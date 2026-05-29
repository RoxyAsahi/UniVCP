package me.rerere.rikkahub.ui.components.message

internal data class RichRouteClosureReport(
    val plannedRoute: String,
    val actualRoute: String,
    val mismatchReason: RichRouteMismatchReason,
    val actualDecisionSource: RichActualDecisionSource,
)

internal enum class RichActualDecisionSource {
    Orchestrator,
    Scheduler,
    SnapshotPolicy,
    InlineWebViewAdmission,
    ComposableFallback,
    RuntimeFailure,
}

internal enum class RichRouteMismatchReason {
    None,
    PlanNativeActualSnapshot,
    PlanSnapshotActualNative,
    PlanDynamicActualInline,
    PlanNativeActualLightweight,
    PolicyOverride,
    RuntimeFallback,
    Other,
}

internal fun buildRichRouteClosureReport(
    plannedRoute: String,
    actualRoute: String,
    actualDecisionSource: RichActualDecisionSource,
    runtimeFallback: Boolean = false,
): RichRouteClosureReport {
    val mismatch = when {
        plannedRoute == actualRoute -> RichRouteMismatchReason.None
        runtimeFallback -> RichRouteMismatchReason.RuntimeFallback
        plannedRoute == RichRenderPlanRoute.Native.name && actualRoute == RichRenderPlanRoute.Snapshot.name ->
            RichRouteMismatchReason.PlanNativeActualSnapshot
        plannedRoute == RichRenderPlanRoute.Snapshot.name && actualRoute == RichRenderPlanRoute.Native.name ->
            RichRouteMismatchReason.PlanSnapshotActualNative
        plannedRoute == RichRenderPlanRoute.DynamicPreview.name && actualRoute == RichRenderPlanRoute.InlineWebView.name ->
            RichRouteMismatchReason.PlanDynamicActualInline
        plannedRoute == RichRenderPlanRoute.Native.name && actualRoute == RichRenderPlanRoute.Lightweight.name ->
            RichRouteMismatchReason.PlanNativeActualLightweight
        actualDecisionSource != RichActualDecisionSource.Orchestrator -> RichRouteMismatchReason.PolicyOverride
        else -> RichRouteMismatchReason.Other
    }
    return RichRouteClosureReport(
        plannedRoute = plannedRoute,
        actualRoute = actualRoute,
        mismatchReason = mismatch,
        actualDecisionSource = actualDecisionSource,
    )
}
