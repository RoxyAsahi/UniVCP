package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import kotlin.uuid.Uuid

internal sealed interface ChatRenderCell {
    val stableKey: String
    val contentType: ChatRenderCellContentType
    val messageId: Uuid?
    val nodeId: Uuid?
    val blockIndex: Int
    val estimatedHeightClass: EstimatedHeightClass
    val renderRisk: RenderRiskScore

    data class AvatarCell(
        val meta: ChatRenderMessageMeta,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:avatar"
        override val contentType = ChatRenderCellContentType.AvatarCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = -1
        override val estimatedHeightClass = EstimatedHeightClass.Small
        override val renderRisk = RenderRiskScore.Low
    }

    data class UserBubbleCell(
        val meta: ChatRenderMessageMeta,
        val partIndex: Int,
        val text: String,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:part:$partIndex:user:${renderTextCacheKey(text)}"
        override val contentType = ChatRenderCellContentType.UserBubbleCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = partIndex
        override val estimatedHeightClass = heightClassForText(text)
        override val renderRisk = RenderRiskScore.Low
    }

    data class MarkdownCell(
        val meta: ChatRenderMessageMeta,
        val partIndex: Int,
        val textBlockIndex: Int,
        val text: String,
        val bubblePosition: AssistantBubblePosition,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:part:$partIndex:${stableMessageTextBlockKey(
            meta.message.id.toString(),
            textBlockIndex,
            MessageTextBlock.Markdown(text),
        )}"
        override val contentType = ChatRenderCellContentType.MarkdownCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = textBlockIndex
        override val estimatedHeightClass = heightClassForText(text)
        override val renderRisk = RenderRiskScore.Low
    }

    data class RichHtmlCell(
        val meta: ChatRenderMessageMeta,
        val partIndex: Int,
        val textBlockIndex: Int,
        val block: MessageTextBlock.VcpHtml,
        val analysis: RichHtmlAnalysis,
        val bubblePosition: AssistantBubblePosition,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:part:$partIndex:${stableMessageTextBlockKey(
            meta.message.id.toString(),
            textBlockIndex,
            block,
        )}"
        override val contentType = ChatRenderCellContentType.RichHtmlCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = textBlockIndex
        override val renderRisk = RenderRiskScore.fromHtml(block.html, analysis)
        override val estimatedHeightClass = heightClassForRichHtml(analysis, renderRisk)
    }

    data class ProtocolCell(
        val meta: ChatRenderMessageMeta,
        val partIndex: Int,
        val textBlockIndex: Int,
        val block: MessageTextBlock.Protocol,
        val bubblePosition: AssistantBubblePosition,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:part:$partIndex:${stableMessageTextBlockKey(
            meta.message.id.toString(),
            textBlockIndex,
            block,
        )}"
        override val contentType = ChatRenderCellContentType.ProtocolCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = textBlockIndex
        override val estimatedHeightClass = EstimatedHeightClass.Medium
        override val renderRisk = RenderRiskScore.Low
    }

    data class ThinkingCell(
        val meta: ChatRenderMessageMeta,
        val groupIndex: Int,
        val steps: List<ThinkingStep>,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:thinking:$groupIndex:${renderTextCacheKey(
            steps.joinToString("|") { step ->
                when (step) {
                    is ThinkingStep.ReasoningStep -> step.reasoning.createdAt.toString()
                    is ThinkingStep.ToolStep -> step.tool.toolCallId.ifBlank { step.tool.toolName }
                }
            },
        )}"
        override val contentType = ChatRenderCellContentType.ThinkingCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = groupIndex
        override val estimatedHeightClass = EstimatedHeightClass.Medium
        override val renderRisk = if (steps.size > 4) RenderRiskScore.Medium else RenderRiskScore.Low
    }

    data class ToolCell(
        val meta: ChatRenderMessageMeta,
        val partIndex: Int,
        val tool: UIMessagePart.Tool,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:tool:$partIndex:${tool.toolCallId.ifBlank {
            renderTextCacheKey(tool.toolName + tool.input)
        }}"
        override val contentType = ChatRenderCellContentType.ToolCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = partIndex
        override val estimatedHeightClass = EstimatedHeightClass.Medium
        override val renderRisk = RenderRiskScore.Low
    }

