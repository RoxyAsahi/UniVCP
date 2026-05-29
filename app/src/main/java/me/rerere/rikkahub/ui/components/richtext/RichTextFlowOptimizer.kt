package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.message.RichContentLoweringMode
import me.rerere.rikkahub.ui.components.message.RichContentTextFlowPlan

internal object RichTextFlowOptimizer {
    fun optimize(
        model: RichHtmlRenderModel,
        astPlan: RichContentTextFlowPlan? = null,
    ): RichHtmlRenderModel {
        if (model.blocks.isEmpty() || model.unsupported.isNotEmpty()) return model
        if (astPlan != null && !astPlan.hasEligibleTextFlow) return model
        val optimized = model.blocks.map(::optimizeBlock)
        return if (optimized == model.blocks) {
            model
        } else {
            model.copy(
                blocks = optimized,
                textFlowLoweringMode = if (astPlan != null) {
                    RichContentLoweringMode.AstGatedRenderModel
                } else {
                    RichContentLoweringMode.RenderModelOnly
                },
            )
        }
    }

    fun inspect(model: RichHtmlRenderModel): RichTextFlowInspection {
        val reasons = linkedSetOf<RichTextFlowBlockedReason>()
        model.blocks.forEach { block -> collectBlockedReasons(block, reasons) }
        return RichTextFlowInspection(
            appliedCount = model.blocks.sumOf(::countTextFlowBlocks),
            blockedReasons = reasons,
            renderNodeReductionEstimate = model.blocks.sumOf(::estimateTextFlowReduction),
            inlineFeaturePreservedCount = model.blocks.sumOf(::countPreservedInlineFeatures),
        )
    }

    private fun optimizeBlock(block: RichBlock): RichBlock {
        return when (block) {
            is RichContainerBlock -> {
                val children = block.children.map(::optimizeBlock)
                val candidate = block.copy(children = children)
                candidate.toTextFlowBlock()
                    ?: block.copy(children = block.groupTextFlowChildren(children))
            }
            is RichTextBlock,
            is RichImageBlock,
            is RichTableBlock,
            is RichSvgBlock,
            is RichMathBlock,
            is RichButtonBlock,
            is RichDetailsBlock,
            is RichSnapshotIslandBlock,
            is RichUnsupportedBlock,
            is RichTextFlowBlock -> block
        }
    }

    private fun RichContainerBlock.groupTextFlowChildren(children: List<RichBlock>): List<RichBlock> {
        if (children.size < 2) return children
        if (style.display.isFlexContainer() || style.display.isGridContainer()) return children
        if (style.display == RichDisplay.None) return children

        val grouped = mutableListOf<RichBlock>()
        val pending = mutableListOf<RichTextFlowParagraph>()
        val pendingOriginals = mutableListOf<RichBlock>()
        var pendingStartBlockId: String? = null

        fun flush() {
            if (pending.isEmpty()) return
            if (pendingOriginals.size == 1) {
                grouped += pendingOriginals.single()
            } else {
                grouped += RichTextFlowBlock(
                    blockId = "${blockId}-flow-${pendingStartBlockId ?: grouped.size}",
                    style = ComputedStyle.Initial.copy(rowGap = style.rowGap),
                    paragraphs = pending.toList(),
                )
            }
            pending.clear()
            pendingOriginals.clear()
            pendingStartBlockId = null
        }

        children.forEach { child ->
            val paragraphs = child.toTextFlowChildGroupParagraphs()
            if (paragraphs.isEmpty()) {
                flush()
                grouped += child
            } else {
                if (pendingStartBlockId == null) pendingStartBlockId = child.blockId
                pending += paragraphs
                pendingOriginals += child
            }
        }
        flush()
        return if (grouped == children) children else grouped
    }

    private fun RichContainerBlock.toTextFlowBlock(): RichTextFlowBlock? {
        if (!style.isTextFlowContainerSafe()) return null
        if (children.isEmpty()) return null
        if (children.any { it.hasTextFlowHardStop() }) return null
        if (!tagName.isTextFlowContainerTag()) return null

        val paragraphs = children.flatMap { it.toTextFlowParagraphs() }
        if (paragraphs.isEmpty()) return null
        if (paragraphs.any { !it.style.isTextFlowParagraphSafe() }) return null
        if (countModelBlocks(this) <= paragraphs.size) return null

        return RichTextFlowBlock(
            blockId = "$blockId-flow",
            style = style,
            paragraphs = paragraphs,
        )
    }

