package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RichContentSubtreeRoutePlan

internal data class RichSubtreeRoutePlan(
    val rootRoute: RichSubtreeRoute,
    val candidates: List<RichSnapshotIslandCandidate>,
    val rejected: List<RichSnapshotIslandRejected>,
    val nativePreservedActionCount: Int,
    val estimatedWholeSnapshotAvoided: Boolean,
)

internal enum class RichSubtreeRoute {
    Native,
    NativeWithSnapshotIslands,
    WholeSnapshot,
    DynamicPreview,
    InlineWebView,
}

internal data class RichSnapshotIslandCandidate(
    val blockId: String,
    val stablePath: String,
    val reason: RichSnapshotIslandReason,
    val estimatedWidthPx: Int?,
    val estimatedHeightPx: Int?,
    val visualOnly: Boolean,
)

internal enum class RichSnapshotIslandReason {
    ComplexSvg,
    SvgFilter,
    SvgMask,
    SvgClipPath,
    CssMask,
    CssClipPath,
    CssBackdropFilter,
    CssFilter,
    MixBlendMode,
    ComplexBackground,
    UnsupportedStaticVisual,
}

internal data class RichSnapshotIslandRejected(
    val blockId: String,
    val stablePath: String,
    val reason: RichSnapshotIslandRejectReason,
)

internal enum class RichSnapshotIslandRejectReason {
    InteractiveSubtree,
    ContainsAction,
    ContainsFormControl,
    ContainsScriptRuntime,
    ContainsCanvasRuntime,
    UnknownSize,
    TooLarge,
    TooSmall,
    TooManyIslands,
    ParentLayoutTooDependent,
    SafetyRejected,
}

internal object RichSubtreeRoutePlanner {
    private const val DefaultMaxIslands = 2
    private const val MinUsefulSizePx = 48
    private const val MaxIslandHeightPx = 1_800

    private val wholeSnapshotHints = setOf(
        RichVisualHint.CssMixBlendMode,
        RichVisualHint.SvgClipPath,
        RichVisualHint.SvgMask,
        RichVisualHint.SvgFilter,
        RichVisualHint.SvgUse,
        RichVisualHint.SvgSymbol,
        RichVisualHint.SvgPattern,
        RichVisualHint.SvgForeignObject,
        RichVisualHint.AnimationBudgetExceeded,
        RichVisualHint.AnimationDependentVisibility,
        RichVisualHint.CssLayoutAnimation,
    )

    fun plan(
        model: RichHtmlRenderModel,
        maxIslands: Int = DefaultMaxIslands,
        minUsefulSizePx: Int = MinUsefulSizePx,
        maxIslandHeightPx: Int = MaxIslandHeightPx,
    ): RichSubtreeRoutePlan {
        val candidates = mutableListOf<RichSnapshotIslandCandidate>()
        val rejected = mutableListOf<RichSnapshotIslandRejected>()
        model.blocks.forEachIndexed { index, block ->
            visit(
                block = block,
                stablePath = "r$index",
                insideInteractive = false,
                candidates = candidates,
                rejected = rejected,
                minUsefulSizePx = minUsefulSizePx,
                maxIslandHeightPx = maxIslandHeightPx,
            )
        }

        val budget = maxIslands.coerceAtLeast(0)
        val overBudget = candidates.size > budget
        if (overBudget) {
            candidates.forEach { candidate ->
                rejected += RichSnapshotIslandRejected(
                    blockId = candidate.blockId,
                    stablePath = candidate.stablePath,
                    reason = RichSnapshotIslandRejectReason.TooManyIslands,
                )
            }
        }
        val dynamic = model.unsupported.any {
            it == RichUnsupportedReason.UnsafeHtml || it == RichUnsupportedReason.DynamicRuntime
        }
        val rootRoute = when {
            dynamic -> RichSubtreeRoute.DynamicPreview
            overBudget -> RichSubtreeRoute.WholeSnapshot
            candidates.isNotEmpty() -> RichSubtreeRoute.NativeWithSnapshotIslands
            shouldWholeSnapshot(model) -> RichSubtreeRoute.WholeSnapshot
            else -> RichSubtreeRoute.Native
        }
        return RichSubtreeRoutePlan(
            rootRoute = rootRoute,
            candidates = candidates.toList(),
            rejected = rejected.toList(),
            nativePreservedActionCount = model.blocks.sumOf(::countActions),
            estimatedWholeSnapshotAvoided = rootRoute == RichSubtreeRoute.NativeWithSnapshotIslands &&
                shouldWholeSnapshot(model),
        )
    }

