package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import me.rerere.rikkahub.ui.components.message.RichContentDocument
import me.rerere.rikkahub.ui.components.message.RichContentDocumentTextStore
import me.rerere.rikkahub.ui.components.message.RichContentElementNode
import me.rerere.rikkahub.ui.components.message.RichContentLoweringMode
import me.rerere.rikkahub.ui.components.message.RichContentMark
import me.rerere.rikkahub.ui.components.message.RichContentNodeKind
import me.rerere.rikkahub.ui.components.message.RichContentNodeV2
import me.rerere.rikkahub.ui.components.message.RichContentSourceKind
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.jsoup.nodes.Document

internal object RichTextFlowAstLowerer {
    @Suppress("UNUSED_PARAMETER")
    fun lower(
        document: RichContentDocument,
        model: RichHtmlRenderModel,
        html: String,
        parsedHtml: Document? = null,
    ): RichHtmlRenderModel? {
        if (document.sourceKind !in setOf(RichContentSourceKind.Html, RichContentSourceKind.Markdown)) return null
        if (model.unsupported.isNotEmpty()) return null
        if (document.sourceKind == RichContentSourceKind.Html && STYLE_OR_STYLE_ATTR.containsMatchIn(html)) return null
        val allowSimpleLists = document.sourceKind == RichContentSourceKind.Markdown
        if (document.root.hasAstDirectHardStop(allowSimpleLists = allowSimpleLists)) return null

        val paragraphs = document.root.toAstDirectParagraphs(allowSimpleLists = allowSimpleLists)
            .filter { paragraph -> paragraph.content.text.isNotBlank() }

        if (paragraphs.isEmpty()) return null
        if (model.blocks.sumOf(::countAstDirectModelBlocks) <= 1) return null

        return model.copy(
            blocks = listOf(
                RichTextFlowBlock(
                    blockId = "ast-flow-${renderTextCacheKey(document.documentId).take(12)}",
                    style = ComputedStyle.Initial,
                    paragraphs = paragraphs,
                )
            ),
            textFlowLoweringMode = RichContentLoweringMode.AstDirect,
        )
    }

    fun lowerDocumentOnly(document: RichContentDocument): RichHtmlRenderModel? {
        if (document.sourceKind != RichContentSourceKind.Markdown) return null
        if (document.root.hasAstDirectHardStop(allowSimpleLists = true)) return null
        val paragraphs = document.root.toAstDirectParagraphs(allowSimpleLists = true)
            .filter { paragraph -> paragraph.content.text.isNotBlank() }
        if (paragraphs.isEmpty()) return null
        return RichHtmlRenderModel(
            id = document.documentId,
            blocks = listOf(
                RichTextFlowBlock(
                    blockId = "ast-flow-${renderTextCacheKey(document.documentId).take(12)}",
                    style = ComputedStyle.Initial,
                    paragraphs = paragraphs,
                )
            ),
            textFlowLoweringMode = RichContentLoweringMode.AstDirect,
        )
    }

    private fun RichContentNodeV2.toAstDirectParagraphs(
        allowSimpleLists: Boolean,
    ): List<RichTextFlowParagraph> {
        return when (kind) {
            RichContentNodeKind.Document,
            RichContentNodeKind.BlockContainer -> children.flatMap {
                it.toAstDirectParagraphs(allowSimpleLists = allowSimpleLists)
            }
            RichContentNodeKind.Paragraph -> toAstDirectParagraph()?.let(::listOf).orEmpty()
            RichContentNodeKind.ListBlock -> if (allowSimpleLists) {
                children.mapNotNull { item -> item.toSimpleListItemParagraph() }
            } else {
                emptyList()
            }
            RichContentNodeKind.ListItem -> if (allowSimpleLists) {
                toSimpleListItemParagraph()?.let(::listOf).orEmpty()
            } else {
                emptyList()
            }
            RichContentNodeKind.TextRun,
            RichContentNodeKind.MarkRange -> inlineNodesToParagraph(listOf(this))?.let(::listOf).orEmpty()
            else -> emptyList()
        }
    }

    private fun RichContentNodeV2.toAstDirectParagraph(): RichTextFlowParagraph? =
        inlineNodesToParagraph(children)

    private fun RichContentNodeV2.toSimpleListItemParagraph(): RichTextFlowParagraph? {
        val content = children.toAnnotatedString(prefix = "- ")
        return if (content.text.isBlank()) null else RichTextFlowParagraph(content, ComputedStyle.Initial)
    }

    private fun inlineNodesToParagraph(nodes: List<RichContentNodeV2>): RichTextFlowParagraph? {
        val content = nodes.toAnnotatedString()
        return if (content.text.isBlank()) null else RichTextFlowParagraph(content, ComputedStyle.Initial)
    }