    data class AttachmentCell(
        val meta: ChatRenderMessageMeta,
        val partIndex: Int,
        val part: UIMessagePart,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:attachment:$partIndex:${renderTextCacheKey(part.toString())}"
        override val contentType = ChatRenderCellContentType.AttachmentCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = partIndex
        override val estimatedHeightClass = EstimatedHeightClass.Small
        override val renderRisk = RenderRiskScore.Low
    }

    data class TranslationCell(
        val meta: ChatRenderMessageMeta,
        val text: String,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:translation:${renderTextCacheKey(text)}"
        override val contentType = ChatRenderCellContentType.TranslationCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = Int.MAX_VALUE - 3
        override val estimatedHeightClass = heightClassForText(text)
        override val renderRisk = RenderRiskScore.Low
    }

    data class ActionsCell(
        val meta: ChatRenderMessageMeta,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:actions:${meta.message.id}"
        override val contentType = ChatRenderCellContentType.ActionsCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = Int.MAX_VALUE - 2
        override val estimatedHeightClass = EstimatedHeightClass.Small
        override val renderRisk = RenderRiskScore.Low
    }

    data class AnnotationCell(
        val meta: ChatRenderMessageMeta,
        val annotations: List<UIMessageAnnotation>,
    ) : ChatRenderCell {
        override val stableKey = "message:${meta.node.id}:annotations:${annotations.size}:${meta.message.id}"
        override val contentType = ChatRenderCellContentType.AnnotationCell
        override val messageId = meta.message.id
        override val nodeId = meta.node.id
        override val blockIndex = Int.MAX_VALUE - 1
        override val estimatedHeightClass = EstimatedHeightClass.Small
        override val renderRisk = RenderRiskScore.Low
    }

    data object BottomSpacerCell : ChatRenderCell {
        override val stableKey = "bottom-spacer"
        override val contentType = ChatRenderCellContentType.BottomSpacerCell
        override val messageId: Uuid? = null
        override val nodeId: Uuid? = null
        override val blockIndex = Int.MAX_VALUE
        override val estimatedHeightClass = EstimatedHeightClass.Small
        override val renderRisk = RenderRiskScore.Low
    }
}

internal data class ChatRenderMessageMeta(
    val node: MessageNode,
    val message: UIMessage,
    val messageIndex: Int,
    val lastMessage: Boolean,
    val loading: Boolean,
    val model: Model?,
    val assistant: Assistant?,
)

internal fun ChatRenderCell.messageMetaOrNull(): ChatRenderMessageMeta? = when (this) {
    is ChatRenderCell.AvatarCell -> meta
    is ChatRenderCell.UserBubbleCell -> meta
    is ChatRenderCell.MarkdownCell -> meta
    is ChatRenderCell.RichHtmlCell -> meta
    is ChatRenderCell.ProtocolCell -> meta
    is ChatRenderCell.ThinkingCell -> meta
    is ChatRenderCell.ToolCell -> meta
    is ChatRenderCell.AttachmentCell -> meta
    is ChatRenderCell.TranslationCell -> meta
    is ChatRenderCell.ActionsCell -> meta
    is ChatRenderCell.AnnotationCell -> meta
    ChatRenderCell.BottomSpacerCell -> null
}

internal fun List<ChatRenderCell>.firstCellIndexForMessageIndex(messageIndex: Int): Int {
    return indexOfFirst { it.messageMetaOrNull()?.messageIndex == messageIndex }
        .takeIf { it >= 0 }
        ?: indices.lastOrNull()
        ?: 0
}

internal fun List<ChatRenderCell>.firstCellIndexForNodeId(nodeId: Uuid): Int {
    return indexOfFirst { it.nodeId == nodeId }
        .takeIf { it >= 0 }
        ?: indices.lastOrNull()
        ?: 0
}