    private fun RichBlock.toTextFlowParagraphs(): List<RichTextFlowParagraph> {
        return when (this) {
            is RichTextBlock -> toTextFlowParagraph()?.let(::listOf).orEmpty()
            is RichTextFlowBlock -> paragraphs
                .takeIf { style.isTextFlowInlineRunSafe() }
                ?.map { it.withOuterTextStyle(style) }
                .orEmpty()
            is RichContainerBlock -> {
                if (!tagName.isTextFlowContainerTag() || !style.isTextFlowParagraphSafe()) return emptyList()
                if (children.isEmpty()) return emptyList()
                val paragraphContent = children.toTextFlowInlineParagraphContent() ?: return emptyList()
                listOf(
                    RichTextFlowParagraph(
                        content = paragraphContent.content,
                        style = style,
                        inlineMath = paragraphContent.inlineMath,
                        inlinePaints = paragraphContent.inlinePaints,
                        listMarker = paragraphContent.listMarker,
                    )
                )
            }
            else -> emptyList()
        }
    }

    private fun RichBlock.toTextFlowChildGroupParagraphs(): List<RichTextFlowParagraph> {
        if (style.order != 0 || style.zIndex != 0f) return emptyList()
        return when (this) {
            is RichTextBlock -> toTextFlowParagraph()?.let(::listOf).orEmpty()
            is RichTextFlowBlock -> paragraphs.takeIf { style.isTextFlowNeutralWrapper() }.orEmpty()
            is RichContainerBlock -> {
                if (!tagName.isTextFlowContainerTag() || !style.isTextFlowNeutralWrapper()) return emptyList()
                toTextFlowParagraphs()
            }
            is RichImageBlock,
            is RichTableBlock,
            is RichSvgBlock,
            is RichMathBlock,
            is RichButtonBlock,
            is RichDetailsBlock,
            is RichSnapshotIslandBlock,
            is RichUnsupportedBlock -> emptyList()
        }
    }

    private fun RichTextBlock.toTextFlowParagraph(): RichTextFlowParagraph? {
        if (content.text.isBlank()) return null
        if (inlineBoxes.isNotEmpty()) return null
        if (inlinePaints.isNotEmpty()) return null
        if (!style.isTextFlowParagraphSafe()) return null
        return RichTextFlowParagraph(
            content = content,
            style = style,
            inlineMath = inlineMath,
            inlinePaints = inlinePaints,
            listMarker = listMarker,
        )
    }

    private fun RichTextFlowParagraph.withOuterTextStyle(style: ComputedStyle): RichTextFlowParagraph {
        if (style.textSpan() == androidx.compose.ui.text.SpanStyle()) return this
        return copy(
            content = buildAnnotatedString {
                withStyle(style.textSpan()) {
                    append(content)
                }
            },
        )
    }

    private fun RichBlock.hasTextFlowHardStop(): Boolean {
        return when (this) {
            is RichButtonBlock,
            is RichDetailsBlock,
            is RichImageBlock,
            is RichMathBlock,
            is RichSvgBlock,
            is RichTableBlock,
            is RichSnapshotIslandBlock,
            is RichUnsupportedBlock -> true
            is RichTextFlowBlock -> false
            is RichTextBlock -> inlineBoxes.isNotEmpty() || inlinePaints.isNotEmpty() || !style.isTextFlowParagraphSafe()
            is RichContainerBlock -> !tagName.isTextFlowContainerTag() ||
                !style.isTextFlowParagraphSafe() ||
                children.any { it.hasTextFlowHardStop() }
        }
    }

    private fun String.isTextFlowContainerTag(): Boolean {
        return this in setOf("div", "p", "span", "section", "article", "main", "ul", "ol", "li")
    }

    private fun ComputedStyle.isTextFlowContainerSafe(): Boolean {
        return isTextFlowBoxSafe() && display != RichDisplay.InlineBlock
    }

    private fun ComputedStyle.isTextFlowParagraphSafe(): Boolean {
        return isTextFlowBoxSafe() && display != RichDisplay.InlineBlock
    }

