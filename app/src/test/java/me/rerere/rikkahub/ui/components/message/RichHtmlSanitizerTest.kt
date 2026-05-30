package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlSanitizerTest {
    @Test
    fun `report only output summarizes removals`() {
        val html = """<div><script>alert(1)</script><a href="javascript:alert(1)" onclick="evil()">x</a></div>"""

        val report = RichHtmlSanitizer.inspect(html)

        assertTrue(report.removedTagCount >= 1)
        assertTrue(report.dangerousProtocolCount >= 1)
        assertTrue(report.eventHandlerCount >= 1)
        assertEquals(RichHtmlSafetyReason.DangerousTag.name, report.runtimeReason)
    }

    @Test
    fun `report and telemetry do not expose raw content`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "sanitizer-secret-message"
        val html = """<div><img src="javascript:$secret"><p>$secret</p></div>"""
        val id = renderTextCacheKey(html)

        RichHtmlRenderTelemetry.recordSanitizerReport(id, RichHtmlSanitizer.inspect(html))
        val serialized = RichHtmlRenderTelemetry.sanitizerSnapshot().joinToString("\n") +
            RichHtmlRenderTelemetry.sanitizerDebugSummary()

        assertFalse(serialized.contains(secret))
        assertTrue(serialized.contains(id))
        assertTrue(RichHtmlRenderTelemetry.sanitizerSummary().dangerousProtocolCount >= 1)
    }

    @Test
    fun `safety agreement is explicit`() {
        val html = """<div><p>safe</p></div>"""
        val report = RichHtmlSanitizer.inspect(html)

        assertEquals(0, report.removedTagCount)
        assertTrue(report.existingSafetyAgreed)
    }

    @Test
    fun `image src safety follows unified media loader policy`() {
        val html = """
            <div>
              <img src="https://example.test/safe.png">
              <img src="file:///sdcard/private.png">
              <img src="data:text/html;base64,PHNjcmlwdD4=">
            </div>
        """.trimIndent()

        val report = RichHtmlSanitizer.inspect(html)

        assertEquals(2, report.dangerousProtocolCount)
        assertEquals(RichHtmlSafetyReason.UnsafeUrl.name, report.runtimeReason)
    }
}