    fun skippedByAst(
        model: RichHtmlRenderModel,
        astPlan: RichContentSubtreeRoutePlan?,
    ): RichSubtreeRoutePlan {
        val dynamic = model.unsupported.any {
            it == RichUnsupportedReason.UnsafeHtml || it == RichUnsupportedReason.DynamicRuntime
        } || (astPlan?.inlineWebViewRequiredCount ?: 0) > 0
        return RichSubtreeRoutePlan(
            rootRoute = when {
                dynamic -> RichSubtreeRoute.DynamicPreview
                astPlan?.wholeSnapshotLikely == true -> RichSubtreeRoute.WholeSnapshot
                shouldWholeSnapshot(model) -> RichSubtreeRoute.WholeSnapshot
                else -> RichSubtreeRoute.Native
            },
            candidates = emptyList(),
            rejected = astPlan
                ?.rejectReasons
                ?.sorted()
                ?.mapIndexed { index, reason ->
                    RichSnapshotIslandRejected(
                        blockId = "ast$index",
                        stablePath = "ast$index",
                        reason = reason.toSnapshotIslandRejectReason(),
                    )
                }
                .orEmpty(),
            nativePreservedActionCount = astPlan?.nativePreservedActionCount ?: model.blocks.sumOf(::countActions),
            estimatedWholeSnapshotAvoided = false,
        )
    }

    private fun visit(
        block: RichBlock,
        stablePath: String,
        insideInteractive: Boolean,
        candidates: MutableList<RichSnapshotIslandCandidate>,
        rejected: MutableList<RichSnapshotIslandRejected>,
        minUsefulSizePx: Int,
        maxIslandHeightPx: Int,
    ) {
        val interactive = insideInteractive || block.isInteractiveContainer()
        val reason = block.snapshotIslandReason()
        if (reason != null) {
            val rejection = when {
                interactive -> RichSnapshotIslandRejectReason.ContainsAction
                block.containsInteractiveAction() -> RichSnapshotIslandRejectReason.ContainsAction
                block.containsRuntime() -> RichSnapshotIslandRejectReason.ContainsScriptRuntime
                !block.isVisualOnlySubtree() -> RichSnapshotIslandRejectReason.ParentLayoutTooDependent
                else -> block.snapshotIslandSize().rejectReason(minUsefulSizePx, maxIslandHeightPx)
            }
            if (rejection == null) {
                val size = block.snapshotIslandSize()
                candidates += RichSnapshotIslandCandidate(
                    blockId = block.blockId,
                    stablePath = stablePath,
                    reason = reason,
                    estimatedWidthPx = size.widthPx,
                    estimatedHeightPx = size.heightPx,
                    visualOnly = true,
                )
                return
            } else {
                rejected += RichSnapshotIslandRejected(block.blockId, stablePath, rejection)
            }
        }
        block.childrenForSubtreePlanning().forEachIndexed { index, child ->
            visit(
                block = child,
                stablePath = "$stablePath.$index",
                insideInteractive = interactive,
                candidates = candidates,
                rejected = rejected,
                minUsefulSizePx = minUsefulSizePx,
                maxIslandHeightPx = maxIslandHeightPx,
            )
        }
    }

    private fun shouldWholeSnapshot(model: RichHtmlRenderModel): Boolean {
        if (model.unsupported.isNotEmpty()) return true
        if (model.animationStats.snapshotCandidateCount > 0) return true
        return model.visualHints.any { it in wholeSnapshotHints }
    }

    private fun RichBlock.snapshotIslandReason(): RichSnapshotIslandReason? {
        style.cssSnapshotReason()?.let { return it }
        return when (this) {
            is RichSvgBlock -> model.visualHints.firstNotNullOfOrNull { it.toSnapshotIslandReason() }
                ?: if (model.commands.size > 96) RichSnapshotIslandReason.ComplexSvg else null
            is RichUnsupportedBlock -> if (reason == RichUnsupportedReason.SvgTooComplex) {
                RichSnapshotIslandReason.UnsupportedStaticVisual
            } else {
                null
            }
            else -> null
        }
    }

    private fun RichVisualHint.toSnapshotIslandReason(): RichSnapshotIslandReason? = when (this) {
        RichVisualHint.SvgFilter -> RichSnapshotIslandReason.SvgFilter
        RichVisualHint.SvgMask -> RichSnapshotIslandReason.SvgMask
        RichVisualHint.SvgClipPath -> RichSnapshotIslandReason.SvgClipPath
        RichVisualHint.SvgUse,
        RichVisualHint.SvgSymbol,
        RichVisualHint.SvgPattern,
        RichVisualHint.SvgForeignObject,
        RichVisualHint.SvgMarker -> RichSnapshotIslandReason.ComplexSvg
        else -> null
    }

    private fun ComputedStyle.cssSnapshotReason(): RichSnapshotIslandReason? = when {
        maskImage != null -> RichSnapshotIslandReason.CssMask
        clipPath != null -> RichSnapshotIslandReason.CssClipPath
        backdropFilter != RichCssFilter.None -> RichSnapshotIslandReason.CssBackdropFilter
        cssFilter.requiresVisualApproximation -> RichSnapshotIslandReason.CssFilter
        mixBlendMode -> RichSnapshotIslandReason.MixBlendMode
        backgroundLayers.size > 1 || extraBackgroundLayers > 0 || backgroundUrl != null ->
            RichSnapshotIslandReason.ComplexBackground
        backgroundImage is RichBackgroundImage.ConicGradient -> RichSnapshotIslandReason.ComplexBackground
        else -> null
    }

