package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RichContentSubtreeRoutePlan
import me.rerere.rikkahub.ui.components.message.RichContentLoweringMode
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

internal object RichSnapshotIslandOptimizer {
    fun optimize(
        model: RichHtmlRenderModel,
        plan: RichSubtreeRoutePlan = RichSubtreeRoutePlanner.plan(model),
        astPlan: RichContentSubtreeRoutePlan? = null,
    ): RichHtmlRenderModel {
        val baseStats = plan.toStats(appliedCount = 0, wholeSnapshotAvoided = false)
        if (plan.rootRoute != RichSubtreeRoute.NativeWithSnapshotIslands || plan.candidates.isEmpty()) {
            return model.copy(snapshotIslandStats = baseStats)
        }
        if (astPlan != null && !astPlan.allowsSnapshotIslandOptimizationFor(plan.candidates.size)) {
            return model.copy(snapshotIslandStats = baseStats)
        }
        if (model.unsupported.any { it == RichUnsupportedReason.UnsafeHtml || it == RichUnsupportedReason.DynamicRuntime }) {
            return model.copy(snapshotIslandStats = baseStats)
        }
        val candidatesById = plan.candidates.associateBy { it.blockId }
        if (candidatesById.size != plan.candidates.size) return model.copy(snapshotIslandStats = baseStats)
        if (!candidatesById.keys.all { it in model.sourceHtmlByBlockId }) return model.copy(snapshotIslandStats = baseStats)

        var applied = 0
        var invalid = false
        fun replace(block: RichBlock): RichBlock {
            val candidate = candidatesById[block.blockId]
            if (candidate != null) {
                if (block.containsInteractiveActionForIsland()) {
                    invalid = true
                    return block
                }
                val sourceHtml = model.sourceHtmlByBlockId[block.blockId]
                if (sourceHtml.isNullOrBlank()) {
                    invalid = true
                    return block
                }
                val styleBoundary = block.snapshotIslandStyleBoundary(candidate.reason)
                if (styleBoundary == null) {
                    invalid = true
                    return block
                }
                val islandHtml = if (model.sourceStyleHtml.isBlank()) {
                    sourceHtml
                } else {
                    model.sourceStyleHtml + "\n" + sourceHtml
                }
                applied += 1
                return RichSnapshotIslandBlock(
                    blockId = block.blockId,
                    style = block.style.toSnapshotIslandWrapperStyle(styleBoundary),
                    sourceHtml = islandHtml,
                    sourceDigest = renderTextCacheKey(islandHtml),
                    reason = candidate.reason,
                    styleBoundary = styleBoundary,
                    estimatedHeightPx = candidate.estimatedHeightPx,
                    fallbackBlock = block,
                )
            }
            return when (block) {
                is RichContainerBlock -> block.copy(children = block.children.map(::replace))
                is RichButtonBlock -> block.copy(children = block.children.map(::replace))
                is RichDetailsBlock -> block.copy(children = block.children.map(::replace))
                is RichTextBlock -> block.copy(
                    inlineBoxes = block.inlineBoxes.map { box ->
                        box.copy(block = replace(box.block))
                    },
                )
                is RichSnapshotIslandBlock,
                is RichTextFlowBlock,
                is RichImageBlock,
                is RichTableBlock,
                is RichSvgBlock,
                is RichMathBlock,
                is RichUnsupportedBlock -> block
            }
        }

        val nextBlocks = model.blocks.map(::replace)
        if (invalid || applied != plan.candidates.size) {
            return model.copy(snapshotIslandStats = baseStats)
        }
        return model.copy(
            blocks = nextBlocks,
            snapshotIslandStats = plan.toStats(
                appliedCount = applied,
                wholeSnapshotAvoided = plan.estimatedWholeSnapshotAvoided,
            ),
            subtreeRouteLoweringMode = if (astPlan != null) {
                RichContentLoweringMode.AstGatedRenderModel
            } else {
                RichContentLoweringMode.RenderModelOnly
            },
        )
    }

    private fun RichSubtreeRoutePlan.toStats(
        appliedCount: Int,
        wholeSnapshotAvoided: Boolean,
    ): RichSnapshotIslandStats {
        return RichSnapshotIslandStats(
            candidateCount = candidates.size,
            appliedCount = appliedCount,
            rejectedCount = rejected.size,
            wholeSnapshotAvoided = wholeSnapshotAvoided,
            rejectReasons = rejected
                .groupingBy { it.reason.name }
                .eachCount(),
        )
    }