    private fun ComputedStyle.isTextFlowBoxSafe(): Boolean {
        if (position != RichPosition.Static) return false
        if (display.isFlexContainer() || display.isGridContainer() || display == RichDisplay.None) return false
        if (backgroundColor?.alpha?.let { it > 0.01f } == true) return false
        if (declaredBackgroundColor != null) return false
        if (backgroundImage != null || backgroundUrl != null || backgroundLayers.isNotEmpty()) return false
        if (extraBackgroundLayers > 0) return false
        if (cssFilter != RichCssFilter.None || backdropFilter != RichCssFilter.None) return false
        if (mixBlendMode) return false
        if (clipPath != null || maskImage != null) return false
        if (shadows.isNotEmpty() || textShadow != null) return false
        if (border.hasVisibleBorder()) return false
        if (width != RichSize.Auto || height != RichSize.Auto) return false
        if (minWidth != null || maxWidth != null || minHeight != null || maxHeight != null) return false
        if (offset != RichOffset.Zero || transform != RichTransform.None) return false
        if (overflow != RichOverflow.Visible) return false
        if (animation.isDeclared || transition.isDeclared) return false
        if (cursorPointer) return false
        if (listStyleImage != null) return false
        if (beforeContent != null || afterContent != null) return false
        return true
    }

    private fun ComputedStyle.isTextFlowNeutralWrapper(): Boolean {
        if (!isTextFlowBoxSafe()) return false
        return padding == RichSpacing.Zero &&
            margin == RichSpacing.Zero &&
            rowGap == 0.dp &&
            columnGap == 0.dp &&
            gap == 0.dp
    }

    private fun ComputedStyle.isTextFlowInlineRunSafe(): Boolean {
        return isTextFlowNeutralWrapper() &&
            display != RichDisplay.InlineBlock &&
            lineHeight == TextUnit.Unspecified &&
            lineHeightMultiplier == null &&
            textAlign == TextAlign.Unspecified &&
            whiteSpace == RichWhiteSpace.Normal &&
            wordBreak == RichWordBreak.Normal &&
            textOverflow == RichTextOverflow.Clip
    }

    private fun RichBorder.hasVisibleBorder(): Boolean {
        return listOf(top, right, bottom, left).any { side ->
            side.style != RichBorderStyle.None && side.width > 0.dp && side.color.alpha > 0.01f
        }
    }

    private fun List<RichBlock>.toTextFlowInlineParagraphContent(): RichTextFlowInlineParagraphContent? {
        val inlineMath = mutableListOf<InlineMathRun>()
        val inlinePaints = mutableListOf<InlineTextPaintRun>()
        var listMarker: String? = null
        val content = buildAnnotatedString {
            forEach { block ->
                val marker = block.appendTextFlowInlineContent(
                    builder = this,
                    inlineMath = inlineMath,
                    inlinePaints = inlinePaints,
                ) ?: return null
                if (listMarker == null) listMarker = marker
            }
        }
        if (content.text.isBlank()) return null
        return RichTextFlowInlineParagraphContent(
            content = content,
            inlineMath = inlineMath,
            inlinePaints = inlinePaints,
            listMarker = listMarker,
        )
    }

    private fun RichBlock.appendTextFlowInlineContent(
        builder: AnnotatedString.Builder,
        inlineMath: MutableList<InlineMathRun>,
        inlinePaints: MutableList<InlineTextPaintRun>,
    ): String? {
        if (style.order != 0 || style.zIndex != 0f) return null
        return when (this) {
            is RichTextBlock -> {
                if (content.text.isBlank()) return null
                if (inlineBoxes.isNotEmpty() || this.inlinePaints.isNotEmpty()) return null
                if (!style.isTextFlowInlineRunSafe()) return null
                val offset = builder.length
                builder.withStyle(style.textSpan()) {
                    append(content)
                }
                inlineMath += this.inlineMath.map { run ->
                    run.copy(start = run.start + offset, end = run.end + offset)
                }
                this.listMarker
            }
            is RichContainerBlock -> {
                if (!tagName.isTextFlowContainerTag() || !style.isTextFlowInlineRunSafe()) return null
                if (children.isEmpty() || children.any { it.hasTextFlowHardStop() }) return null
                var marker: String? = null
                builder.withStyle(style.textSpan()) {
                    children.forEach { child ->
                        val childMarker = child.appendTextFlowInlineContent(
                            builder = builder,
                            inlineMath = inlineMath,
                            inlinePaints = inlinePaints,
                        ) ?: return null
                        if (marker == null) marker = childMarker
                    }
                }
                marker
            }
            is RichTextFlowBlock,
            is RichImageBlock,
            is RichTableBlock,
            is RichSvgBlock,
            is RichMathBlock,
            is RichButtonBlock,
            is RichDetailsBlock,
            is RichSnapshotIslandBlock,
            is RichUnsupportedBlock -> null
        }
    }