    private fun RichBlock.snapshotIslandSize(): SnapshotIslandSize = when (this) {
        is RichSvgBlock -> SnapshotIslandSize(model.width.value.toInt(), model.height.value.toInt())
        else -> SnapshotIslandSize(
            widthPx = style.width.dpPxOrNull() ?: style.minWidth?.value?.toInt(),
            heightPx = style.height.dpPxOrNull() ?: style.minHeight?.value?.toInt(),
        )
    }

    private fun RichSize.dpPxOrNull(): Int? = (this as? RichSize.DpSize)?.value?.value?.toInt()

    private fun SnapshotIslandSize.rejectReason(
        minUsefulSizePx: Int,
        maxIslandHeightPx: Int,
    ): RichSnapshotIslandRejectReason? = when {
        widthPx == null || heightPx == null -> RichSnapshotIslandRejectReason.UnknownSize
        widthPx < minUsefulSizePx || heightPx < minUsefulSizePx -> RichSnapshotIslandRejectReason.TooSmall
        heightPx > maxIslandHeightPx -> RichSnapshotIslandRejectReason.TooLarge
        else -> null
    }

    private fun RichBlock.isVisualOnlySubtree(): Boolean = when (this) {
        is RichSvgBlock -> true
        is RichUnsupportedBlock -> reason == RichUnsupportedReason.SvgTooComplex
        is RichContainerBlock -> children.all { it.isVisualOnlySubtree() }
        is RichSnapshotIslandBlock -> true
        is RichTextBlock,
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichMathBlock,
        is RichButtonBlock,
        is RichDetailsBlock -> false
    }

    private fun RichBlock.containsInteractiveAction(): Boolean = when (this) {
        is RichButtonBlock -> true
        is RichDetailsBlock -> true
        is RichContainerBlock -> style.cursorPointer || children.any { it.containsInteractiveAction() }
        is RichTextBlock -> inlineBoxes.any { it.block.containsInteractiveAction() }
        is RichSnapshotIslandBlock -> false
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichSvgBlock,
        is RichMathBlock,
        is RichUnsupportedBlock -> false
    }

    private fun RichBlock.containsRuntime(): Boolean = when (this) {
        is RichUnsupportedBlock -> reason == RichUnsupportedReason.UnsafeHtml || reason == RichUnsupportedReason.DynamicRuntime
        is RichContainerBlock -> children.any { it.containsRuntime() }
        is RichButtonBlock -> children.any { it.containsRuntime() } || inlineBoxes.any { it.block.containsRuntime() }
        is RichDetailsBlock -> children.any { it.containsRuntime() }
        is RichTextBlock -> inlineBoxes.any { it.block.containsRuntime() }
        is RichSnapshotIslandBlock,
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichSvgBlock,
        is RichMathBlock -> false
    }

    private fun RichBlock.isInteractiveContainer(): Boolean = this is RichButtonBlock ||
        this is RichDetailsBlock ||
        style.cursorPointer

    private fun RichBlock.childrenForSubtreePlanning(): List<RichBlock> = when (this) {
        is RichContainerBlock -> children
        is RichButtonBlock -> children + inlineBoxes.map { it.block }
        is RichDetailsBlock -> children
        is RichTextBlock -> inlineBoxes.map { it.block }
        is RichSnapshotIslandBlock -> fallbackBlock?.let(::listOf).orEmpty()
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichSvgBlock,
        is RichMathBlock,
        is RichUnsupportedBlock -> emptyList()
    }

    private fun countActions(block: RichBlock): Int = when (block) {
        is RichButtonBlock -> 1 + block.children.sumOf(::countActions) + block.inlineBoxes.sumOf { countActions(it.block) }
        is RichDetailsBlock -> block.children.sumOf(::countActions)
        is RichContainerBlock -> block.children.sumOf(::countActions)
        is RichTextBlock -> block.inlineBoxes.sumOf { countActions(it.block) }
        is RichSnapshotIslandBlock -> 0
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichTableBlock,
        is RichSvgBlock,
        is RichMathBlock,
        is RichUnsupportedBlock -> 0
    }

    private data class SnapshotIslandSize(
        val widthPx: Int?,
        val heightPx: Int?,
    )

    private fun String.toSnapshotIslandRejectReason(): RichSnapshotIslandRejectReason = when (this) {
        "Action" -> RichSnapshotIslandRejectReason.ContainsAction
        "Runtime" -> RichSnapshotIslandRejectReason.ContainsScriptRuntime
        "TooManyIslands" -> RichSnapshotIslandRejectReason.TooManyIslands
        "Unsupported" -> RichSnapshotIslandRejectReason.SafetyRejected
        else -> RichSnapshotIslandRejectReason.ParentLayoutTooDependent
    }
}
