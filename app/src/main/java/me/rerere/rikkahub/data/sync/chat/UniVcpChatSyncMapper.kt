package me.rerere.rikkahub.data.sync.chat

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant as JavaInstant
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.uuid.Uuid

object UniVcpChatSyncMapper {
    fun exportConversation(
        conversation: Conversation,
        assistant: Assistant? = null,
        deviceId: String? = null,
        currentPathOnly: Boolean = true,
    ): SyncConversation {
        val messages = if (currentPathOnly) {
            conversation.messageNodes.mapNotNull { node ->
                node.messages.getOrNull(node.selectIndex)?.let { message ->
                    message.toSyncMessage(node = node, branchIndex = node.selectIndex, deviceId = deviceId)
                }
            }
        } else {
            conversation.messageNodes.flatMap { node ->
                node.messages.mapIndexed { index, message ->
                    message.toSyncMessage(node = node, branchIndex = index, deviceId = deviceId)
                }
            }
        }

        return SyncConversation(
            id = conversation.id.toString(),
            title = conversation.title,
            createdAt = conversation.createAt.toEpochMilli(),
            updatedAt = conversation.updateAt.toEpochMilli(),
            pinned = conversation.isPinned,
            source = SyncSource(
                app = CHAT_SYNC_APP_UNIVCP,
                conversationId = conversation.id.toString(),
                assistantId = conversation.assistantId.toString(),
                deviceId = deviceId,
            ),
            assistant = assistant?.toSyncAssistant(deviceId),
            messages = messages,
            currentPathOnly = currentPathOnly,
        )
    }

    fun importConversation(
        syncConversation: SyncConversation,
        assistantId: Uuid,
    ): Conversation {
        val nodes = syncConversation.messages.map { message ->
            MessageNode.of(message.toUiMessage())
        }

        return Conversation(
            id = syncConversation.id.toUuidOrStable(),
            assistantId = assistantId,
            title = syncConversation.title,
            messageNodes = nodes,
            isPinned = syncConversation.pinned,
            createAt = JavaInstant.ofEpochMilli(syncConversation.createdAt),
            updateAt = JavaInstant.ofEpochMilli(syncConversation.updatedAt),
        )
    }

    fun stableAssistantId(syncAssistant: SyncAssistant): Uuid {
        return syncAssistant.id.toUuidOrNull()
            ?: syncAssistant.source.assistantId?.toUuidOrNull()
            ?: Uuid.parse(
                UUID.nameUUIDFromBytes(
                    "univcp-sync-assistant:${syncAssistant.source.app}:${syncAssistant.id}"
                        .toByteArray(StandardCharsets.UTF_8)
                ).toString()
            )
    }

    private fun UIMessage.toSyncMessage(
        node: MessageNode,
        branchIndex: Int,
        deviceId: String?,
    ): SyncMessage {
        return SyncMessage(
            id = id.toString(),
            role = role,
            createdAt = createdAt.toEpochMillis(),
            updatedAt = finishedAt?.toEpochMillis() ?: createdAt.toEpochMillis(),
            modelId = modelId?.toString(),
            nodeId = node.id.toString(),
            branchIndex = branchIndex,
            source = SyncSource(
                app = CHAT_SYNC_APP_UNIVCP,
                conversationId = null,
                deviceId = deviceId,
            ),
            parts = parts.map { it.toSyncPart() },
        )
    }

    private fun Assistant.toSyncAssistant(deviceId: String?): SyncAssistant {
        return SyncAssistant(
            id = id.toString(),
            name = name,
            systemPrompt = systemPrompt,
            source = SyncSource(
                app = CHAT_SYNC_APP_UNIVCP,
                assistantId = id.toString(),
                deviceId = deviceId,
            ),
        )
    }

    private fun SyncMessage.toUiMessage(): UIMessage {
        return UIMessage(
            id = id.toUuidOrStable(),
            role = role,
            parts = parts.mapNotNull { it.toUiPartOrNull() },
            createdAt = createdAt.toLocalDateTime(),
            finishedAt = updatedAt.takeIf { it != createdAt }?.toLocalDateTime(),
            modelId = modelId?.toUuidOrNull(),
        )
    }

