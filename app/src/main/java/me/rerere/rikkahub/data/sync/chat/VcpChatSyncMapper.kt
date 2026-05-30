package me.rerere.rikkahub.data.sync.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole

object VcpChatSyncMapper {
    fun historyToSyncConversation(
        agentId: String,
        topicId: String,
        topicName: String,
        history: JsonArray,
        itemType: String = "agent",
        assistant: SyncAssistant? = null,
    ): SyncConversation {
        val messages = history.mapIndexedNotNull { index, element ->
            val message = element as? JsonObject ?: return@mapIndexedNotNull null
            message.toSyncMessage(
                agentId = agentId,
                topicId = topicId,
                itemType = itemType,
                fallbackIndex = index,
            )
        }
        val createdAt = messages.minOfOrNull { it.createdAt } ?: System.currentTimeMillis()
        val updatedAt = messages.maxOfOrNull { it.updatedAt } ?: createdAt

        return SyncConversation(
            id = stableVcpConversationId(agentId = agentId, topicId = topicId),
            title = topicName,
            createdAt = createdAt,
            updatedAt = updatedAt,
            source = SyncSource(
                app = CHAT_SYNC_APP_VCPCHAT,
                agentId = agentId,
                topicId = topicId,
                itemType = itemType,
            ),
            assistant = assistant,
            messages = messages,
            currentPathOnly = true,
        )
    }

    fun syncConversationToHistory(
        conversation: SyncConversation,
        userName: String = "用户",
        assistantName: String = "AI",
    ): JsonArray {
        val resolvedAssistantName = conversation.assistant?.name?.ifBlank { null } ?: assistantName
        return buildJsonArray {
            conversation.messages.forEach { message ->
                add(message.toVcpChatMessage(userName = userName, assistantName = resolvedAssistantName))
            }
        }
    }

    private fun JsonObject.toSyncMessage(
        agentId: String,
        topicId: String,
        itemType: String,
        fallbackIndex: Int,
    ): SyncMessage {
        val role = roleValue("role")
        val timestamp = longValue("timestamp") ?: System.currentTimeMillis()
        val id = stringValue("id") ?: "vcp_${topicId}_${timestamp}_$fallbackIndex"
        val parts = buildList {
            val content = this@toSyncMessage["content"]
            when {
                content == null || content is JsonNull -> Unit
                content is JsonPrimitive && content.isString -> add(SyncMessagePart.Text(content.content))
                else -> add(SyncMessagePart.Raw(originalType = "vcpchat_content", payload = content))
            }
            arrayValue("attachments")?.forEach { attachment ->
                (attachment as? JsonObject)?.toAssetPartOrNull()?.let(::add)
            }
        }

        return SyncMessage(
            id = id,
            role = role,
            name = stringValue("name"),
            createdAt = timestamp,
            updatedAt = timestamp,
            source = SyncSource(
                app = CHAT_SYNC_APP_VCPCHAT,
                agentId = stringValue("agentId") ?: agentId,
                topicId = topicId,
                itemType = itemType,
            ),
            parts = parts,
            metadata = JsonObject(mapOf("vcpchat" to this)),
        )
    }

    private fun SyncMessage.toVcpChatMessage(
        userName: String,
        assistantName: String,
    ): JsonObject {
        val text = parts.plainText()
        val attachments = parts.filterIsInstance<SyncMessagePart.Asset>()
        val original = metadata["vcpchat"] as? JsonObject

        return buildJsonObject {
            original?.forEach { (key, value) ->
                if (key !in generatedVcpChatKeys) {
                    put(key, value)
                }
            }
            put("role", role.serialValue())
            put(
                "name",
                name ?: when (role) {
                    MessageRole.USER -> userName
                    MessageRole.ASSISTANT -> assistantName
                    MessageRole.SYSTEM -> "系统"
                    MessageRole.TOOL -> "Tool"
                }
            )
            put("content", text)
            put("timestamp", createdAt)
            put("id", id)
            if (attachments.isNotEmpty()) {
                put(
                    "attachments",
                    buildJsonArray {
                        attachments.forEach { add(it.toVcpChatAttachment()) }
                    }
                )
            }
            if (role == MessageRole.ASSISTANT) {
                put("isThinking", false)
                put("agentId", source?.agentId ?: conversationAgentIdFromSource())
            }
        }
    }

