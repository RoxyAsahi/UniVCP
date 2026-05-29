package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlRenderTelemetryV5Test {
    @Test
    fun `compiler emits v5 compile css and svg telemetry without raw content`() {
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlCompiler.clearCacheForTest()
        val secret = "secret-v5-body-text"
        val html = """
            <div id="vcp-root">
              <style>
                #hero { color: rgb(255, 0, 0); }
                [data-kind="hero"] { font-weight: 700; }
                section[data-secret*="token"] article.card div * { opacity: .9; }
                .card > svg { filter: blur(2px); }
                div::unsupported { color: blue; }
              </style>
              <div id="hero" class="card" data-kind="hero">$secret
                <svg width="120" height="80"><filter id="f"></filter><rect width="120" height="80"/></svg>
              </div>
            </div>
        """.trimIndent()

        RichHtmlCompiler.compile(html)

        val css = RichHtmlRenderTelemetry.cssCascadeSummary()
        val compile = RichHtmlRenderTelemetry.compilePhaseSummary()
        val svg = RichHtmlRenderTelemetry.svgRouteSummary()
        val serialized = RichHtmlRenderTelemetry.cssCascadeSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.compilePhaseSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.svgRouteSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.v5DebugSummary()

        assertTrue(css.sampleCount >= 1)
        assertTrue(css.totalRules >= 2)
        assertTrue(css.attrRules >= 1)
        assertTrue(css.totalLegacyScans > 0)
        assertTrue(css.maxSelectorMatchMs >= 0)
        assertTrue(css.highCostSelectorCategories["SubstringAttribute"].orZeroForTest() >= 1)
        assertTrue(css.highCostSelectorCategories["LongDescendantChain"].orZeroForTest() >= 1)
        assertTrue(compile.sampleCount >= 1)
        assertTrue(svg.sampleCount >= 1)
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<style"))
        assertFalse(serialized.contains("rgb(255"))
        assertFalse(serialized.contains("token"))
    }

    @Test
    fun `long css uses indexed matcher without repeated full legacy scans`() {
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlCompiler.clearCacheForTest()
        val rules = (0..80).joinToString("\n") { index ->
            ".c$index { color: rgb($index, 0, 0); }"
        }
        val html = """
            <div id="vcp-root">
              <style>$rules</style>
              <div class="c80">indexed selector budget</div>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)

        val css = RichHtmlRenderTelemetry.cssCascadeSummary()
        val serialized = RichHtmlRenderTelemetry.cssCascadeSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.v5DebugSummary()

        assertTrue(css.sampleCount >= 1)
        assertTrue(css.legacyVerificationSkippedCount > 0)
        assertTrue(css.totalLegacyScans < css.totalRules * 3)
        assertEquals(Color(80, 0, 0), model.blocks.flatMap(::stylesForTest).first { it.color != null }.color)
        assertFalse(serialized.contains("indexed selector budget"))
        assertFalse(serialized.contains(".c80"))
    }

    @Test
    fun `media and prepared draw telemetry are metadata only`() {
        RichHtmlRenderTelemetry.resetForTest()
        PreparedDrawCache.clearForTest()
        val secretUrl = "https://example.test/image.png?token=secret-media-token"
        val request = RichMediaRequest.fromSource(secretUrl, RichMediaKind.Image, widthPx = 120, heightPx = 80)

        assertTrue(RichMediaLoader.safeData(request)?.startsWith("https://") == true)
        PreparedDrawCache.dashRecipe(listOf(4f, 2f))
        PreparedDrawCache.dashRecipe(listOf(4f, 2f))
        PreparedDrawCache.gradientRecipe(listOf(1, 2), listOf(0f, 1f))

        val media = RichHtmlRenderTelemetry.mediaSummary()
        val prepared = RichHtmlRenderTelemetry.preparedDrawSummary()
        val serialized = RichHtmlRenderTelemetry.mediaSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.preparedDrawSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.v5DebugSummary()

        assertTrue(media.sampleCount >= 1)
        assertTrue(media.byOutcome.getValue("accepted") >= 1)
        assertTrue(prepared.totalHits >= 1)
        assertTrue(prepared.totalMisses >= 1)
        assertFalse(serialized.contains(secretUrl))
        assertFalse(serialized.contains("secret-media-token"))
    }

    @Test
    fun `compiler routes media safety through unified loader telemetry`() {
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlCompiler.clearCacheForTest()
        val secret = "compiler-media-secret"
        val html = """
            <div id="vcp-root" style="background-image:url('file:///sdcard/$secret-bg.png')">
              <img src="https://example.test/safe.png" alt="safe">
              <img src="file:///sdcard/$secret-image.png" alt="private">
              <ul style="list-style-image:url(javascript:$secret)"><li>item</li></ul>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val media = RichHtmlRenderTelemetry.mediaSummary()
        val serialized = RichHtmlRenderTelemetry.mediaSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.v5DebugSummary() + "\n" +
            model.toString()

        assertTrue(model.blocks.flatMap(::imageSourcesForTest).all { !it.startsWith("file://") })
        assertTrue(media.byOutcome.getValue("compile-accepted") >= 1)
        assertTrue(media.byOutcome.getValue("compile-rejected") >= 2)
        assertTrue(media.bySafety["LocalFileRejected"].orZeroForTest() >= 2)
        assertTrue(media.bySafety["UnsupportedProtocol"].orZeroForTest() >= 1)
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("sdcard"))
        assertFalse(serialized.contains("javascript:"))
    }
}

private fun Int?.orZeroForTest(): Int = this ?: 0

private fun stylesForTest(block: RichBlock): List<ComputedStyle> = when (block) {
    is RichContainerBlock -> listOf(block.style) + block.children.flatMap(::stylesForTest)
    is RichButtonBlock -> listOf(block.style) + block.children.flatMap(::stylesForTest) +
        block.inlineBoxes.flatMap { stylesForTest(it.block) }
    is RichDetailsBlock -> listOf(block.style) + block.children.flatMap(::stylesForTest)
    is RichTextBlock -> listOf(block.style) + block.inlineBoxes.flatMap { stylesForTest(it.block) }
    is RichTextFlowBlock -> listOf(block.style) + block.paragraphs.map { it.style }
    is RichSnapshotIslandBlock -> listOf(block.style) + block.fallbackBlock?.let(::stylesForTest).orEmpty()
    is RichImageBlock,
    is RichTableBlock,
    is RichSvgBlock,
    is RichMathBlock,
    is RichUnsupportedBlock -> listOf(block.style)
}

private fun imageSourcesForTest(block: RichBlock): List<String> = when (block) {
    is RichImageBlock -> listOf(block.src)
    is RichContainerBlock -> block.children.flatMap(::imageSourcesForTest)
    is RichButtonBlock -> block.children.flatMap(::imageSourcesForTest) +
        block.inlineBoxes.flatMap { imageSourcesForTest(it.block) }
    is RichDetailsBlock -> block.children.flatMap(::imageSourcesForTest)
    is RichTextBlock -> block.inlineBoxes.flatMap { imageSourcesForTest(it.block) }
    is RichSnapshotIslandBlock -> block.fallbackBlock?.let(::imageSourcesForTest).orEmpty()
    is RichTextFlowBlock,
    is RichTableBlock,
    is RichSvgBlock,
    is RichMathBlock,
    is RichUnsupportedBlock -> emptyList()
}
