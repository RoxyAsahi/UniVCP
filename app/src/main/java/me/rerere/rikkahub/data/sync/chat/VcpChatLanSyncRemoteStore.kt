package me.rerere.rikkahub.data.sync.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.SseEvent
import me.rerere.common.http.sseFlow
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class VcpChatLanSyncConfig(
    val baseUrl: String,
    val authToken: String? = null,
    val deviceId: String,
    val pollIntervalMs: Long = 15_000L,
    val realtimeEvents: Boolean = true,
    val targetItemType: String = "agent",
    val targetItemId: String? = null,
    val targetTopicId: String? = null,
)

@Serializable
data class VcpChatLanDiagnostics(
    val ok: Boolean = false,
    val checkedAt: Long = 0L,
    val status: JsonObject? = null,
    val checks: List<VcpChatLanDiagnosticCheck> = emptyList(),
)

@Serializable
data class VcpChatLanDiagnosticCheck(
    val name: String,
    val ok: Boolean,
    val detail: String = "",
)

@Serializable
data class VcpChatLanTargetsResult(
    val appDataDir: String? = null,
    val items: List<VcpChatLanTargetItem> = emptyList(),
)

@Serializable
data class VcpChatLanTargetItem(
    val id: String,
    val itemType: String,
    val name: String = id,
    val assistant: SyncAssistant? = null,
    val topics: List<VcpChatLanTopic> = emptyList(),
)

@Serializable
data class VcpChatLanTopic(
    val id: String,
    val name: String? = null,
    val createdAt: Long? = null,
    val locked: Boolean? = null,
    val unread: Boolean? = null,
)

