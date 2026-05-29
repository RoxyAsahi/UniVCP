package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDynamicWebViewBlockTest {
    @Test
    fun `cache id is stable for same content width and font scale`() {
        val html = "<div>dynamic</div>"

        val first = inlineDynamicWebViewCacheId(html, viewportWidthDp = 384f, fontScale = 1.0f)
        val second = inlineDynamicWebViewCacheId(html, viewportWidthDp = 384.2f, fontScale = 1.0f)

        assertEquals(first, second)
    }

    @Test
    fun `cache id separates width buckets`() {
        val html = "<div style='height:1200px'>dynamic</div>"

        val narrow = inlineDynamicWebViewCacheId(html, viewportWidthDp = 360f, fontScale = 1.0f)
        val wide = inlineDynamicWebViewCacheId(html, viewportWidthDp = 600f, fontScale = 1.0f)

        assertNotEquals(narrow, wide)
    }

    @Test
    fun `cache id separates font scale buckets`() {
        val html = "<p>text heavy dynamic content</p>"

        val normal = inlineDynamicWebViewCacheId(html, viewportWidthDp = 384f, fontScale = 1.0f)
        val large = inlineDynamicWebViewCacheId(html, viewportWidthDp = 384f, fontScale = 1.3f)

        assertNotEquals(normal, large)
        assertTrue(large.contains(":fs130:"))
        assertTrue(large.endsWith(":hm3"))
    }

    @Test
    fun `inline webview phase separates load measure live and release`() {
        var phase = InlineDynamicWebViewPhase.Deferred

        phase = inlineDynamicWebViewNextPhase(phase, "admit")
        assertEquals(InlineDynamicWebViewPhase.Acquiring, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "factory")
        assertEquals(InlineDynamicWebViewPhase.Attaching, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "load")
        assertEquals(InlineDynamicWebViewPhase.Loading, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "page-finished")
        assertEquals(InlineDynamicWebViewPhase.Measuring, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "height")
        assertEquals(InlineDynamicWebViewPhase.Live, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "freeze")
        assertEquals(InlineDynamicWebViewPhase.Freezing, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "release")
        assertEquals(InlineDynamicWebViewPhase.Releasing, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "released")
        assertEquals(InlineDynamicWebViewPhase.Released, phase)
    }

    @Test
    fun `inline webview phase does not regress live on late page callbacks`() {
        var phase = InlineDynamicWebViewPhase.Live

        phase = inlineDynamicWebViewNextPhase(phase, "load")
        assertEquals(InlineDynamicWebViewPhase.Live, phase)

        phase = inlineDynamicWebViewNextPhase(phase, "page-finished")
        assertEquals(InlineDynamicWebViewPhase.Live, phase)
    }

    @Test
    fun `inline webview admission releases slot after render process gone`() {
        inlineDynamicWebViewResetAdmissionForTest()

        assertTrue(inlineDynamicWebViewAcquireForTest("inline-a"))
        assertTrue(inlineDynamicWebViewAcquireForTest("inline-b"))
        assertEquals(2, inlineDynamicWebViewActiveCount())

        inlineDynamicWebViewReleaseForTest("inline-a")

        assertEquals(1, inlineDynamicWebViewActiveCount())
        assertTrue(inlineDynamicWebViewAcquireForTest("inline-c"))
        assertEquals(2, inlineDynamicWebViewActiveCount())

        inlineDynamicWebViewResetAdmissionForTest()
    }

    @Test
    fun `console log line is metadata only`() {
        val secret = "inline-secret-message"
        val line = inlineDynamicWebViewConsoleLogLine(
            inlineId = "inline:1234567890abcdef",
            level = "ERROR",
            lineNumber = 42,
        )

        assertTrue(line.contains("level=ERROR"))
        assertTrue(line.contains("line=42"))
        assertFalse(line.contains(secret))
        assertFalse(line.contains("sourceId"))
    }
}