    private fun countModelBlocks(block: RichBlock): Int {
        return when (block) {
            is RichContainerBlock -> 1 + block.children.sumOf(::countModelBlocks)
            is RichButtonBlock -> 1 + block.children.sumOf(::countModelBlocks) +
                block.inlineBoxes.sumOf { countModelBlocks(it.block) }
            is RichDetailsBlock -> 1 + block.children.sumOf(::countModelBlocks)
            is RichTextBlock -> 1 + block.inlineBoxes.sumOf { countModelBlocks(it.block) }
            is RichTextFlowBlock -> 1
            is RichSnapshotIslandBlock -> 1
            is RichImageBlock,
            is RichMathBlock,
            is RichSvgBlock,
            is RichTableBlock,
            is RichUnsupportedBlock -> 1
        }
    }

    private fun collectBlockedReasons(
        block: RichBlock,
        reasons: MutableSet<RichTextFlowBlockedReason>,
    ) {
        when (block) {
            is RichTextFlowBlock -> Unit
            is RichTextBlock -> reasons += block.textFlowBlockedReasons()
            is RichContainerBlock -> {
                if (block.isTextFlowLikeContainer()) {
                    reasons += block.style.textFlowBlockedReasons()
                    if (!block.tagName.isTextFlowContainerTag()) reasons += RichTextFlowBlockedReason.UnknownContainer
                }
                block.children.forEach { collectBlockedReasons(it, reasons) }
            }
            is RichButtonBlock -> reasons += RichTextFlowBlockedReason.Action
            is RichDetailsBlock -> {
                reasons += RichTextFlowBlockedReason.Details
                block.children.forEach { collectBlockedReasons(it, reasons) }
            }
            is RichImageBlock -> reasons += RichTextFlowBlockedReason.Media
            is RichMathBlock -> reasons += RichTextFlowBlockedReason.Media
            is RichSvgBlock -> reasons += RichTextFlowBlockedReason.Svg
            is RichSnapshotIslandBlock -> reasons += RichTextFlowBlockedReason.SnapshotIsland
            is RichTableBlock -> reasons += RichTextFlowBlockedReason.Table
            is RichUnsupportedBlock -> reasons += RichTextFlowBlockedReason.Unsupported
        }
    }

    private fun countTextFlowBlocks(block: RichBlock): Int {
        return when (block) {
            is RichTextFlowBlock -> 1
            is RichSnapshotIslandBlock -> 0
            is RichContainerBlock -> block.children.sumOf(::countTextFlowBlocks)
            is RichButtonBlock -> block.children.sumOf(::countTextFlowBlocks) +
                block.inlineBoxes.sumOf { countTextFlowBlocks(it.block) }
            is RichDetailsBlock -> block.children.sumOf(::countTextFlowBlocks)
            is RichTextBlock -> block.inlineBoxes.sumOf { countTextFlowBlocks(it.block) }
            is RichImageBlock,
            is RichMathBlock,
            is RichSvgBlock,
            is RichTableBlock,
            is RichUnsupportedBlock -> 0
        }
    }

    private fun estimateTextFlowReduction(block: RichBlock): Int {
        return when (block) {
            is RichTextFlowBlock -> block.paragraphs.size.coerceAtLeast(1)
            is RichSnapshotIslandBlock -> 0
            is RichContainerBlock -> block.children.sumOf(::estimateTextFlowReduction)
            is RichButtonBlock -> block.children.sumOf(::estimateTextFlowReduction) +
                block.inlineBoxes.sumOf { estimateTextFlowReduction(it.block) }
            is RichDetailsBlock -> block.children.sumOf(::estimateTextFlowReduction)
            is RichTextBlock -> block.inlineBoxes.sumOf { estimateTextFlowReduction(it.block) }
            is RichImageBlock,
            is RichMathBlock,
            is RichSvgBlock,
            is RichTableBlock,
            is RichUnsupportedBlock -> 0
        }
    }