internal enum class ChatRenderCellContentType {
    AvatarCell,
    UserBubbleCell,
    MarkdownCell,
    RichHtmlCell,
    ProtocolCell,
    ThinkingCell,
    ToolCell,
    AttachmentCell,
    TranslationCell,
    ActionsCell,
    AnnotationCell,
    BottomSpacerCell,
}

internal enum class EstimatedHeightClass {
    Small,
    Medium,
    Large,
    ExtraLarge,
}

internal enum class AssistantBubblePosition {
    None,
    Single,
    Top,
    Middle,
    Bottom,
}

internal enum class RichContentRoute {
    NativeNow,
    NativeDeferred,
    Snapshot,
    Lightweight,
    DynamicPreview,
}

internal data class RenderRiskScore(
    val score: Int,
    val route: RichContentRoute,
    val reasons: Set<String> = emptySet(),
) {
    companion object {
        val Low = RenderRiskScore(score = 0, route = RichContentRoute.NativeNow)
        val Medium = RenderRiskScore(score = 35, route = RichContentRoute.NativeDeferred, reasons = setOf("medium"))

        fun fromHtml(
            html: String,
            analysis: RichHtmlAnalysis = analyzeRichHtml(html),
            historicalFailure: Boolean = false,
        ): RenderRiskScore {
            val nodeCount = HTML_TAG.findAll(html).count()
            val tableCellCount = TABLE_CELL_TAG.findAll(html).count()
            val svgPathChars = SVG_PATH_D_ATTR.findAll(html).sumOf { it.groupValues.getOrNull(2)?.length ?: 0 }
            val svgCommandCount = SVG_COMMAND_TAG.findAll(html).count()
            val animationCount = ANIMATION_HINT.findAll(html).count()
            val visualHintCount = VISUAL_HINT.findAll(html).count()
            val estimatedHeight = analysis.previewText.length * 2 + nodeCount * 4 + tableCellCount * 6

            var score = 0
            val reasons = linkedSetOf<String>()
            fun add(points: Int, reason: String) {
                if (points <= 0) return
                score += points
                reasons += reason
            }

            add((html.length / 1_000).coerceAtMost(25), "htmlLength")
            add((nodeCount / 40).coerceAtMost(20), "domNodes")
            add((tableCellCount / 12).coerceAtMost(20), "tableCells")
            add((svgPathChars / 1_500).coerceAtMost(25), "svgPathChars")
            add((svgCommandCount / 24).coerceAtMost(20), "svgCommands")
            add((animationCount * 12).coerceAtMost(24), "animations")
            add((visualHintCount * 5).coerceAtMost(15), "visualHints")
            add((estimatedHeight / 600).coerceAtMost(12), "estimatedHeight")
            if (analysis.nativeConfidence == NativeConfidence.WebViewFallback) add(35, "nativeConfidence")
            if (analysis.kind == RichHtmlRenderKind.InteractiveStatic) add(12, "interactiveStatic")
            if (analysis.kind == RichHtmlRenderKind.ComplexDynamic) add(100, "complexDynamic")
            if (historicalFailure) add(100, "historicalFailure")

            val runtimeDynamic = RUNTIME_DYNAMIC_HINT.containsMatchIn(html)
            val route = when {
                analysis.kind == RichHtmlRenderKind.ComplexDynamic && !runtimeDynamic &&
                    (svgPathChars > 8_000 || svgCommandCount > 128 || tableCellCount > 160) -> {
                    RichContentRoute.Snapshot
                }
                analysis.kind == RichHtmlRenderKind.ComplexDynamic -> RichContentRoute.DynamicPreview
                historicalFailure -> RichContentRoute.Snapshot
                score >= 80 -> RichContentRoute.Snapshot
                score >= 35 -> RichContentRoute.NativeDeferred
                else -> RichContentRoute.NativeNow
            }
            return RenderRiskScore(
                score = score.coerceAtMost(100),
                route = route,
                reasons = reasons,
            )
        }
    }
}

