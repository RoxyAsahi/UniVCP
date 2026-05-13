package me.rerere.rikkahub.data.sync.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole

const val CHAT_SYNC_SCHEMA_VERSION = 1

const val CHAT_SYNC_APP_UNIVCP = "univcp"
const val CHAT_SYNC_APP_VCPCHAT = "vcpchat"

@Serializable
data class ChatSyncBundle(
    val schemaVersion: Int = CHAT_SYNC_SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val conversations: List<SyncConversation>,
    val metadata: JsonObject = emptyJsonObject(),
)

@Serializable
data class SyncConversation(
    val id: String,
    val title: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val pinned: Boolean = false,
    val source: SyncSource,
    val assistant: SyncAssistant? = null,
    val messages: List<SyncMessage>,
    val currentPathOnly: Boolean = true,
    val metadata: JsonObject = emptyJsonObject(),
)

@Serializable
data class SyncAssistant(
    val id: String,
    val name: String = "",
    val systemPrompt: String = "",
    val source: SyncSource,
    val metadata: JsonObject = emptyJsonObject(),
)

@Serializable
data class SyncSource(
    val app: String,
    val conversationId: String? = null,
    val assistantId: String? = null,
    val agentId: String? = null,
    val topicId: String? = null,
    val itemType: String? = null,
    val deviceId: String? = null,
    val raw: JsonObject = emptyJsonObject(),
)

@Serializable
data class SyncMessage(
    val id: String,
    val role: MessageRole,
    val name: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val modelId: String? = null,
    val nodeId: String? = null,
    val branchIndex: Int? = null,
    val source: SyncSource? = null,
    val parts: List<SyncMessagePart>,
    val metadata: JsonObject = emptyJsonObject(),
)

@Serializable
sealed class SyncMessagePart {
    abstract val metadata: JsonObject?

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        override val metadata: JsonObject? = null,
    ) : SyncMessagePart()

    @Serializable
    @SerialName("asset")
    data class Asset(
        val kind: SyncAssetKind,
        val url: String,
        val fileName: String? = null,
        val mime: String? = null,
        val sha256: String? = null,
        val sizeBytes: Long? = null,
        override val metadata: JsonObject? = null,
    ) : SyncMessagePart()

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val text: String,
        val createdAt: Long,
        val finishedAt: Long? = null,
        override val metadata: JsonObject? = null,
    ) : SyncMessagePart()

    @Serializable
    @SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<SyncMessagePart> = emptyList(),
        val approvalState: String = "auto",
        override val metadata: JsonObject? = null,
    ) : SyncMessagePart()

    @Serializable
    @SerialName("raw")
    data class Raw(
        val originalType: String,
        val payload: JsonElement,
        override val metadata: JsonObject? = null,
    ) : SyncMessagePart()
}

@Serializable
enum class SyncAssetKind {
    @SerialName("image")
    IMAGE,

    @SerialName("video")
    VIDEO,

    @SerialName("audio")
    AUDIO,

    @SerialName("document")
    DOCUMENT,

    @SerialName("file")
    FILE,
}

fun emptyJsonObject(): JsonObject = JsonObject(emptyMap())

fun List<SyncMessagePart>.plainText(): String {
    return mapNotNull { part ->
        when (part) {
            is SyncMessagePart.Text -> part.text
            is SyncMessagePart.Reasoning -> part.text
            else -> null
        }
    }.joinToString("\n")
}
