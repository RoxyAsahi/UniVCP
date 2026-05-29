package me.rerere.rikkahub.data.sync.chat

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class FirebaseRtdbChatSyncConfig(
    val databaseUrl: String,
    val roomId: String = "default",
    val deviceId: String,
    val authToken: String? = null,
    val pathPrefix: String = "chatSync",
    val incrementalPull: Boolean = false,
)

class FirebaseRtdbChatSyncRemoteStore(
    private val client: OkHttpClient,
    private val config: FirebaseRtdbChatSyncConfig,
    private val json: Json = JsonInstant,
) : ChatSyncRemoteStore {
    override suspend fun pullChanges(since: ChatSyncCursor?): ChatSyncPullResult {
        val request = Request.Builder()
            .url(collectionUrl(since))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("Firebase chat sync pull failed: ${response.code} $body")
            }

            val envelopes = decodeEnvelopeMap(body)
                .sortedBy { it.updatedAt }
            val remoteEnvelopes = envelopes.filterRemote(since)

            return ChatSyncPullResult(
                conversations = remoteEnvelopes.map { it.conversation.withEnvelopeUpdatedAt(it.updatedAt) },
                nextCursor = envelopes.nextCursor(since),
            )
        }
    }

    override suspend fun pushChanges(conversations: List<SyncConversation>): ChatSyncPushResult {
        val pushed = mutableListOf<String>()
        val rejected = mutableListOf<ChatSyncRejection>()

        for (conversation in conversations) {
            val envelope = conversation.toEnvelope()
            val body = json.encodeToString(envelope).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(conversationUrl(conversation.id))
                .put(body)
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (!response.isSuccessful) {
                        error("Firebase chat sync push failed: ${response.code} $responseBody")
                    }
                }
            }.onSuccess {
                pushed += conversation.id
            }.onFailure { error ->
                rejected += ChatSyncRejection(
                    conversationId = conversation.id,
                    reason = error.message ?: error::class.qualifiedName.orEmpty(),
                )
            }
        }

        return ChatSyncPushResult(
            pushedConversationIds = pushed,
            rejected = rejected,
        )
    }

    override suspend fun getPresence(): List<ChatSyncPeerPresence> {
        val request = Request.Builder()
            .url(presenceUrl())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("Firebase chat sync presence failed: ${response.code} $body")
            }
            if (body.isBlank() || body == "null") return emptyList()
            val element = json.parseToJsonElement(body)
            if (element == JsonNull) return emptyList()
            return (element as? JsonObject)
                ?.values
                ?.mapNotNull { value ->
                    runCatching {
                        json.decodeFromJsonElement<ChatSyncPeerPresence>(value)
                    }.getOrNull()
                }
                .orEmpty()
        }
    }

    override fun observeChanges(since: ChatSyncCursor?): Flow<ChatSyncRemoteEvent> {
        val request = Request.Builder()
            .url(collectionUrl(since))
            .header("Accept", "text/event-stream")
            .build()

        return client.sseFlow(request).transform { event ->
            if (event is SseEvent.Event) {
                decodeFirebaseStreamPayload(event.data)
                    .filterRemote(since)
                    .sortedBy { it.updatedAt }
                    .forEach { envelope ->
                        emit(
                            ChatSyncRemoteEvent(
                                conversation = envelope.conversation.withEnvelopeUpdatedAt(envelope.updatedAt),
                                cursor = ChatSyncCursor(updatedAfter = envelope.updatedAt),
                                sourceDeviceId = envelope.sourceDeviceId,
                            )
                        )
                    }
            }
        }
    }

    private fun SyncConversation.toEnvelope(): ChatSyncRemoteEnvelope {
        val contentUpdatedAt = maxOf(updatedAt, messages.maxOfOrNull { maxOf(it.updatedAt, it.createdAt) } ?: updatedAt)
        return ChatSyncRemoteEnvelope(
            id = id,
            updatedAt = maxOf(System.currentTimeMillis(), contentUpdatedAt),
            sourceDeviceId = config.deviceId,
            conversation = this,
        )
    }

    private fun SyncConversation.withEnvelopeUpdatedAt(envelopeUpdatedAt: Long): SyncConversation {
        return copy(updatedAt = maxOf(updatedAt, envelopeUpdatedAt))
    }

    private fun List<ChatSyncRemoteEnvelope>.filterRemote(since: ChatSyncCursor?): List<ChatSyncRemoteEnvelope> {
        val updatedAfter = since?.updatedAfter ?: 0L
        return filter { envelope ->
            envelope.updatedAt > updatedAfter && envelope.sourceDeviceId != config.deviceId
        }
    }

    private fun List<ChatSyncRemoteEnvelope>.nextCursor(previous: ChatSyncCursor?): ChatSyncCursor {
        val updatedAfter = maxOf(previous?.updatedAfter ?: 0L, maxOfOrNull { it.updatedAt } ?: 0L)
        return ChatSyncCursor(updatedAfter = updatedAfter)
    }

    private fun decodeEnvelopeMap(body: String): List<ChatSyncRemoteEnvelope> {
        if (body.isBlank() || body == "null") return emptyList()
        val element = json.parseToJsonElement(body)
        if (element == JsonNull) return emptyList()
        return (element as? JsonObject)
            ?.values
            ?.mapNotNull(::decodeEnvelope)
            .orEmpty()
    }

    private fun decodeFirebaseStreamPayload(body: String): List<ChatSyncRemoteEnvelope> {
        val payload = runCatching {
            json.decodeFromString<FirebaseStreamPayload>(body)
        }.getOrNull() ?: return emptyList()
        val data = payload.data ?: return emptyList()
        if (data == JsonNull) return emptyList()

        val dataObject = data as? JsonObject ?: return decodeEnvelope(data)?.let { listOf(it) }.orEmpty()
        if (dataObject.looksLikeEnvelope()) {
            return decodeEnvelope(dataObject)?.let { listOf(it) }.orEmpty()
        }
        return dataObject.values.mapNotNull(::decodeEnvelope)
    }

    private fun decodeEnvelope(element: JsonElement): ChatSyncRemoteEnvelope? {
        if (element == JsonNull) return null
        return runCatching {
            json.decodeFromJsonElement<ChatSyncRemoteEnvelope>(element)
        }.getOrNull()
    }

    private fun JsonObject.looksLikeEnvelope(): Boolean {
        return containsKey("conversation") && containsKey("updatedAt")
    }

    private fun collectionUrl(since: ChatSyncCursor? = null): String {
        val params = queryParams {
            val updatedAfter = since?.updatedAfter ?: 0L
            if (config.incrementalPull && updatedAfter > 0L) {
                add("orderBy" to "\"updatedAt\"")
                add("startAt" to (updatedAfter + 1L).toString())
            }
        }
        return "${baseUrl()}/${config.pathPrefix}/${syncKey(config.roomId)}/conversations.json${queryString(params)}"
    }

    private fun conversationUrl(conversationId: String): String {
        return "${baseUrl()}/${config.pathPrefix}/${syncKey(config.roomId)}/conversations/${syncKey(conversationId)}.json" +
            queryString()
    }

    private fun presenceUrl(): String {
        return "${baseUrl()}/${config.pathPrefix}/${syncKey(config.roomId)}/presence.json${queryString()}"
    }

    private fun baseUrl(): String {
        return config.databaseUrl.trimEnd('/')
    }

    private fun queryString(params: List<Pair<String, String>> = emptyList()): String {
        val allParams = params.toMutableList()
        config.authToken?.takeIf { it.isNotBlank() }?.let { token ->
            allParams += "auth" to token
        }
        if (allParams.isEmpty()) return ""
        return allParams.joinToString(prefix = "?", separator = "&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
    }

    private inline fun queryParams(block: MutableList<Pair<String, String>>.() -> Unit): List<Pair<String, String>> {
        return buildList(block)
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun syncKey(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    @Serializable
    private data class FirebaseStreamPayload(
        val path: String = "/",
        val data: JsonElement? = null,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