internal fun buildChatRenderCells(
    conversation: Conversation,
    settings: Settings,
    loading: Boolean,
): List<ChatRenderCell> {
    val assistant = settings.getAssistantById(conversation.assistantId)
    val cells = mutableListOf<ChatRenderCell>()
    conversation.messageNodes.fastForEachIndexed { messageIndex, node ->
        val message = node.currentMessage
        val meta = ChatRenderMessageMeta(
            node = node,
            message = message,
            messageIndex = messageIndex,
            lastMessage = messageIndex == conversation.messageNodes.lastIndex,
            loading = loading && messageIndex == conversation.messageNodes.lastIndex,
            model = message.modelId?.let { settings.findModelById(it) },
            assistant = assistant,
        )
        cells += buildMessageCells(meta)
    }
    cells += ChatRenderCell.BottomSpacerCell
    return cells
}

private fun buildMessageCells(meta: ChatRenderMessageMeta): List<ChatRenderCell> {
    val message = meta.message
    val cells = mutableListOf<ChatRenderCell>()
    if (!message.parts.isEmptyUIMessage()) {
        cells += ChatRenderCell.AvatarCell(meta)
    }

    var thinkingGroupIndex = 0
    message.parts.groupMessageParts().forEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    cells += ChatRenderCell.ThinkingCell(
                        meta = meta,
                        groupIndex = thinkingGroupIndex++,
                        steps = block.steps,
                    )
                }
            }

            is MessagePartBlock.ContentBlock -> {
                cells += buildContentCells(
                    meta = meta,
                    part = block.part,
                    partIndex = block.index,
                )
            }
        }
    }

    message.translation?.takeIf { it.isNotBlank() }?.let {
        cells += ChatRenderCell.TranslationCell(meta = meta, text = it)
    }
    if (message.annotations.isNotEmpty()) {
        cells += ChatRenderCell.AnnotationCell(meta = meta, annotations = message.annotations)
    }
    val showActions = if (meta.lastMessage) !meta.loading else !message.parts.isEmptyUIMessage()
    if (showActions) {
        cells += ChatRenderCell.ActionsCell(meta)
    }
    return applyAssistantBubbleGrouping(cells)
}

private fun buildContentCells(
    meta: ChatRenderMessageMeta,
    part: UIMessagePart,
    partIndex: Int,
): List<ChatRenderCell> {
    return when (part) {
        is UIMessagePart.Text -> buildTextCells(meta, part, partIndex)
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        is UIMessagePart.Document
            -> listOf(ChatRenderCell.AttachmentCell(meta = meta, partIndex = partIndex, part = part))
        else -> emptyList()
    }
}

private fun buildTextCells(
    meta: ChatRenderMessageMeta,
    part: UIMessagePart.Text,
    partIndex: Int,
): List<ChatRenderCell> {
    if (meta.message.role == MessageRole.USER) {
        val text = part.text.replaceRegexes(
            assistant = meta.assistant,
            scope = AssistantAffectScope.USER,
            visual = true,
        )
        return listOf(ChatRenderCell.UserBubbleCell(meta = meta, partIndex = partIndex, text = text))
    }

    val assistantContent = part.text.replaceRegexes(
        assistant = meta.assistant,
        scope = AssistantAffectScope.ASSISTANT,
        visual = true,
    )
    val textBlocks = parseMessageTextBlocks(assistantContent, streaming = meta.loading)
    return textBlocks.mapIndexed { textBlockIndex, block ->
        when (block) {
            is MessageTextBlock.Markdown -> ChatRenderCell.MarkdownCell(
                meta = meta,
                partIndex = partIndex,
                textBlockIndex = textBlockIndex,
                text = block.text,
                bubblePosition = AssistantBubblePosition.None,
            )

            is MessageTextBlock.VcpHtml -> ChatRenderCell.RichHtmlCell(
                meta = meta,
                partIndex = partIndex,
                textBlockIndex = textBlockIndex,
                block = block,
                analysis = analyzeRichHtml(block.html),
                bubblePosition = AssistantBubblePosition.None,
            )

            is MessageTextBlock.Protocol -> ChatRenderCell.ProtocolCell(
                meta = meta,
                partIndex = partIndex,
                textBlockIndex = textBlockIndex,
                block = block,
                bubblePosition = AssistantBubblePosition.None,
            )
        }
    }
}

