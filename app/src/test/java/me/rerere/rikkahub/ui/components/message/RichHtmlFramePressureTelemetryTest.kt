package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RichHtmlFramePressureTelemetryTest {
    @Before
    fun setUp() {
        RichHtmlRenderTelemetry.resetForTest()
    }

    @Test
    fun `records recent frame pressure by direction`() {
        RichHtmlRenderTelemetry.recordFramePressure(
            frameMs = 41f,
            direction = "Up",
            visibleCellRange = 1..3,
            fastScrolling = false,
            inlineActiveCount = 1,
            sampleAtMs = 1_000L,
        )
        RichHtmlRenderTelemetry.recordFramePressure(
            frameMs = 56f,
            direction = "Down",
            visibleCellRange = 4..6,
            fastScrolling = true,
            inlineActiveCount = 2,
            sampleAtMs = 1_100L,
        )

        val up = RichHtmlRenderTelemetry.recentFramePressureWindow(
            nowMs = 1_200L,
            windowMs = 900L,
            direction = "Up",
        )

        assertEquals(1, up.jankyFrames)
        assertEquals(0, up.severeFrames)
        assertEquals(41f, up.maxFrameMs, 0.01f)
        assertEquals(1, up.inlineActiveMax)
    }

    @Test
    fun `ignores frames outside pressure threshold or window`() {
        RichHtmlRenderTelemetry.recordFramePressure(
            frameMs = 20f,
            direction = "Up",
            visibleCellRange = 1..3,
            fastScrolling = false,
            inlineActiveCount = 1,
            sampleAtMs = 1_000L,
        )
        RichHtmlRenderTelemetry.recordFramePressure(
            frameMs = 34f,
            direction = "Up",
            visibleCellRange = 1..3,
            fastScrolling = false,
            inlineActiveCount = 1,
            sampleAtMs = 1_000L,
        )

        val current = RichHtmlRenderTelemetry.recentFramePressureWindow(nowMs = 1_200L, windowMs = 900L)
        val expired = RichHtmlRenderTelemetry.recentFramePressureWindow(nowMs = 2_500L, windowMs = 900L)

        assertEquals(1, current.jankyFrames)
        assertTrue(expired.maxFrameMs == 0f)
    }
}