class VcpChatLanSyncRemoteStore(
    private val client: OkHttpClient,
    private val config: VcpChatLanSyncConfig,
    private val json: Json = JsonInstant,
) : ChatSyncRemoteStore {
    suspend fun getDiagnostics(): VcpChatLanDiagnostics {
        return getJson("/chat-sync/diagnostics")
    }

    suspend fun listTargets(itemType: String? = null): VcpChatLanTargetsResult {
        return postJson<VcpChatLanResponse<VcpChatLanTargetsResult>, ListTargetsRequest>(
            path = "/chat-sync/list-targets",
            body = ListTargetsRequest(itemType = itemType),
        ).result ?: error("VCPChat LAN list-targets returned no result")
    }

    suspend fun fetchEmoticonLibrary(regenerate: Boolean = false): VcpChatEmoticonLibrarySnapshot {
        return postJson<VcpChatLanResponse<VcpChatEmoticonLibrarySnapshot>, EmoticonLibraryRequest>(
            path = "/chat-sync/emoticons",
            body = EmoticonLibraryRequest(regenerate = regenerate),
        ).result?.let { snapshot ->
            snapshot.copy(syncedAt = System.currentTimeMillis(), count = snapshot.items.size)
        } ?: error("VCPChat LAN emoticons returned no result")
    }

    suspend fun exportTopic(
        itemType: String = "agent",
        itemId: String,
        topicId: String,
    ): SyncConversation {
        return postJson<VcpChatLanResponse<SyncConversation>, ExportTopicRequest>(
            path = "/chat-sync/export-topic",
            body = ExportTopicRequest(
                itemType = itemType,
                itemId = itemId,
                topicId = topicId,
            ),
        ).result ?: error("VCPChat LAN export-topic returned no result")
    }

    override suspend fun pullChanges(since: ChatSyncCursor?): ChatSyncPullResult {
        val updatedAfter = since?.updatedAfter ?: 0L
        val bundle = postJson<VcpChatLanResponse<ChatSyncBundle>, ExportAllRequest>(
            path = "/chat-sync/export-all",
            body = ExportAllRequest(
                includeEmpty = false,
                updatedAfter = updatedAfter,
                requesterDeviceId = config.deviceId,
            ),
        ).result ?: error("VCPChat LAN export-all returned no result")

        val conversations = bundle.conversations
            .filter { it.updatedAt > updatedAfter }
            .sortedBy { it.updatedAt }
        val nextCursor = ChatSyncCursor(
            updatedAfter = maxOf(
                updatedAfter,
                bundle.conversations.maxOfOrNull { it.updatedAt } ?: updatedAfter,
            )
        )
        return ChatSyncPullResult(
            conversations = conversations,
            nextCursor = nextCursor,
        )
    }

    override suspend fun pushChanges(conversations: List<SyncConversation>): ChatSyncPushResult {
        if (conversations.isEmpty()) return ChatSyncPushResult(emptyList())

        if (conversations.size == 1) {
            return pushSingleConversation(conversations.single())
        }

        val bundle = ChatSyncBundle(conversations = conversations)
        val response = postJson<VcpChatLanResponse<ImportBundleResult>, ImportBundleRequest>(
            path = "/chat-sync/import-bundle",
            body = ImportBundleRequest(
                bundle = bundle,
                overwrite = false,
                itemType = config.targetItemType.takeIf { it.isNotBlank() } ?: "agent",
                targetItemId = config.targetItemId?.takeIf { it.isNotBlank() },
                targetTopicId = config.targetTopicId?.takeIf { it.isNotBlank() },
            ),
        )
        if (response.status != "success") {
            return ChatSyncPushResult(
                pushedConversationIds = emptyList(),
                rejected = conversations.map { conversation ->
                    ChatSyncRejection(
                        conversationId = conversation.id,
                        reason = response.error ?: "VCPChat LAN import failed",
                    )
                }
            )
        }
        return ChatSyncPushResult(
            pushedConversationIds = conversations.map { it.id },
        )
    }

    private fun pushSingleConversation(conversation: SyncConversation): ChatSyncPushResult {
        val response = postJson<VcpChatLanResponse<JsonElement>, ImportTopicRequest>(
            path = "/chat-sync/import-topic",
            body = ImportTopicRequest(
                conversation = conversation,
                overwrite = false,
                itemType = config.targetItemType.takeIf { it.isNotBlank() } ?: "agent",
                targetItemId = config.targetItemId?.takeIf { it.isNotBlank() },
                targetTopicId = config.targetTopicId?.takeIf { it.isNotBlank() },
            ),
        )
        if (response.status != "success") {
            return ChatSyncPushResult(
                pushedConversationIds = emptyList(),
                rejected = listOf(
                    ChatSyncRejection(
                        conversationId = conversation.id,
                        reason = response.error ?: "VCPChat LAN import failed",
                    )
                ),
            )
        }
        return ChatSyncPushResult(pushedConversationIds = listOf(conversation.id))
    }

    override suspend fun getPresence(): List<ChatSyncPeerPresence> {
        val status = getJson<JsonObject>("/chat-sync/status")
        return listOf(
            ChatSyncPeerPresence(
                deviceId = status.stringValue("deviceId") ?: "vcpchat-lan",
                app = CHAT_SYNC_APP_VCPCHAT,
                status = if (status.booleanValue("started") == false) "offline" else "online",
                updatedAt = System.currentTimeMillis(),
                direction = status.stringValue("direction"),
                version = status.stringValue("version"),
            )
        )
    }

    override fun observeChanges(since: ChatSyncCursor?): Flow<ChatSyncRemoteEvent> = flow {
        if (config.realtimeEvents) {
            val realtimeResult = runCatching {
                observeRealtimeChanges(since).collect { emit(it) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }
            if (realtimeResult.isSuccess && !currentCoroutineContext().isActive) {
                return@flow
            }
        }
        emitAll(observePollingChanges(since))
    }

    private fun observePollingChanges(since: ChatSyncCursor?): Flow<ChatSyncRemoteEvent> = flow {
        var cursor = since
        val interval = config.pollIntervalMs.coerceAtLeast(1_000L)
        while (currentCoroutineContext().isActive) {
            val result = pullChanges(cursor)
            result.conversations.forEach { conversation ->
                val eventCursor = ChatSyncCursor(updatedAfter = conversation.updatedAt)
                emit(
                    ChatSyncRemoteEvent(
                        conversation = conversation,
                        cursor = eventCursor,
                        sourceDeviceId = CHAT_SYNC_APP_VCPCHAT,
                    )
                )
                cursor = eventCursor
            }
            result.nextCursor?.let { cursor = it }
            delay(interval)
        }
    }

    private fun observeRealtimeChanges(since: ChatSyncCursor?): Flow<ChatSyncRemoteEvent> = flow {
        var cursor = since
        val request = Request.Builder()
            .url(endpoint("/chat-sync/events"))
            .applyAuthHeaders()
            .header("Accept", "text/event-stream")
            .get()
            .build()

        client.sseFlow(request).collect { event ->
            when (event) {
                is SseEvent.Event -> {
                    if (event.type != "topic-changed") return@collect
                    val topicEvent = json.decodeFromString<VcpChatLanTopicChangedEvent>(event.data)
                    val updatedAfter = cursor?.updatedAfter ?: 0L
                    if (topicEvent.updatedAt <= updatedAfter) return@collect

                    val conversation = exportTopic(
                        itemType = topicEvent.itemType,
                        itemId = topicEvent.itemId,
                        topicId = topicEvent.topicId,
                    )
                    val eventCursor = ChatSyncCursor(
                        updatedAfter = maxOf(topicEvent.updatedAt, conversation.updatedAt),
                    )
                    emit(
                        ChatSyncRemoteEvent(
                            conversation = conversation,
                            cursor = eventCursor,
                            sourceDeviceId = topicEvent.sourceDeviceId ?: CHAT_SYNC_APP_VCPCHAT,
                        )
                    )
                    cursor = eventCursor
                }

                is SseEvent.Failure -> {
                    throw event.throwable ?: IllegalStateException("VCPChat LAN SSE failed")
                }

                SseEvent.Closed -> {
                    throw IllegalStateException("VCPChat LAN SSE closed")
                }

                SseEvent.Open -> Unit
            }
        }
    }

    private inline fun <reified T> getJson(path: String): T {
        val request = Request.Builder()
            .url(endpoint(path))
            .applyAuthHeaders()
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("VCPChat LAN request failed: ${response.code} $body")
            }
            return json.decodeFromString(body)
        }
    }

    private inline fun <reified T, reified B> postJson(path: String, body: B): T {
        val jsonBody = json.encodeToJsonElement(body).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint(path))
            .applyAuthHeaders()
            .post(jsonBody)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                error("VCPChat LAN request failed: ${response.code} $responseBody")
            }
            return json.decodeFromString(responseBody)
        }
    }

    private fun endpoint(path: String): String {
        val normalizedBase = config.baseUrl.trim().trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return normalizedBase + normalizedPath
    }

    private fun Request.Builder.applyAuthHeaders(): Request.Builder {
        val token = config.authToken?.takeIf { it.isNotBlank() } ?: return this
        return header("Authorization", "Bearer $token")
            .header("x-chat-sync-token", token)
    }

    @Serializable
    private data class ExportAllRequest(
        val includeEmpty: Boolean = false,
        val updatedAfter: Long = 0L,
        val requesterDeviceId: String? = null,
    )

    @Serializable
    private data class ExportTopicRequest(
        val itemType: String = "agent",
        val itemId: String,
        val topicId: String,
    )

    @Serializable
    private data class ListTargetsRequest(
        val itemType: String? = null,
    )

    @Serializable
    private data class EmoticonLibraryRequest(
        val regenerate: Boolean = false,
    )

    @Serializable
    private data class VcpChatLanTopicChangedEvent(
        val type: String = "topic-changed",
        val itemType: String = "agent",
        val itemId: String,
        val topicId: String,
        val updatedAt: Long = 0L,
        val sourceDeviceId: String? = null,
    )

    @Serializable
    private data class ImportBundleRequest(
        val bundle: ChatSyncBundle,
        val overwrite: Boolean = false,
        val itemType: String = "agent",
        val targetItemId: String? = null,
        val targetTopicId: String? = null,
    )

    @Serializable
    private data class ImportTopicRequest(
        val conversation: SyncConversation,
        val overwrite: Boolean = false,
        val itemType: String = "agent",
        val targetItemId: String? = null,
        val targetTopicId: String? = null,
    )

    @Serializable
    private data class VcpChatLanResponse<T>(
        val status: String,
        val result: T? = null,
        val error: String? = null,
    )

    @Serializable
    private data class ImportBundleResult(
        val imported: Int = 0,
        val results: List<JsonElement> = emptyList(),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private fun JsonObject.stringValue(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.booleanValue(key: String): Boolean? {
    return this[key]?.jsonPrimitive?.booleanOrNull
}
