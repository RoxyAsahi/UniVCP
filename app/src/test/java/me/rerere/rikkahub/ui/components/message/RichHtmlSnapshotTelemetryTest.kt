package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlSnapshotTelemetryTest {
    @Test
    fun `snapshot telemetry summarizes cache queue and height warnings`() {
        RichHtmlRenderTelemetry.resetForTest()

        RichHtmlRenderTelemetry.recordSnapshotStart(
            id = "sample-a",
            widthPx = 720,
            reason = "VisualHint:CssBackdropFilter",
            joinedInFlight = true,
        )
        RichHtmlRenderTelemetry.recordSnapshotSuccess(
            id = "sample-a",
            widthPx = 720,
            heightPx = 260,
            renderTimeMs = 42,
            cacheHit = false,
            reason = "VisualHint:CssBackdropFilter",
            queueWaitMs = 18,
            joinedInFlight = true,
            nativeEstimateHeightPx = 180,
            heightWarningThresholdPct = 20f,
        )
        RichHtmlRenderTelemetry.recordSnapshotSuccess(
            id = "sample-b",
            widthPx = 720,
            heightPx = 180,
            renderTimeMs = 0,
            cacheHit = true,
            reason = "NativeFailure",
            nativeEstimateHeightPx = 175,
            heightWarningThresholdPct = 15f,
        )
        RichHtmlRenderTelemetry.recordSnapshotFailure(
            id = "sample-c",
            widthPx = 720,
            reason = "Timeout",
            message = "timed out",
            queueWaitMs = 33,
        )

        val summary = RichHtmlRenderTelemetry.snapshotSummary()

        assertEquals(1, summary.cacheHits)
        assertEquals(1, summary.cacheMisses)
        assertEquals(1, summary.joinedInFlight)
        assertEquals(2, summary.successes)
        assertEquals(1, summary.failures)
        assertEquals(1, summary.warnings)
        assertEquals(33, summary.maxQueueWaitMs)
        assertEquals(42, summary.maxRenderTimeMs)
        assertEquals(1, summary.failureReasons["Timeout"])
        assertTrue(RichHtmlRenderTelemetry.snapshotDebugSummary().contains("cacheHit=1"))
    }
}