    private fun RichBlock.containsInteractiveActionForIsland(): Boolean = when (this) {
        is RichButtonBlock -> true
        is RichDetailsBlock -> true
        is RichContainerBlock -> style.cursorPointer || children.any { it.containsInteractiveActionForIsland() }
        is RichTextBlock -> inlineBoxes.any { it.block.containsInteractiveActionForIsland() }
        is RichSnapshotIslandBlock -> false
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichSvgBlock,
        is RichMathBlock,
        is RichUnsupportedBlock -> false
    }

    private fun RichBlock.snapshotIslandStyleBoundary(
        reason: RichSnapshotIslandReason,
    ): RichSnapshotIslandStyleBoundary? = when (this) {
        is RichSvgBlock -> RichSnapshotIslandStyleBoundary.NativeWrapper
        is RichUnsupportedBlock -> if (this.reason == RichUnsupportedReason.SvgTooComplex) {
            RichSnapshotIslandStyleBoundary.SnapshotSource
        } else {
            null
        }
        is RichContainerBlock -> if (reason.isCssSourceOwnedReason() && style.hasSafeSourceOwnedIslandBoundary()) {
            RichSnapshotIslandStyleBoundary.NeutralWrapper
        } else {
            null
        }
        is RichTextBlock,
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichMathBlock,
        is RichButtonBlock,
        is RichDetailsBlock,
        is RichSnapshotIslandBlock -> null
    }

    private fun RichSnapshotIslandReason.isCssSourceOwnedReason(): Boolean = when (this) {
        RichSnapshotIslandReason.CssMask,
        RichSnapshotIslandReason.CssClipPath,
        RichSnapshotIslandReason.CssBackdropFilter,
        RichSnapshotIslandReason.CssFilter,
        RichSnapshotIslandReason.MixBlendMode,
        RichSnapshotIslandReason.ComplexBackground -> true
        RichSnapshotIslandReason.ComplexSvg,
        RichSnapshotIslandReason.SvgFilter,
        RichSnapshotIslandReason.SvgMask,
        RichSnapshotIslandReason.SvgClipPath,
        RichSnapshotIslandReason.UnsupportedStaticVisual -> false
    }

    private fun ComputedStyle.hasSafeSourceOwnedIslandBoundary(): Boolean {
        return position == RichPosition.Static &&
            transform == RichTransform.None &&
            opacity == 1f &&
            margin == RichSpacing.Zero
    }

    private fun ComputedStyle.toSnapshotIslandWrapperStyle(
        boundary: RichSnapshotIslandStyleBoundary,
    ): ComputedStyle {
        return when (boundary) {
            RichSnapshotIslandStyleBoundary.NativeWrapper -> layoutOnlySnapshotIslandStyle()
            RichSnapshotIslandStyleBoundary.SnapshotSource,
            RichSnapshotIslandStyleBoundary.NeutralWrapper -> layoutOnlySnapshotIslandStyle().copy(
                padding = RichSpacing.Zero,
                border = RichBorder.None,
                borderRadius = RichCornerRadius.Zero,
                backgroundColor = null,
                declaredBackgroundColor = null,
                backgroundImage = null,
                backgroundUrl = null,
                backgroundLayers = emptyList(),
                extraBackgroundLayers = 0,
                shadows = emptyList(),
                cssFilter = RichCssFilter.None,
                backdropFilter = RichCssFilter.None,
                mixBlendMode = false,
                clipPath = null,
                maskImage = null,
            )
        }
    }

    private fun ComputedStyle.layoutOnlySnapshotIslandStyle(): ComputedStyle {
        return ComputedStyle.Initial.copy(
            display = display,
            width = width,
            height = height,
            minWidth = minWidth,
            maxWidth = maxWidth,
            minHeight = minHeight,
            maxHeight = maxHeight,
            flexGrow = flexGrow,
            flexShrink = flexShrink,
            flexBasis = flexBasis,
            alignSelf = alignSelf,
            order = order,
            gridColumnStart = gridColumnStart,
            gridRowStart = gridRowStart,
            gridColumnSpan = gridColumnSpan,
            gridRowSpan = gridRowSpan,
            overflow = overflow,
        )
    }
}
