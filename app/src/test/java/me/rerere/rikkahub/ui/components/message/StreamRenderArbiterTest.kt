package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRenderArbiterTest {
    @Test
    fun `markdown streaming samples latest content inside window`() {
        val arbiter = StreamRenderArbiter(sampleWindowMs = 120)

        val first = arbiter.onFrame(frame("hello", nowMs = 0))
        val second = arbiter.onFrame(frame("hello world", nowMs = 50))
        val third = arbiter.onFrame(frame("hello world!", nowMs = 121))

        assertTrue(first.publishText)
        assertFalse(second.publishText)
        assertEquals(70L, second.nextCheckDelayMs)
        assertTrue(third.publishText)
    }

    @Test
    fun `unclosed vcp root publishes placeholder once then holds updates`() {
        val arbiter = StreamRenderArbiter(sampleWindowMs = 120, richBlockSampleWindowMs = 500)

        assertTrue(arbiter.onFrame(frame("intro", nowMs = 0)).publishText)
        val partialStart = arbiter.onFrame(
            frame("""intro <div id="vcp-root"><h2>title""", nowMs = 130)
        )
        val partialUpdate = arbiter.onFrame(
            frame("""intro <div id="vcp-root"><h2>title</h2><p>body""", nowMs = 260)
        )

        assertTrue(partialStart.publishText)
        assertTrue(partialStart.hasUnclosedRichBlock)
        assertFalse(partialUpdate.publishText)
        assertTrue(partialUpdate.hasUnclosedRichBlock)
    }

    @Test
    fun `unclosed vcp root samples latest content after rich window`() {
        val arbiter = StreamRenderArbiter(sampleWindowMs = 120, richBlockSampleWindowMs = 500)

        arbiter.onFrame(frame("""<div id="vcp-root"><h2>title""", nowMs = 0))
        val held = arbiter.onFrame(
            frame("""<div id="vcp-root"><h2>title</h2><p>body""", nowMs = 300)
        )
        val sampled = arbiter.onFrame(
            frame("""<div id="vcp-root"><h2>title</h2><p>body grows""", nowMs = 500)
        )

        assertFalse(held.publishText)
        assertEquals(200L, held.nextCheckDelayMs)
        assertTrue(sampled.publishText)
        assertTrue(sampled.hasUnclosedRichBlock)
    }

    @Test
    fun `closed vcp root flushes immediately`() {
        val arbiter = StreamRenderArbiter(sampleWindowMs = 120)

        arbiter.onFrame(frame("""<div id="vcp-root"><p>body""", nowMs = 0))
        val closed = arbiter.onFrame(frame("""<div id="vcp-root"><p>body</p></div>""", nowMs = 20))

        assertTrue(closed.publishText)
        assertTrue(closed.forceFlush)
        assertFalse(closed.hasUnclosedRichBlock)
    }

    @Test
    fun `final frame flushes before sample window`() {
        val arbiter = StreamRenderArbiter(sampleWindowMs = 120)

        arbiter.onFrame(frame("hello", streaming = true, nowMs = 0))
        val final = arbiter.onFrame(frame("hello final", streaming = false, nowMs = 30))

        assertTrue(final.publishText)
        assertTrue(final.forceFlush)
    }

    @Test
    fun `closed rich content still publishes during scroll-like updates`() {
        val arbiter = StreamRenderArbiter(sampleWindowMs = 120)

        val decision = arbiter.onFrame(
            frame(
                content = """<div id="response-root"><p>ready</p></div>""",
                nowMs = 0,
            )
        )

        assertTrue(decision.publishText)
        assertFalse(decision.hasUnclosedRichBlock)
    }

    @Test
    fun `content inspection detects partial details`() {
        val partial = inspectStreamRenderContent("<details><summary>More")
        val closed = inspectStreamRenderContent("<details><summary>More</summary><p>Body</p></details>")

        assertTrue(partial.hasRichRoot)
        assertTrue(partial.hasUnclosedRichBlock)
        assertTrue(closed.hasRichRoot)
        assertFalse(closed.hasUnclosedRichBlock)
    }

    @Test
    fun `content inspection shares rich root detection with styled roots`() {
        val visualRoot = inspectStreamRenderContent(
            """<div class="math-block" style="background:#fff;padding:12px;">ready</div>"""
        )
        val scriptTextOnly = inspectStreamRenderContent(
            """<script>const sample = '<div id="vcp-root">';</script><p>plain</p>"""
        )

        assertTrue(visualRoot.hasRichRoot)
        assertFalse(visualRoot.hasUnclosedRichBlock)
        assertFalse(scriptTextOnly.hasRichRoot)
        assertFalse(scriptTextOnly.hasUnclosedRichBlock)
    }

    private fun frame(
        content: String,
        streaming: Boolean = true,
        nowMs: Long,
    ): StreamRenderFrame {
        return StreamRenderFrame(
            content = content,
            streaming = streaming,
            nowMs = nowMs,
        )
    }
}