    private fun List<RichContentNodeV2>.toAnnotatedString(prefix: String = ""): AnnotatedString = buildAnnotatedString {
        append(prefix)
        forEach { node -> appendInlineNode(node, emptySet()) }
    }

    private fun AnnotatedString.Builder.appendInlineNode(
        node: RichContentNodeV2,
        inheritedMarks: Set<RichContentMark>,
    ) {
        when (node.kind) {
            RichContentNodeKind.TextRun,
            RichContentNodeKind.MarkRange -> {
                val marks = inheritedMarks + ((node as? RichContentElementNode)?.marks ?: emptySet())
                appendMarkedText(RichContentDocumentTextStore.text(node), marks)
            }
            else -> {
                val marks = inheritedMarks + ((node as? RichContentElementNode)?.marks ?: emptySet())
                node.children.forEach { child -> appendInlineNode(child, marks) }
            }
        }
    }

    private fun AnnotatedString.Builder.appendMarkedText(
        text: String,
        marks: Set<RichContentMark>,
    ) {
        val collapsed = text.collapseHtmlWhitespace()
        if (collapsed.isBlank()) return
        val normalized = if (length == 0) collapsed.trimStart() else collapsed
        val span = marks.toAstDirectSpanStyle()
        if (span == SpanStyle()) {
            append(normalized)
        } else {
            withStyle(span) { append(normalized) }
        }
    }

    private fun RichContentNodeV2.hasAstDirectHardStop(allowSimpleLists: Boolean): Boolean {
        if (capabilities.interactive ||
            capabilities.inlineWebViewRequired ||
            capabilities.snapshotIslandEligible ||
            capabilities.browserOnlyVisual ||
            capabilities.unsafeRuntime ||
            capabilities.unsupportedReasons.isNotEmpty()
        ) {
            return true
        }
        if (kind in AstDirectHardStopKinds) {
            return true
        }
        if (!allowSimpleLists && kind in SimpleListKinds) {
            return true
        }
        return children.any { it.hasAstDirectHardStop(allowSimpleLists = allowSimpleLists) }
    }

    private fun Set<RichContentMark>.toAstDirectSpanStyle(): SpanStyle {
        val marks = this
        var style = SpanStyle()
        if (RichContentMark.Bold in marks) style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
        if (RichContentMark.Italic in marks) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (RichContentMark.Code in marks) style = style.merge(SpanStyle(fontFamily = FontFamily.Monospace))
        val decorations = buildList {
            if (RichContentMark.Underline in marks) add(TextDecoration.Underline)
            if (RichContentMark.Strike in marks) add(TextDecoration.LineThrough)
        }
        if (decorations.isNotEmpty()) {
            style = style.merge(
                SpanStyle(
                    textDecoration = if (decorations.size == 1) {
                        decorations.single()
                    } else {
                        TextDecoration.combine(decorations)
                    },
                )
            )
        }
        return style
    }

    private fun countAstDirectModelBlocks(block: RichBlock): Int = when (block) {
        is RichContainerBlock -> 1 + block.children.sumOf(::countAstDirectModelBlocks)
        is RichButtonBlock -> 1 + block.children.sumOf(::countAstDirectModelBlocks) +
            block.inlineBoxes.sumOf { countAstDirectModelBlocks(it.block) }
        is RichDetailsBlock -> 1 + block.children.sumOf(::countAstDirectModelBlocks)
        is RichTextBlock -> 1 + block.inlineBoxes.sumOf { countAstDirectModelBlocks(it.block) }
        is RichTextFlowBlock,
        is RichSnapshotIslandBlock,
        is RichImageBlock,
        is RichMathBlock,
        is RichSvgBlock,
        is RichTableBlock,
        is RichUnsupportedBlock -> 1
    }

    private fun String.collapseHtmlWhitespace(): String = replace(Regex("""\s+"""), " ")

    private val AstDirectHardStopKinds = setOf(
        RichContentNodeKind.Table,
        RichContentNodeKind.TableRow,
        RichContentNodeKind.TableCell,
        RichContentNodeKind.Media,
        RichContentNodeKind.Svg,
        RichContentNodeKind.Formula,
        RichContentNodeKind.Action,
        RichContentNodeKind.Details,
        RichContentNodeKind.VisualIslandCandidate,
        RichContentNodeKind.Runtime,
        RichContentNodeKind.Unsupported,
    )

    private val SimpleListKinds = setOf(
        RichContentNodeKind.ListBlock,
        RichContentNodeKind.ListItem,
    )

    private val STYLE_OR_STYLE_ATTR = Regex("""(<\s*style\b|\sstyle\s*=)""", RegexOption.IGNORE_CASE)
}