    private fun JsonObject.toAssetPartOrNull(): SyncMessagePart.Asset? {
        val mime = stringValue("type")
            ?: objectValue("_fileManagerData")?.stringValue("type")
            ?: stringValue("mime")
        val url = stringValue("src")
            ?: stringValue("internalPath")
            ?: objectValue("_fileManagerData")?.stringValue("internalPath")
            ?: return null
        val fileName = stringValue("name")
            ?: objectValue("_fileManagerData")?.stringValue("name")
        val size = longValue("size")
            ?: objectValue("_fileManagerData")?.longValue("size")
        val hash = stringValue("hash")
            ?: objectValue("_fileManagerData")?.stringValue("hash")

        return SyncMessagePart.Asset(
            kind = mime.toAssetKind(),
            url = url,
            fileName = fileName,
            mime = mime,
            sha256 = hash,
            sizeBytes = size,
            metadata = JsonObject(mapOf("vcpchat" to this)),
        )
    }

    private fun SyncMessagePart.Asset.toVcpChatAttachment(): JsonObject {
        val original = metadata?.get("vcpchat") as? JsonObject
        return buildJsonObject {
            original?.forEach { (key, value) ->
                if (key !in generatedVcpChatAttachmentKeys) {
                    put(key, value)
                }
            }
            put("type", mime ?: kind.defaultMime())
            put("src", url)
            put("name", fileName ?: url.substringAfterLast('/').ifBlank { "attachment" })
            sizeBytes?.let { put("size", it) }
            sha256?.let { put("hash", it) }
        }
    }
}

private val generatedVcpChatKeys = setOf(
    "role",
    "name",
    "content",
    "timestamp",
    "id",
    "attachments",
)

private val generatedVcpChatAttachmentKeys = setOf(
    "type",
    "src",
    "name",
    "size",
    "hash",
)

private fun stableVcpConversationId(agentId: String, topicId: String): String {
    return "vcpchat:$agentId:$topicId"
}

private fun SyncMessage.conversationAgentIdFromSource(): String {
    return source?.agentId ?: ""
}

private fun JsonObject.stringValue(key: String): String? {
    return this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

private fun JsonObject.longValue(key: String): Long? {
    val primitive = this[key]?.jsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
}

private fun JsonObject.arrayValue(key: String): JsonArray? {
    return this[key]?.let { runCatching { it.jsonArray }.getOrNull() }
}

private fun JsonObject.objectValue(key: String): JsonObject? {
    return this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
}

private fun JsonObject.roleValue(key: String): MessageRole {
    return when (stringValue(key)?.lowercase()) {
        "system" -> MessageRole.SYSTEM
        "assistant" -> MessageRole.ASSISTANT
        "tool" -> MessageRole.TOOL
        else -> MessageRole.USER
    }
}

private fun MessageRole.serialValue(): String {
    return when (this) {
        MessageRole.SYSTEM -> "system"
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.TOOL -> "tool"
    }
}

private fun String?.toAssetKind(): SyncAssetKind {
    return when {
        this?.startsWith("image/") == true -> SyncAssetKind.IMAGE
        this?.startsWith("video/") == true -> SyncAssetKind.VIDEO
        this?.startsWith("audio/") == true -> SyncAssetKind.AUDIO
        this?.startsWith("text/") == true -> SyncAssetKind.DOCUMENT
        this?.contains("pdf") == true -> SyncAssetKind.DOCUMENT
        else -> SyncAssetKind.FILE
    }
}

private fun SyncAssetKind.defaultMime(): String {
    return when (this) {
        SyncAssetKind.IMAGE -> "image/*"
        SyncAssetKind.VIDEO -> "video/*"
        SyncAssetKind.AUDIO -> "audio/*"
        SyncAssetKind.DOCUMENT -> "application/octet-stream"
        SyncAssetKind.FILE -> "application/octet-stream"
    }
}
