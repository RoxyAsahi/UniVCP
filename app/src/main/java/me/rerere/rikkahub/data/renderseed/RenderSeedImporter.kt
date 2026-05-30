package me.rerere.rikkahub.data.renderseed

import android.content.Context
import android.util.Log
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

private const val TAG = "RenderSeedImporter"
private const val SEED_ASSET = "render_seed/chat_render_seed.json"

class RenderSeedImporter(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
    private val json: Json,
) {
    suspend fun seedIfEnabled() {
        if (!BuildConfig.UNIVCP_RENDER_SEED_ENABLED) return

        val bundle = runCatching {
            context.assets.open(SEED_ASSET).bufferedReader().use { reader ->
                json.decodeFromString(RenderSeedBundle.serializer(), reader.readText())
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read $SEED_ASSET", error)
            return
        }

        val settings = settingsStore.settingsFlowRaw.firstUsable()
        val assistantBySeedId = bundle.assistants.associate { seed ->
            seed.id to seed.toAssistant(settings.getCurrentAssistant())
        }
        val nextAssistants = settings.assistants
            .filterNot { assistant -> assistant.id in assistantBySeedId.values.map { it.id }.toSet() }
            .plus(assistantBySeedId.values)

        if (nextAssistants != settings.assistants) {
            settingsStore.update(settings.copy(assistants = nextAssistants))
        }

        var inserted = 0
        var updated = 0
        for (seedConversation in bundle.conversations) {
            val conversation = seedConversation.toConversation(
                assistantId = assistantBySeedId[seedConversation.assistantSeedId]?.id
                    ?: settings.getCurrentAssistant().id
            )
            val existing = conversationRepository.getConversationById(conversation.id)
            if (existing == null) {
                conversationRepository.insertConversation(conversation)
                inserted += 1
            } else if (existing.updateAt.toEpochMilli() < conversation.updateAt.toEpochMilli()) {
                conversationRepository.updateConversation(conversation)
                updated += 1
            }
        }

        Log.i(TAG, "Seeded render conversations inserted=$inserted updated=$updated")
    }
}

private suspend fun kotlinx.coroutines.flow.Flow<me.rerere.rikkahub.data.datastore.Settings>.firstUsable() =
    first { !it.init }

private fun RenderSeedAssistant.toAssistant(template: Assistant): Assistant {
    return template.copy(
        id = Uuid.parse(id),
        name = name,
        systemPrompt = systemPrompt,
    )
}

private fun RenderSeedConversation.toConversation(assistantId: Uuid): Conversation {
    return Conversation(
        id = Uuid.parse(id),
        assistantId = assistantId,
        title = title,
        messageNodes = messages.map { seedMessage ->
            MessageNode(
                id = Uuid.parse(seedMessage.nodeId),
                messages = listOf(seedMessage.toUiMessage()),
                selectIndex = 0,
            )
        },
        chatSuggestions = suggestions,
        isPinned = true,
        createAt = Instant.ofEpochMilli(createdAt),
        updateAt = Instant.ofEpochMilli(updatedAt),
    )
}

private fun RenderSeedMessage.toUiMessage(): UIMessage {
    return UIMessage(
        id = Uuid.parse(id),
        role = role,
        parts = parts.map { it.toUiPart() },
        createdAt = KotlinInstant.fromEpochMilliseconds(createdAt).toLocalDateTime(),
        finishedAt = updatedAt.takeIf { it != createdAt }
            ?.let { KotlinInstant.fromEpochMilliseconds(it).toLocalDateTime() },
    )
}

private fun RenderSeedPart.toUiPart(): UIMessagePart {
    return when (this) {
        is RenderSeedPart.Text -> UIMessagePart.Text(text)
        is RenderSeedPart.Reasoning -> UIMessagePart.Reasoning(
            reasoning = text,
            createdAt = KotlinInstant.fromEpochMilliseconds(createdAt),
            finishedAt = finishedAt?.let(KotlinInstant.Companion::fromEpochMilliseconds),
        )

        is RenderSeedPart.Image -> UIMessagePart.Image(url)
        is RenderSeedPart.Document -> UIMessagePart.Document(
            url = url,
            fileName = fileName,
            mime = mime,
        )

        is RenderSeedPart.Tool -> UIMessagePart.Tool(
            toolCallId = toolCallId,
            toolName = toolName,
            input = input,
            output = output.map { it.toUiPart() },
            approvalState = approvalState.toToolApprovalState(),
        )
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

private fun KotlinInstant.toLocalDateTime(): kotlinx.datetime.LocalDateTime {
    return toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
}

@Serializable
private data class RenderSeedBundle(
    val version: Int,
    val assistants: List<RenderSeedAssistant>,
    val conversations: List<RenderSeedConversation>,
)

@Serializable
private data class RenderSeedAssistant(
    val id: String,
    val name: String,
    val systemPrompt: String,
)

@Serializable
private data class RenderSeedConversation(
    val id: String,
    val assistantSeedId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<RenderSeedMessage>,
    val suggestions: List<String> = emptyList(),
)

@Serializable
private data class RenderSeedMessage(
    val id: String,
    val nodeId: String,
    val role: MessageRole,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val parts: List<RenderSeedPart>,
)

@Serializable
private sealed class RenderSeedPart {
    @Serializable
    @kotlinx.serialization.SerialName("text")
    data class Text(val text: String) : RenderSeedPart()

    @Serializable
    @kotlinx.serialization.SerialName("reasoning")
    data class Reasoning(
        val text: String,
        val createdAt: Long,
        val finishedAt: Long? = null,
    ) : RenderSeedPart()

    @Serializable
    @kotlinx.serialization.SerialName("image")
    data class Image(val url: String) : RenderSeedPart()

    @Serializable
    @kotlinx.serialization.SerialName("document")
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String,
    ) : RenderSeedPart()

    @Serializable
    @kotlinx.serialization.SerialName("tool")
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val input: String,
        val output: List<RenderSeedPart> = emptyList(),
        val approvalState: String = "auto",
    ) : RenderSeedPart()
}