    @Suppress("DEPRECATION")
    private fun UIMessagePart.toSyncPart(): SyncMessagePart {
        return when (this) {
            is UIMessagePart.Text -> SyncMessagePart.Text(
                text = text,
                metadata = metadata,
            )

            is UIMessagePart.Image -> SyncMessagePart.Asset(
                kind = SyncAssetKind.IMAGE,
                url = url,
                mime = metadata?.stringValue("mime"),
                metadata = metadata,
            )

            is UIMessagePart.Video -> SyncMessagePart.Asset(
                kind = SyncAssetKind.VIDEO,
                url = url,
                mime = metadata?.stringValue("mime"),
                metadata = metadata,
            )

            is UIMessagePart.Audio -> SyncMessagePart.Asset(
                kind = SyncAssetKind.AUDIO,
                url = url,
                mime = metadata?.stringValue("mime"),
                metadata = metadata,
            )

            is UIMessagePart.Document -> SyncMessagePart.Asset(
                kind = SyncAssetKind.DOCUMENT,
                url = url,
                fileName = fileName,
                mime = mime,
                metadata = metadata,
            )

            is UIMessagePart.Reasoning -> SyncMessagePart.Reasoning(
                text = reasoning,
                createdAt = createdAt.toEpochMilliseconds(),
                finishedAt = finishedAt?.toEpochMilliseconds(),
                metadata = metadata,
            )

            is UIMessagePart.Tool -> SyncMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = toolName,
                input = input,
                output = output.map { it.toSyncPart() },
                approvalState = approvalState.toSyncName(),
                metadata = metadata,
            )

            is UIMessagePart.Search,
            is UIMessagePart.ToolCall,
            is UIMessagePart.ToolResult,
                -> SyncMessagePart.Raw(
                originalType = this::class.simpleName ?: "UIMessagePart",
                payload = JsonInstant.encodeToJsonElement(UIMessagePart.serializer(), this),
                metadata = metadata,
            )
        }
    }

    private fun SyncMessagePart.toUiPartOrNull(): UIMessagePart? {
        return when (this) {
            is SyncMessagePart.Text -> UIMessagePart.Text(
                text = text,
                metadata = metadata,
            )

            is SyncMessagePart.Asset -> when (kind) {
                SyncAssetKind.IMAGE -> UIMessagePart.Image(
                    url = url,
                    metadata = metadata,
                )

                SyncAssetKind.VIDEO -> UIMessagePart.Video(
                    url = url,
                    metadata = metadata,
                )

                SyncAssetKind.AUDIO -> UIMessagePart.Audio(
                    url = url,
                    metadata = metadata,
                )

                SyncAssetKind.DOCUMENT,
                SyncAssetKind.FILE,
                    -> UIMessagePart.Document(
                    url = url,
                    fileName = fileName ?: url.substringAfterLast('/').ifBlank { "attachment" },
                    mime = mime ?: "application/octet-stream",
                    metadata = metadata,
                )
            }

            is SyncMessagePart.Reasoning -> UIMessagePart.Reasoning(
                reasoning = text,
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(createdAt),
                finishedAt = finishedAt?.let(kotlin.time.Instant.Companion::fromEpochMilliseconds),
                metadata = metadata,
            )

            is SyncMessagePart.Tool -> UIMessagePart.Tool(
                toolCallId = toolCallId,
                toolName = toolName,
                input = input,
                output = output.mapNotNull { it.toUiPartOrNull() },
                approvalState = approvalState.toToolApprovalState(),
                metadata = metadata,
            )

            is SyncMessagePart.Raw -> runCatching {
                JsonInstant.decodeFromJsonElement(UIMessagePart.serializer(), payload)
            }.getOrNull()
        }
    }
}

private val syncTimeZone: TimeZone
    get() = TimeZone.currentSystemDefault()

private fun LocalDateTime.toEpochMillis(): Long {
    return toInstant(syncTimeZone).toEpochMilliseconds()
}

private fun Long.toLocalDateTime(): LocalDateTime {
    return kotlin.time.Instant.fromEpochMilliseconds(this).toLocalDateTime(syncTimeZone)
}

private fun String.toUuidOrNull(): Uuid? {
    return runCatching { Uuid.parse(this) }.getOrNull()
}

private fun String.toUuidOrStable(): Uuid {
    return toUuidOrNull() ?: Uuid.parse(
        UUID.nameUUIDFromBytes("univcp-sync:$this".toByteArray(StandardCharsets.UTF_8)).toString()
    )
}

private fun JsonObject.stringValue(key: String): String? {
    return this[key]?.let { element ->
        runCatching { Json.decodeFromString<String>(JsonInstant.encodeToString(element)) }.getOrNull()
    }
}

private fun ToolApprovalState.toSyncName(): String {
    return when (this) {
        ToolApprovalState.Auto -> "auto"
        ToolApprovalState.Pending -> "pending"
        ToolApprovalState.Approved -> "approved"
        is ToolApprovalState.Denied -> "denied"
        is ToolApprovalState.Answered -> "answered"
    }
}

private fun String.toToolApprovalState(): ToolApprovalState {
    return when (lowercase()) {
        "pending" -> ToolApprovalState.Pending
        "approved" -> ToolApprovalState.Approved
        "denied" -> ToolApprovalState.Denied()
        "answered" -> ToolApprovalState.Answered("")
        else -> ToolApprovalState.Auto
    }
}
