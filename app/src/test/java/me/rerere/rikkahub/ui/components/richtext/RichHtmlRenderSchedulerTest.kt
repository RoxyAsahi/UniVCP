package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.components.message.NativeConfidence
import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichHtmlAnalysis
import me.rerere.rikkahub.ui.components.message.RichContentRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlRenderSchedulerTest {
    @Test
    fun `admitted first render stays admitted when scroll turns fast`() {
        RichHtmlRenderScheduler.resetForTest()
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "interactive card preview ".repeat(20),
            nativeConfidence = NativeConfidence.Medium,
            htmlLength = 3_200,
        )
        val slowAdmission = RichHtmlRenderScheduler.admission(
            key = "digest",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = true,
                fastScrolling = false,
                visibleCellRange = 2..4,
            ),
            cellIndex = 3,
            risk = RenderRiskScore.Medium,
        )

        val fastAdmission = RichHtmlRenderScheduler.admission(
            key = "digest",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = true,
                fastScrolling = true,
                visibleCellRange = 2..4,
            ),
            cellIndex = 3,
            risk = RenderRiskScore.Medium,
        )

        assertTrue(slowAdmission.nativeAllowed)
        assertTrue(fastAdmission.nativeAllowed)
        assertEquals("already-admitted", fastAdmission.reason)
    }

    @Test
    fun `prewarm target compiles into persistent cache`() = runBlocking {
        RichHtmlCompiler.clearCacheForTest()
        RichHtmlRenderScheduler.resetForTest()
        val html = """<div id="vcp-root"><p>near viewport</p></div>"""
        val options = RichHtmlCompileOptions(viewportWidthDp = 360f)

        RichHtmlRenderScheduler.updatePrewarmTargets(
            targets = listOf(
                RichHtmlPrewarmTarget(
                    html = html,
                    cellIndex = 8,
                    viewportWidthDp = 360f,
                    risk = RenderRiskScore.Low,
                )
            ),
            maxTargets = 1,
        )
        RichHtmlRenderScheduler.drainPrewarmForTest()

        assertNotNull(RichHtmlCompiler.getCached(html, options))
        assertEquals(0, RichHtmlRenderScheduler.prewarmJobCountForTest())
    }

    @Test
    fun `prewarm skips snapshot risk targets`() = runBlocking {
        RichHtmlCompiler.clearCacheForTest()
        RichHtmlRenderScheduler.resetForTest()
        val html = """<div id="vcp-root"><svg><path d="M0 0"/></svg></div>"""
        val options = RichHtmlCompileOptions(viewportWidthDp = 360f)

        RichHtmlRenderScheduler.updatePrewarmTargets(
            targets = listOf(
                RichHtmlPrewarmTarget(
                    html = html,
                    cellIndex = 12,
                    viewportWidthDp = 360f,
                    risk = RenderRiskScore(score = 90, route = RichContentRoute.Snapshot),
                )
            ),
            maxTargets = 1,
        )
        RichHtmlRenderScheduler.drainPrewarmForTest()

        assertNull(RichHtmlCompiler.getCached(html, options))
        assertEquals(0, RichHtmlRenderScheduler.prewarmJobCountForTest())
    }

    @Test
    fun `prewarm skips targets already cached for viewport`() = runBlocking {
        RichHtmlCompiler.clearCacheForTest()
        RichHtmlRenderScheduler.resetForTest()
        val html = """<div id="vcp-root"><p>cached near viewport</p></div>"""
        val uncachedHtml = """<div id="vcp-root"><p>still needs prewarm</p></div>"""
        val options = RichHtmlCompileOptions(viewportWidthDp = 360f)
        RichHtmlCompiler.compileAsync(html, options)

        RichHtmlRenderScheduler.updatePrewarmTargets(
            targets = listOf(
                RichHtmlPrewarmTarget(
                    html = html,
                    cellIndex = 9,
                    viewportWidthDp = 360f,
                    risk = RenderRiskScore.Low,
                ),
                RichHtmlPrewarmTarget(
                    html = uncachedHtml,
                    cellIndex = 10,
                    viewportWidthDp = 360f,
                    risk = RenderRiskScore.Low,
                ),
            ),
            maxTargets = 1,
        )
        RichHtmlRenderScheduler.drainPrewarmForTest()

        assertNotNull(RichHtmlCompiler.getCached(html, options))
        assertNotNull(RichHtmlCompiler.getCached(uncachedHtml, options))
        assertEquals(0, RichHtmlRenderScheduler.prewarmJobCountForTest())
    }

    @Test
    fun `prewarm keeps started jobs when scroll target changes`() = runBlocking {
        RichHtmlCompiler.clearCacheForTest()
        RichHtmlRenderScheduler.resetForTest()
        val html = buildString {
            append("""<div id="vcp-root">""")
            repeat(240) { index ->
                append("""<p style="padding:2px 4px;background:linear-gradient(90deg,#fff,#eef);">near viewport $index</p>""")
            }
            append("</div>")
        }
        val options = RichHtmlCompileOptions(viewportWidthDp = 360f)

        RichHtmlRenderScheduler.updatePrewarmTargets(
            targets = listOf(
                RichHtmlPrewarmTarget(
                    html = html,
                    cellIndex = 8,
                    viewportWidthDp = 360f,
                    risk = RenderRiskScore.Low,
                )
            ),
            maxTargets = 1,
        )
        RichHtmlRenderScheduler.updatePrewarmTargets(emptyList(), maxTargets = 1)
        RichHtmlRenderScheduler.drainPrewarmForTest()

        assertNotNull(RichHtmlCompiler.getCached(html, options))
        assertEquals(0, RichHtmlRenderScheduler.prewarmJobCountForTest())
    }

    @Test
    fun `height cache separates content type buckets`() {
        RichHtmlHeightCache.resetForTest()
        val richHtmlKey = RichHtmlHeightCache.key(
            id = "digest",
            viewportWidthDp = 360f,
            fontScale = 1f,
            contentType = "RichHtmlCell",
        )
        val snapshotKey = RichHtmlHeightCache.key(
            id = "digest",
            viewportWidthDp = 360f,
            fontScale = 1f,
            contentType = "SnapshotCell",
        )

        RichHtmlHeightCache.put(richHtmlKey, 240)

        assertEquals(240, RichHtmlHeightCache.get(richHtmlKey))
        assertNull(RichHtmlHeightCache.get(snapshotKey))
    }
}
