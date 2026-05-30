package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichContentRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.RichActualDecisionSource
import me.rerere.rikkahub.ui.components.message.RichRenderPlanRoute
import me.rerere.rikkahub.ui.components.message.analyzeRichHtml
import me.rerere.rikkahub.ui.components.message.buildRichRouteClosureReport
import me.rerere.rikkahub.ui.components.message.buildRichRenderPlan
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichRenderOrchestratorTest {
    @Test
    fun `complex dynamic routes to dynamic preview unless existing path is inline webview`() {
        val html = """<div id="vcp-root"><canvas></canvas><script>draw()</script></div>"""
        val analysis = analyzeRichHtml(html)
        val risk = RenderRiskScore.fromHtml(html, analysis)
        val dynamic = RichRenderOrchestrator.decide(input(html = html, risk = risk, analysis = analysis))
        val inline = RichRenderOrchestrator.decide(
            input(
                html = html,
                risk = risk,
                analysis = analysis,
                planRoute = RichRenderPlanRoute.InlineWebView,
            )
        )

        assertEquals(RichRenderDecisionRoute.DynamicPreview, dynamic.route)
        assertEquals(RichRenderDecisionRoute.InlineWebView, inline.route)
        assertFalse(dynamic.nativeAdmissionAllowed)
        assertFalse(inline.nativeAdmissionAllowed)
    }

    @Test
    fun `snapshot risk routes to snapshot`() {
        val html = """<div id="vcp-root"><svg><path d="M0 0"/></svg></div>"""
        val decision = RichRenderOrchestrator.decide(
            input(
                html = html,
                risk = RenderRiskScore(score = 90, route = RichContentRoute.Snapshot, reasons = setOf("test")),
            )
        )

        assertEquals(RichRenderDecisionRoute.Snapshot, decision.route)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `compiled plan snapshot and dynamic routes are authoritative`() {
        val snapshot = RichRenderOrchestrator.decide(
            input(
                planRoute = RichRenderPlanRoute.Snapshot,
                cachedModelAvailable = true,
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = true,
                    reason = "compiled-cache",
                ),
            )
        )
        val dynamic = RichRenderOrchestrator.decide(
            input(
                planRoute = RichRenderPlanRoute.DynamicPreview,
                cachedModelAvailable = true,
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = true,
                    reason = "compiled-cache",
                ),
            )
        )

        assertEquals(RichRenderDecisionRoute.Snapshot, snapshot.route)
        assertEquals(RichRenderDecisionRoute.DynamicPreview, dynamic.route)
        assertFalse(snapshot.nativeAdmissionAllowed)
        assertFalse(dynamic.nativeAdmissionAllowed)
    }

    @Test
    fun `fast scroll blocks risky new native first render`() {
        val html = """<div id="vcp-root"><p>${"long text ".repeat(80)}</p></div>"""
        val decision = RichRenderOrchestrator.decide(
            input(
                html = html,
                risk = RenderRiskScore.Medium,
                scrollState = RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = true,
                    fastScrolling = true,
                    visibleCellRange = 0..3,
                ),
                cellIndex = 1,
            )
        )

        assertEquals(RichRenderDecisionRoute.NativeDeferred, decision.route)
        assertEquals("fast-scroll-risk", decision.reason)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `already rendered content remains native allowed`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                alreadyRendered = true,
                scrollState = RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = true,
                    fastScrolling = true,
                    visibleCellRange = 0..3,
                ),
                cellIndex = 1,
            )
        )

        assertEquals(RichRenderDecisionRoute.Native, decision.route)
        assertEquals("already-rendered", decision.reason)
        assertTrue(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `cached model can be native allowed after admission`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                cachedModelAvailable = true,
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = true,
                    reason = "admitted",
                ),
            )
        )

        assertEquals(RichRenderDecisionRoute.Native, decision.route)
        assertEquals("compiled-cache", decision.reason)
        assertTrue(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `cached model without admission does not bypass policy`() {
        val decision = RichRenderOrchestrator.decide(input(cachedModelAvailable = true))

        assertEquals(RichRenderDecisionRoute.NativeDeferred, decision.route)
        assertEquals("compiled-cache-awaiting-admission", decision.reason)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `cached model still respects fast scroll risk gate before first native render`() {
        val html = """<div id="vcp-root"><p>${"cached risky text ".repeat(80)}</p></div>"""
        val decision = RichRenderOrchestrator.decide(
            input(
                html = html,
                risk = RenderRiskScore.Medium,
                cachedModelAvailable = true,
                scrollState = RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = true,
                    fastScrolling = true,
                    visibleCellRange = 0..3,
                ),
                cellIndex = 1,
            )
        )

        assertEquals(RichRenderDecisionRoute.NativeDeferred, decision.route)
        assertEquals("fast-scroll-risk", decision.reason)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `cached model still respects scheduler admission denial`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                cachedModelAvailable = true,
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = false,
                    reason = "queue-full",
                ),
            )
        )

        assertEquals(RichRenderDecisionRoute.NativeDeferred, decision.route)
        assertEquals("NativeAdmission:queue-full", decision.reason)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `queue-full native deferral retries after active scroll ends`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = false,
                    reason = "scroll-queue-full",
                ),
                scrollState = RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = false,
                    fastScrolling = false,
                    visibleCellRange = 0..3,
                ),
                cellIndex = 1,
            )
        )

        assertEquals(RichRenderDecisionRoute.NativeDeferred, decision.route)
        assertTrue(shouldRetryNativeAdmission(decision, decisionInputScrollState(), cellIndex = 1))
    }

    @Test
    fun `queue-full native deferral does not retry during active scroll or offscreen`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = false,
                    reason = "queue-full",
                ),
            )
        )

        assertFalse(
            shouldRetryNativeAdmission(
                decision,
                RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = true,
                    fastScrolling = false,
                    visibleCellRange = 0..3,
                ),
                cellIndex = 1,
            )
        )
        assertFalse(
            shouldRetryNativeAdmission(
                decision,
                RichRenderScrollState(
                    scrolling = false,
                    scrollInProgress = false,
                    fastScrolling = false,
                    visibleCellRange = 0..3,
                ),
                cellIndex = 8,
            )
        )
    }

    @Test
    fun `scheduler circuit breaker denial routes to snapshot`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = false,
                    reason = "circuit-breaker",
                ),
            )
        )

        assertEquals(RichRenderDecisionRoute.Snapshot, decision.route)
        assertEquals("NativeAdmission:circuit-breaker", decision.reason)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `height cache hit produces placeholder height`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                heightEntry = RichRenderHeightCacheEntry(
                    heightPx = 640,
                    confidence = RichRenderHeightConfidence.MeasuredNative,
                    updatedAtMs = 123L,
                ),
                estimatedPlaceholderHeightPx = 240,
                scrollState = RichRenderScrollState(visibleCellRange = 0..0),
                cellIndex = 3,
            )
        )

        assertEquals(640, decision.placeholderHeightPx)
        assertFalse(decision.nativeAdmissionAllowed)
    }

    @Test
    fun `no cache falls back to estimate`() {
        val decision = RichRenderOrchestrator.decide(
            input(
                heightEntry = null,
                estimatedPlaceholderHeightPx = 288,
                scrollState = RichRenderScrollState(visibleCellRange = 0..0),
                cellIndex = 3,
            )
        )

        assertEquals(288, decision.placeholderHeightPx)
    }

    @Test
    fun `decision reason is deterministic`() {
        val first = RichRenderOrchestrator.decide(input(risk = RenderRiskScore.Medium))
        val second = RichRenderOrchestrator.decide(input(risk = RenderRiskScore.Medium))

        assertEquals(first, second)
    }

    @Test
    fun `snapshot island render skips active and recent scrolling`() {
        assertTrue(
            shouldSkipSnapshotIslandRenderForScroll(
                RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = false,
                    fastScrolling = false,
                )
            )
        )
        assertTrue(
            shouldSkipSnapshotIslandRenderForScroll(
                RichRenderScrollState(
                    scrolling = true,
                    scrollInProgress = true,
                    fastScrolling = false,
                )
            )
        )
        assertEquals(
            "fast-scroll-skip",
            snapshotIslandScrollSkipReason(RichRenderScrollState(fastScrolling = true))
        )
        assertEquals(
            "recent-scroll-skip",
            snapshotIslandScrollSkipReason(RichRenderScrollState(scrolling = true))
        )
    }

    @Test
    fun `orchestrator and height delta telemetry are metadata only`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "orchestrator-secret-message"
        val id = renderTextCacheKey(secret)

        RichHtmlRenderTelemetry.recordOrchestratorDecision(
            id = id,
            route = "NativeDeferred",
            reason = "fast-scroll-risk",
            placeholderHeightPx = 400,
            placeholderSource = "Cache:MeasuredNative",
            nativeAdmissionAllowed = false,
            shouldPrewarm = false,
            alreadyRendered = false,
            cachedModelAvailable = false,
            transient = false,
            heightConfidence = "MeasuredNative",
        )
        RichHtmlRenderTelemetry.recordHeightDelta(
            id = id,
            route = "Native",
            placeholderHeightPx = 400,
            measuredHeightPx = 720,
            placeholderSource = "Cache:MeasuredNative",
            heightConfidence = "MeasuredNative",
        )

        val serialized = RichHtmlRenderTelemetry.orchestratorSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.orchestratorDebugSummary() + "\n" +
            RichHtmlRenderTelemetry.heightDeltaSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.heightDeltaDebugSummary()
        assertFalse(serialized.contains(secret))
        assertTrue(serialized.contains(id))
        assertTrue(RichHtmlRenderTelemetry.heightDeltaSummary().severeWarnings >= 1)
    }

    @Test
    fun `route closure telemetry categorizes orchestrator inline drift without source text`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "route-closure-inline-secret"
        val id = renderTextCacheKey(secret)
        val report = buildRichRouteClosureReport(
            plannedRoute = RichRenderPlanRoute.DynamicPreview.name,
            actualRoute = RichRenderPlanRoute.InlineWebView.name,
            actualDecisionSource = RichActualDecisionSource.Orchestrator,
        )

        RichHtmlRenderTelemetry.recordRouteClosure(id, report)

        val serialized = RichHtmlRenderTelemetry.routeClosureSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.v5DebugSummary()
        assertEquals("PlanDynamicActualInline", report.mismatchReason.name)
        assertTrue(serialized.contains("PlanDynamicActualInline"))
        assertTrue(serialized.contains("Orchestrator"))
        assertFalse(serialized.contains(secret))
    }

    @Test
    fun `duplicate scroll telemetry metadata is suppressed per rich block`() {
        RichHtmlRenderTelemetry.resetForTest()
        val id = renderTextCacheKey("duplicate-scroll-telemetry")
        val report = buildRichRouteClosureReport(
            plannedRoute = RichRenderPlanRoute.Native.name,
            actualRoute = RichRenderPlanRoute.Native.name,
            actualDecisionSource = RichActualDecisionSource.Orchestrator,
        )

        repeat(2) {
            RichHtmlRenderTelemetry.recordOrchestratorDecision(
                id = id,
                route = "Native",
                reason = "already-rendered",
                placeholderHeightPx = null,
                placeholderSource = "Cache:MeasuredNative",
                nativeAdmissionAllowed = true,
                shouldPrewarm = false,
                alreadyRendered = true,
                cachedModelAvailable = true,
                transient = false,
                heightConfidence = "MeasuredNative",
            )
            RichHtmlRenderTelemetry.recordHeightDelta(
                id = id,
                route = "Native",
                placeholderHeightPx = 480,
                measuredHeightPx = 480,
                placeholderSource = "Cache:MeasuredNative",
                heightConfidence = "MeasuredNative",
            )
            RichHtmlRenderTelemetry.recordRouteClosure(id, report)
        }

        assertEquals(1, RichHtmlRenderTelemetry.orchestratorSnapshot().size)
        assertEquals(1, RichHtmlRenderTelemetry.heightDeltaSnapshot().size)
        assertEquals(1, RichHtmlRenderTelemetry.routeClosureSnapshot().size)
    }

    private fun input(
        html: String = """<div id="vcp-root"><p>Hello</p></div>""",
        analysis: me.rerere.rikkahub.ui.components.message.RichHtmlAnalysis = analyzeRichHtml(html),
        risk: RenderRiskScore = RenderRiskScore.Low,
        planRoute: RichRenderPlanRoute? = null,
        scrollState: RichRenderScrollState = RichRenderScrollState(visibleCellRange = 0..3),
        cellIndex: Int? = 1,
        cachedModelAvailable: Boolean = false,
        heightEntry: RichRenderHeightCacheEntry? = null,
        alreadyRendered: Boolean = false,
        transient: Boolean = false,
        estimatedPlaceholderHeightPx: Int? = null,
        nativeAdmission: RichHtmlRenderAdmission? = null,
    ): RichRenderDecisionInput {
        val basePlan = buildRichRenderPlan(html = html, analysis = analysis, risk = risk)
        val plan = planRoute?.let { basePlan.withRoute(it, "test") } ?: basePlan
        return RichRenderDecisionInput(
            plan = plan,
            analysis = analysis,
            risk = risk,
            scrollState = scrollState,
            cellIndex = cellIndex,
            cachedModelAvailable = cachedModelAvailable,
            heightEntry = heightEntry,
            alreadyRendered = alreadyRendered,
            transient = transient,
            estimatedPlaceholderHeightPx = estimatedPlaceholderHeightPx,
            nativeAdmission = nativeAdmission,
        )
    }

    private fun decisionInputScrollState(): RichRenderScrollState = RichRenderScrollState(
        scrolling = true,
        scrollInProgress = false,
        fastScrolling = false,
        visibleCellRange = 0..3,
    )
}
