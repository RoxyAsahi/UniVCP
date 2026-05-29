package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichContentRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlAnalysis
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderKind
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotPolicy
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotRoute
import me.rerere.rikkahub.ui.components.message.RichRenderPlan
import me.rerere.rikkahub.ui.components.message.RichRenderPlanRoute

internal data class RichRenderDecisionInput(
    val plan: RichRenderPlan,
    val analysis: RichHtmlAnalysis,
    val risk: RenderRiskScore,
    val scrollState: RichRenderScrollState,
    val cellIndex: Int?,
    val cachedModelAvailable: Boolean,
    val heightEntry: RichRenderHeightCacheEntry?,
    val alreadyRendered: Boolean,
    val transient: Boolean,
    val estimatedPlaceholderHeightPx: Int? = null,
    val nativeAdmission: RichHtmlRenderAdmission? = null,
)

internal data class RichRenderDecision(
    val route: RichRenderDecisionRoute,
    val reason: String,
    val placeholderHeightPx: Int?,
    val nativeAdmissionAllowed: Boolean,
    val shouldPrewarm: Boolean,
)

internal enum class RichRenderDecisionRoute {
    Native,
    NativeDeferred,
    Snapshot,
    DynamicPreview,
    Lightweight,
    InlineWebView,
}

internal object RichRenderOrchestrator {
    fun decide(input: RichRenderDecisionInput): RichRenderDecision {
        val placeholderHeightPx = input.heightEntry?.heightPx ?: input.estimatedPlaceholderHeightPx

        fun decision(
            route: RichRenderDecisionRoute,
            reason: String,
            nativeAllowed: Boolean,
            shouldPrewarm: Boolean = false,
            placeholder: Int? = placeholderHeightPx,
        ): RichRenderDecision = RichRenderDecision(
            route = route,
            reason = reason,
            placeholderHeightPx = placeholder,
            nativeAdmissionAllowed = nativeAllowed,
            shouldPrewarm = shouldPrewarm,
        )

        if (input.plan.route == RichRenderPlanRoute.InlineWebView) {
            return decision(
                route = RichRenderDecisionRoute.InlineWebView,
                reason = "Plan:InlineWebView",
                nativeAllowed = false,
            )
        }

        if (input.transient) {
            return decision(
                route = RichRenderDecisionRoute.Native,
                reason = "transient",
                nativeAllowed = true,
                placeholder = null,
            )
        }

        if (input.plan.route == RichRenderPlanRoute.Snapshot) {
            return decision(
                route = RichRenderDecisionRoute.Snapshot,
                reason = input.plan.reason.ifBlank { "Plan:Snapshot" },
                nativeAllowed = false,
            )
        }

        if (input.plan.route == RichRenderPlanRoute.DynamicPreview) {
            return decision(
                route = RichRenderDecisionRoute.DynamicPreview,
                reason = input.plan.reason.ifBlank { "Plan:DynamicPreview" },
                nativeAllowed = false,
            )
        }

        if (input.risk.route == RichContentRoute.Snapshot) {
            return decision(
                route = RichRenderDecisionRoute.Snapshot,
                reason = "Risk:Snapshot",
                nativeAllowed = false,
            )
        }

        if (input.risk.route == RichContentRoute.DynamicPreview ||
            input.analysis.kind == RichHtmlRenderKind.ComplexDynamic
        ) {
            return decision(
                route = RichRenderDecisionRoute.DynamicPreview,
                reason = if (input.risk.route == RichContentRoute.DynamicPreview) {
                    "Risk:DynamicPreview"
                } else {
                    "ComplexDynamic"
                },
                nativeAllowed = false,
            )
        }

        val beforeCompile = RichHtmlSnapshotPolicy.beforeCompile(input.analysis)
        if (beforeCompile.route == RichHtmlSnapshotRoute.Snapshot) {
            return decision(
                route = RichRenderDecisionRoute.Snapshot,
                reason = beforeCompile.reason.ifBlank { "BeforeCompile:Snapshot" },
                nativeAllowed = false,
            )
        }
        if (beforeCompile.route == RichHtmlSnapshotRoute.DynamicPreview) {
            return decision(
                route = RichRenderDecisionRoute.DynamicPreview,
                reason = beforeCompile.reason.ifBlank { "BeforeCompile:DynamicPreview" },
                nativeAllowed = false,
            )
        }

        if (input.alreadyRendered) {
            return decision(
                route = RichRenderDecisionRoute.Native,
                reason = "already-rendered",
                nativeAllowed = true,
                placeholder = null,
            )
        }

        input.cellIndex?.let { cellIndex ->
            if (input.scrollState.visibleCellRange.isNotEmptyRange() &&
                cellIndex !in input.scrollState.visibleCellRange
            ) {
                return if (cellIndex in input.scrollState.nearViewportRange) {
                    decision(
                        route = RichRenderDecisionRoute.NativeDeferred,
                        reason = "near-viewport-prewarm-only",
                        nativeAllowed = false,
                        shouldPrewarm = true,
                    )
                } else {
                    decision(
                        route = RichRenderDecisionRoute.Lightweight,
                        reason = "far-from-viewport",
                        nativeAllowed = false,
                    )
                }
            }
        }

        val expensive = input.analysis.isExpensiveFirstRenderForDecision()
        val risky = input.risk.score >= 35
        if (expensive && input.scrollState.fastScrolling) {
            return decision(
                route = RichRenderDecisionRoute.NativeDeferred,
                reason = "fast-scroll",
                nativeAllowed = false,
            )
        }
        if (risky && input.scrollState.fastScrolling) {
            return decision(
                route = RichRenderDecisionRoute.NativeDeferred,
                reason = "fast-scroll-risk",
                nativeAllowed = false,
            )
        }

        input.nativeAdmission?.let { admission ->
            if (!admission.nativeAllowed) {
                return when {
                    admission.reason == "circuit-breaker" ||
                        admission.reason == "risk-route:${RichContentRoute.Snapshot.name}" -> decision(
                        route = RichRenderDecisionRoute.Snapshot,
                        reason = "NativeAdmission:${admission.reason}",
                        nativeAllowed = false,
                    )

                    admission.reason == "risk-route:${RichContentRoute.DynamicPreview.name}" -> decision(
                        route = RichRenderDecisionRoute.DynamicPreview,
                        reason = "NativeAdmission:${admission.reason}",
                        nativeAllowed = false,
                    )

                    admission.reason == "near-viewport-prewarm-only" -> decision(
                        route = RichRenderDecisionRoute.NativeDeferred,
                        reason = "NativeAdmission:${admission.reason}",
                        nativeAllowed = false,
                        shouldPrewarm = true,
                    )

                    admission.reason == "fast-scroll" ||
                        admission.reason == "fast-scroll-risk" ||
                        admission.reason == "scroll-queue-full" ||
                        admission.reason == "queue-full" -> decision(
                        route = RichRenderDecisionRoute.NativeDeferred,
                        reason = "NativeAdmission:${admission.reason}",
                        nativeAllowed = false,
                    )

                    else -> decision(
                        route = RichRenderDecisionRoute.Lightweight,
                        reason = "NativeAdmission:${admission.reason}",
                        nativeAllowed = false,
                    )
                }
            }
            val reason = when {
                admission.reason == "already-rendered" -> "already-rendered"
                admission.reason == "compiled-cache" -> "compiled-cache"
                input.cachedModelAvailable && admission.reason == "admitted" -> "compiled-cache"
                else -> admission.reason
            }
            return decision(
                route = RichRenderDecisionRoute.Native,
                reason = reason,
                nativeAllowed = true,
                placeholder = null,
            )
        }

        if (input.cachedModelAvailable) {
            if (input.nativeAdmission == null) {
                return decision(
                    route = RichRenderDecisionRoute.NativeDeferred,
                    reason = "compiled-cache-awaiting-admission",
                    nativeAllowed = false,
                )
            }
            return decision(
                route = RichRenderDecisionRoute.Native,
                reason = "compiled-cache",
                nativeAllowed = true,
                placeholder = null,
            )
        }

        return decision(
            route = RichRenderDecisionRoute.Native,
            reason = "admitted",
            nativeAllowed = true,
            placeholder = null,
        )
    }
}

private fun RichHtmlAnalysis.isExpensiveFirstRenderForDecision(): Boolean {
    return kind != RichHtmlRenderKind.NativeStatic ||
        htmlLength >= 2_400 ||
        previewText.length >= 320 ||
        nativeConfidence.name.contains("Fallback", ignoreCase = true)
}

private fun IntRange.isNotEmptyRange(): Boolean = first <= last
