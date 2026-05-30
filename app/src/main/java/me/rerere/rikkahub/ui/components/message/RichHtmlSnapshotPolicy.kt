package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModel
import me.rerere.rikkahub.ui.components.richtext.RichUnsupportedReason
import me.rerere.rikkahub.ui.components.richtext.RichVisualHint

internal enum class RichHtmlSnapshotRoute {
    Native,
    Snapshot,
    DynamicPreview,
}

internal data class RichHtmlSnapshotDecision(
    val route: RichHtmlSnapshotRoute,
    val reason: String = "",
)

internal object RichHtmlSnapshotPolicy {
    private val criticalHints = setOf(
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

    fun beforeCompile(analysis: RichHtmlAnalysis): RichHtmlSnapshotDecision {
        return when {
            analysis.kind == RichHtmlRenderKind.ComplexDynamic -> {
                RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.DynamicPreview, "ComplexDynamic")
            }

            analysis.kind == RichHtmlRenderKind.NativeStatic &&
                analysis.nativeConfidence == NativeConfidence.WebViewFallback -> {
                RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Snapshot, "NativeConfidenceWebViewFallback")
            }

            else -> RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Native)
        }
    }

    fun afterCompile(
        analysis: RichHtmlAnalysis,
        model: RichHtmlRenderModel,
    ): RichHtmlSnapshotDecision {
        if (analysis.kind == RichHtmlRenderKind.ComplexDynamic) {
            return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.DynamicPreview, "ComplexDynamic")
        }
        if (model.unsupported.contains(RichUnsupportedReason.UnsafeHtml)) {
            return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.DynamicPreview, "UnsafeHtml")
        }
        val snapshotIslandsAvoidWholeSnapshot = model.snapshotIslandStats.wholeSnapshotAvoided &&
            model.snapshotIslandStats.appliedCount > 0 &&
            model.unsupported.none {
                it == RichUnsupportedReason.UnsafeHtml || it == RichUnsupportedReason.DynamicRuntime
            }
        model.unsupported.firstOrNull()?.let {
            if (snapshotIslandsAvoidWholeSnapshot && it == RichUnsupportedReason.SvgTooComplex) {
                return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Native, "SnapshotIslandsAvoidedWholeSnapshot")
            }
            return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Snapshot, "Unsupported:${it.name}")
        }
        if (analysis.kind == RichHtmlRenderKind.NativeStatic) {
            if (model.animationStats.snapshotCandidateCount > 0) {
                if (snapshotIslandsAvoidWholeSnapshot) {
                    return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Native, "SnapshotIslandsAvoidedWholeSnapshot")
                }
                return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Snapshot, "AnimationSnapshotCandidate")
            }
            model.visualHints.firstOrNull { it in criticalHints }?.let {
                if (snapshotIslandsAvoidWholeSnapshot) {
                    return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Native, "SnapshotIslandsAvoidedWholeSnapshot")
                }
                return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Snapshot, "VisualHint:${it.name}")
            }
        }
        return RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Native)
    }

    fun nativeFailure(analysis: RichHtmlAnalysis): RichHtmlSnapshotDecision {
        return if (analysis.kind == RichHtmlRenderKind.ComplexDynamic) {
            RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.DynamicPreview, "ComplexDynamic")
        } else {
            RichHtmlSnapshotDecision(RichHtmlSnapshotRoute.Snapshot, "NativeFailure")
        }
    }
}
