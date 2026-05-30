package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.uuid.Uuid

private const val TAG = "VcpInterruptManager"
private const val VCP_MESSAGE_ID_SUFFIX_LENGTH = 7
private const val VCP_MESSAGE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

class VcpInterruptManager(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private data class ActiveRequest(
        val messageId: String,
        val provider: ProviderSetting.OpenAI,
    )

    private val activeRequests = ConcurrentHashMap<Uuid, ActiveRequest>()

    fun newRequestId(): String {
        val suffix = buildString {
            repeat(VCP_MESSAGE_ID_SUFFIX_LENGTH) {
                append(VCP_MESSAGE_ID_CHARS[Random.nextInt(VCP_MESSAGE_ID_CHARS.length)])
            }
        }
        return "msg_${System.currentTimeMillis()}_assistant_$suffix"
    }

    fun register(
        conversationId: Uuid,
        messageId: String,
        provider: ProviderSetting,
    ) {
        if (provider is ProviderSetting.OpenAI && provider.enableVcpInterrupt && !provider.useResponseApi) {
            activeRequests[conversationId] = ActiveRequest(messageId, provider)
        } else {
            activeRequests.remove(conversationId)
        }
    }

    fun unregister(conversationId: Uuid, messageId: String) {
        activeRequests.computeIfPresent(conversationId) { _, active ->
            active.takeUnless { it.messageId == messageId }
        }
    }

    suspend fun interrupt(conversationId: Uuid): Boolean {
        val active = activeRequests[conversationId] ?: return false
        return sendInterrupt(active)
    }

    private suspend fun sendInterrupt(active: ActiveRequest): Boolean = withContext(Dispatchers.IO) {
        val interruptUrl = buildInterruptUrl(active.provider) ?: return@withContext false
        val body = buildInterruptBody(active.messageId)
        val request = Request.Builder()
            .url(interruptUrl)
            .addHeader("Authorization", "Bearer ${active.provider.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "VCP interrupt failed: ${response.code} ${response.body.string()}")
                    return@withContext false
                }
                true
            }
        }.getOrElse {
            Log.w(TAG, "VCP interrupt request failed", it)
            false
        }
    }

    internal fun buildInterruptBody(messageId: String): String = json.encodeToString(
        buildJsonObject {
            put("requestId", messageId)
        }
    )

    internal fun buildInterruptUrl(provider: ProviderSetting.OpenAI): HttpUrl? {
        if (provider.useResponseApi) return null
        val requestUrl = buildString {
            append(provider.baseUrl.trimEnd('/'))
            append('/')
            append(provider.chatCompletionsPath.trimStart('/'))
        }.toHttpUrlOrNull() ?: return null

        val pathSegments = requestUrl.pathSegments.filter { it.isNotBlank() }
        val v1Index = pathSegments.indexOf("v1")
        val prefix = when {
            v1Index >= 0 -> pathSegments.take(v1Index + 1)
            pathSegments.size >= 2 -> pathSegments.dropLast(2)
            else -> emptyList()
        }

        return requestUrl.newBuilder()
            .encodedPath("/" + (prefix + "interrupt").joinToString("/"))
            .query(null)
            .fragment(null)
            .build()
    }
}