    private fun countPreservedInlineFeatures(block: RichBlock): Int {
        return when (block) {
            is RichTextFlowBlock -> block.paragraphs.sumOf { paragraph ->
                paragraph.content.spanStyles.size +
                    paragraph.inlineMath.size +
                    paragraph.inlinePaints.size +
                    if (paragraph.listMarker != null) 1 else 0
            }
            is RichContainerBlock -> block.children.sumOf(::countPreservedInlineFeatures)
            is RichButtonBlock -> block.children.sumOf(::countPreservedInlineFeatures) +
                block.inlineBoxes.sumOf { countPreservedInlineFeatures(it.block) }
            is RichDetailsBlock -> block.children.sumOf(::countPreservedInlineFeatures)
            is RichTextBlock -> block.inlineBoxes.sumOf { countPreservedInlineFeatures(it.block) }
            is RichSnapshotIslandBlock,
            is RichImageBlock,
            is RichMathBlock,
            is RichSvgBlock,
            is RichTableBlock,
            is RichUnsupportedBlock -> 0
        }
    }

    private fun RichTextBlock.textFlowBlockedReasons(): Set<RichTextFlowBlockedReason> = buildSet {
        if (inlineBoxes.isNotEmpty()) add(RichTextFlowBlockedReason.InlineBox)
        if (inlinePaints.isNotEmpty()) add(RichTextFlowBlockedReason.InlinePaint)
        addAll(style.textFlowBlockedReasons())
    }

    private fun RichContainerBlock.isTextFlowLikeContainer(): Boolean {
        return tagName.isTextFlowContainerTag() || children.any {
            it is RichTextBlock || it is RichTextFlowBlock || it is RichContainerBlock && it.isTextFlowLikeContainer()
        }
    }

    private fun ComputedStyle.textFlowBlockedReasons(): Set<RichTextFlowBlockedReason> = buildSet {
        if (position != RichPosition.Static || offset != RichOffset.Zero || transform != RichTransform.None) {
            add(RichTextFlowBlockedReason.Positioning)
        }
        if (display.isFlexContainer() || display.isGridContainer() || display == RichDisplay.None) {
            add(RichTextFlowBlockedReason.FlexGrid)
        }
        if (backgroundImage != null || backgroundUrl != null || backgroundLayers.isNotEmpty() || extraBackgroundLayers > 0) {
            add(RichTextFlowBlockedReason.BackgroundImage)
        }
        if (cssFilter != RichCssFilter.None || backdropFilter != RichCssFilter.None ||
            mixBlendMode || clipPath != null || maskImage != null
        ) {
            add(RichTextFlowBlockedReason.VisualEffect)
        }
        if (backgroundColor?.alpha?.let { it > 0.01f } == true ||
            declaredBackgroundColor != null ||
            shadows.isNotEmpty() ||
            textShadow != null ||
            border.hasVisibleBorder()
        ) {
            add(RichTextFlowBlockedReason.BorderBackgroundShadow)
        }
        if (width != RichSize.Auto || height != RichSize.Auto || minWidth != null || maxWidth != null ||
            minHeight != null || maxHeight != null
        ) {
            add(RichTextFlowBlockedReason.ExplicitSize)
        }
        if (listStyleImage != null) add(RichTextFlowBlockedReason.ListImageMarker)
        if (beforeContent != null || afterContent != null) add(RichTextFlowBlockedReason.GeneratedContent)
        if (animation.isDeclared || transition.isDeclared || cursorPointer) {
            add(RichTextFlowBlockedReason.InteractiveOrAnimated)
        }
    }
}

internal data class RichTextFlowInspection(
    val appliedCount: Int,
    val blockedReasons: Set<RichTextFlowBlockedReason>,
    val renderNodeReductionEstimate: Int,
    val inlineFeaturePreservedCount: Int,
)

private data class RichTextFlowInlineParagraphContent(
    val content: AnnotatedString,
    val inlineMath: List<InlineMathRun>,
    val inlinePaints: List<InlineTextPaintRun>,
    val listMarker: String?,
)

internal enum class RichTextFlowBlockedReason {
    Action,
    Table,
    Svg,
    Media,
    Details,
    Unsupported,
    SnapshotIsland,
    Positioning,
    FlexGrid,
    BackgroundImage,
    VisualEffect,
    BorderBackgroundShadow,
    ExplicitSize,
    InlineBox,
    InlinePaint,
    ListImageMarker,
    GeneratedContent,
    InteractiveOrAnimated,
    UnknownContainer,
}