private fun applyAssistantBubbleGrouping(cells: List<ChatRenderCell>): List<ChatRenderCell> {
    val bubbleIndices = cells.indices.filter { index ->
        when (val cell = cells[index]) {
            is ChatRenderCell.MarkdownCell -> cell.meta.message.role == MessageRole.ASSISTANT
            is ChatRenderCell.RichHtmlCell -> cell.meta.message.role == MessageRole.ASSISTANT
            is ChatRenderCell.ProtocolCell -> cell.meta.message.role == MessageRole.ASSISTANT
            else -> false
        }
    }
    if (bubbleIndices.isEmpty()) return cells

    val positions = mutableMapOf<Int, AssistantBubblePosition>()
    var run = mutableListOf<Int>()

    fun flushRun() {
        if (run.isEmpty()) return
        when (run.size) {
            1 -> positions[run.first()] = AssistantBubblePosition.Single
            else -> run.forEachIndexed { index, cellIndex ->
                positions[cellIndex] = when (index) {
                    0 -> AssistantBubblePosition.Top
                    run.lastIndex -> AssistantBubblePosition.Bottom
                    else -> AssistantBubblePosition.Middle
                }
            }
        }
        run = mutableListOf()
    }

    bubbleIndices.forEach { index ->
        if (run.isEmpty() || index == run.last() + 1) {
            run += index
        } else {
            flushRun()
            run += index
        }
    }
    flushRun()

    return cells.mapIndexed { index, cell ->
        when (cell) {
            is ChatRenderCell.MarkdownCell -> cell.copy(
                bubblePosition = positions[index] ?: AssistantBubblePosition.None,
            )

            is ChatRenderCell.RichHtmlCell -> cell.copy(
                bubblePosition = positions[index] ?: AssistantBubblePosition.None,
            )

            is ChatRenderCell.ProtocolCell -> cell.copy(
                bubblePosition = positions[index] ?: AssistantBubblePosition.None,
            )

            else -> cell
        }
    }
}

private fun heightClassForText(text: String): EstimatedHeightClass {
    return when {
        text.length > 5_600 -> EstimatedHeightClass.ExtraLarge
        text.length > 2_400 -> EstimatedHeightClass.Large
        text.length > 560 -> EstimatedHeightClass.Medium
        else -> EstimatedHeightClass.Small
    }
}

private fun heightClassForRichHtml(
    analysis: RichHtmlAnalysis,
    risk: RenderRiskScore,
): EstimatedHeightClass {
    return when {
        risk.score >= 80 -> EstimatedHeightClass.ExtraLarge
        risk.score >= 45 || analysis.htmlLength > 3_200 -> EstimatedHeightClass.Large
        analysis.htmlLength > 800 -> EstimatedHeightClass.Medium
        else -> EstimatedHeightClass.Small
    }
}

private val HTML_TAG = Regex("""<[^>]+>""")
private val TABLE_CELL_TAG = Regex("""<\s*(td|th)\b""", RegexOption.IGNORE_CASE)
private val SVG_COMMAND_TAG = Regex("""<\s*(path|rect|circle|ellipse|line|polyline|polygon|text)\b""", RegexOption.IGNORE_CASE)
private val SVG_PATH_D_ATTR = Regex("""<\s*path\b[^>]*\sd\s*=\s*(['"])([\s\S]*?)\1""", RegexOption.IGNORE_CASE)
private val ANIMATION_HINT = Regex("""(@keyframes|\banimation\s*:|\btransition\s*:|requestAnimationFrame)""", RegexOption.IGNORE_CASE)
private val VISUAL_HINT = Regex("""(linear-gradient|radial-gradient|box-shadow|filter\s*:|clip-path|mask\s*:|mix-blend-mode)""", RegexOption.IGNORE_CASE)
private val RUNTIME_DYNAMIC_HINT = Regex(
    """(<\s*(script|canvas|video|audio|iframe|object|embed)\b|\brequestAnimationFrame\s*\(|\bsetInterval\s*\(|\bTHREE\s*\.|\bWebGLRenderer\b|\bmermaid\s*\.)""",
    RegexOption.IGNORE_CASE,
)
