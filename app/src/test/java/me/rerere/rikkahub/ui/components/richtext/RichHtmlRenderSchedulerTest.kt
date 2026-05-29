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
    fun `cached model still respects fast-scroll admission gate`() {
        RichHtmlRenderScheduler.resetForTest()
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "cached risky card ".repeat(30),
            nativeConfidence = NativeConfidence.Medium,
            htmlLength = 3_600,
        )

        val admission = RichHtmlRenderScheduler.admission(
            key = "cached-digest",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = true,
                fastScrolling = true,
                visibleCellRange = 0..3,
            ),
            cellIndex = 1,
            risk = RenderRiskScore.Medium,
            cachedModelAvailable = true,
        )

        assertEquals(false, admission.nativeAllowed)
        assertEquals("fast-scroll", admission.reason)
    }

    @Test
    fun `already rendered cached model remains visible during fast scroll`() {
        RichHtmlRenderScheduler.resetForTest()
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "already rendered card ".repeat(30),
            nativeConfidence = NativeConfidence.Medium,
            htmlLength = 3_600,
        )
        RichHtmlRenderScheduler.markRendered("cached-digest")

        val admission = RichHtmlRenderScheduler.admission(
            key = "cached-digest",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = true,
                fastScrolling = true,
                visibleCellRange = 0..3,
            ),
            cellIndex = 1,
            risk = RenderRiskScore.Medium,
        )

        assertEquals(true, admission.nativeAllowed)
        assertEquals("already-rendered", admission.reason)
    }

    @Test
    fun `cached model uses the same first render capacity gate`() {
        RichHtmlRenderScheduler.resetForTest()
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "cached card",
            nativeConfidence = NativeConfidence.Medium,
            htmlLength = 1_200,
        )
        repeat(4) { index ->
            val admission = RichHtmlRenderScheduler.admission(
                key = "uncached-$index",
                analysis = analysis,
                scrollState = RichRenderScrollState(
                    scrolling = false,
                    fastScrolling = false,
                    visibleCellRange = 0..6,
                ),
                cellIndex = index,
                risk = RenderRiskScore.Low,
            )
            assertTrue(admission.nativeAllowed)
        }

        val queued = RichHtmlRenderScheduler.admission(
            key = "uncached-overflow",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = false,
                fastScrolling = false,
                visibleCellRange = 0..6,
            ),
            cellIndex = 5,
            risk = RenderRiskScore.Low,
        )
        val cached = RichHtmlRenderScheduler.admission(
            key = "cached-ready",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = false,
                fastScrolling = false,
                visibleCellRange = 0..6,
            ),
            cellIndex = 6,
            risk = RenderRiskScore.Low,
            cachedModelAvailable = true,
        )

        assertEquals(false, queued.nativeAllowed)
        assertEquals("queue-full", queued.reason)
        assertEquals(false, cached.nativeAllowed)
        assertEquals("queue-full", cached.reason)
    }

    @Test
    fun `cached model still admits through normal scheduler when capacity is available`() {
        RichHtmlRenderScheduler.resetForTest()
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "cached card",
            nativeConfidence = NativeConfidence.Medium,
            htmlLength = 1_200,
        )

        val admission = RichHtmlRenderScheduler.admission(
            key = "cached-normal-admission",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = false,
                fastScrolling = false,
                visibleCellRange = 0..3,
            ),
            cellIndex = 1,
            risk = RenderRiskScore.Low,
            cachedModelAvailable = true,
        )

        assertEquals(true, admission.nativeAllowed)
        assertEquals("admitted", admission.reason)
    }

    @Test
    fun `uncached first render admission consumes first render capacity`() {
        RichHtmlRenderScheduler.resetForTest()
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "uncached card",
            nativeConfidence = NativeConfidence.Medium,
            htmlLength = 1_200,
        )

        repeat(4) { index ->
            val admission = RichHtmlRenderScheduler.admission(
                key = "compile-$index",
                analysis = analysis,
                scrollState = RichRenderScrollState(
                    scrolling = false,
                    fastScrolling = false,
                    visibleCellRange = 0..12,
                ),
                cellIndex = index,
                risk = RenderRiskScore.Low,
                cachedModelAvailable = false,
                reserveFirstRenderSlot = true,
            )

            assertEquals(true, admission.nativeAllowed)
            assertEquals("admitted", admission.reason)
        }

        val queued = RichHtmlRenderScheduler.admission(
            key = "compile-overflow",
            analysis = analysis,
            scrollState = RichRenderScrollState(
                scrolling = false,
                fastScrolling = false,
                visibleCellRange = 0..12,
            ),
            cellIndex = 4,
            risk = RenderRiskScore.Low,
            cachedModelAvailable = false,
            reserveFirstRenderSlot = true,
        )

        assertEquals(false, queued.nativeAllowed)
        assertEquals("queue-full", queued.reason)
    }

    @Test
    fun `cached compiled model presents immediately after scheduler admission`() {
        assertEquals(
            true,
            shouldAllowNativePresentationImmediately(
                transientCache = false,
                alreadyRendered = false,
                cachedModelAvailable = true,
                deferForScroll = true,
            ),
        )
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
    fun `prewarm cancels stale jobs when scroll target changes`() = runBlocking {
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

        assertEquals(0, RichHtmlRenderScheduler.prewarmJobCountForTest())
    }

    @Test
    fun `height cache separates content type buckets`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
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

    @Test
    fun `height cache wrapper separates density and theme buckets`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        RichHtmlHeightCache.resetForTest()
        val lightDensity2 = RichHtmlHeightCache.key(
            id = "digest",
            viewportWidthDp = 360f,
            fontScale = 1f,
            density = 2f,
            themeBucket = "light",
            contentType = "RichHtmlCell",
        )
        val lightDensity3 = RichHtmlHeightCache.key(
            id = "digest",
            viewportWidthDp = 360f,
            fontScale = 1f,
            density = 3f,
            themeBucket = "light",
            contentType = "RichHtmlCell",
        )
        val darkDensity2 = RichHtmlHeightCache.key(
            id = "digest",
            viewportWidthDp = 360f,
            fontScale = 1f,
            density = 2f,
            themeBucket = "dark",
            contentType = "RichHtmlCell",
        )

        RichHtmlHeightCache.put(lightDensity2, 320)

        assertEquals(320, RichHtmlHeightCache.get(lightDensity2))
        assertNull(RichHtmlHeightCache.get(lightDensity3))
        assertNull(RichHtmlHeightCache.get(darkDensity2))
    }
}
