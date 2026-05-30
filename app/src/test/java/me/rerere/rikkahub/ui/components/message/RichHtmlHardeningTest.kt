package me.rerere.rikkahub.ui.components.message

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.ComputedStyle
import me.rerere.rikkahub.ui.components.richtext.RichCssDeclarationParser
import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichButtonBlock
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompileOptions
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichTextBlock
import me.rerere.rikkahub.ui.components.richtext.RichTextFlowBlock
import me.rerere.rikkahub.ui.components.richtext.StyleResolver
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlHardeningTest {
    @Test
    fun `render text cache key is stable strong and does not expose long content`() {
        val secret = "super-sensitive-render-body-" + "x".repeat(160)
        val same = renderTextCacheKey(secret)
        val changed = renderTextCacheKey(secret + "!")

        assertEquals(same, renderTextCacheKey(secret))
        assertNotEquals(same, changed)
        assertTrue(same.contains("sha256"))
        assertFalse(same.contains("super-sensitive-render-body"))
        assertFalse(same.contains("x".repeat(64)))
    }

    @Test
    fun `render lru cache exposes hit miss and eviction stats`() {
        val cache = RenderLruCache<String, String>(maxEntries = 2)

        assertEquals(null, cache.get("a"))
        cache.getOrPut("a") { "A" }
        cache.getOrPut("a") { "ignored" }
        cache.getOrPut("b") { "B" }
        cache.getOrPut("c") { "C" }

        val stats = cache.stats()
        assertEquals(2, stats.size)
        assertEquals(2, stats.maxEntries)
        assertTrue(stats.hits >= 1)
        assertTrue(stats.misses >= 3)
        assertEquals(1, stats.evictions)
    }

    @Test
    fun `rich html compiler async entry writes successful models to cache`() = runBlocking {
        RichHtmlCompiler.clearCacheForTest()
        val html = """<div id="vcp-root"><p>cached</p></div>"""

        val model = RichHtmlCompiler.compileAsync(html)
        val cached = RichHtmlCompiler.compileAsync(html)

        assertTrue(model.blocks.single() is RichContainerBlock || model.blocks.single() is RichTextFlowBlock)
        assertEquals(model, cached)
        assertEquals(1, RichHtmlCompiler.cacheStats().size)
        assertTrue(RichHtmlCompiler.cacheStats().hits >= 1)
    }

    @Test
    fun `cancelled async compile does not populate cache`() = runBlocking {
        RichHtmlCompiler.clearCacheForTest()
        val html = """<div id="vcp-root"><p>cancelled</p></div>"""

        val job = launch {
            currentCoroutineContext().cancel()
            RichHtmlCompiler.compileAsync(html)
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, RichHtmlCompiler.cacheStats().size)
    }

    @Test
    fun `css declaration parser uses ph css path and manual fallback`() {
        val normal = RichCssDeclarationParser.parse("color:red;background:#fff")

        RichCssDeclarationParser.forceFallbackForTest = true
        val fallback = RichCssDeclarationParser.parse("color:red;background:#fff")
        RichCssDeclarationParser.forceFallbackForTest = false

        val variableFallback = RichCssDeclarationParser.parse("--accent:#38bdf8;color:var(--accent)")

        assertEquals(normal, fallback)
        assertEquals("#38bdf8", variableFallback["--accent"])
        assertEquals("var(--accent)", variableFallback["color"])
    }

    @Test
    fun `fallback telemetry records render reasons without content`() {
        RichHtmlRenderTelemetry.resetForTest()

        RichHtmlRenderTelemetry.recordFallback(RichHtmlFallbackStage.Render, "SyntheticRenderFailure")

        val snapshot = RichHtmlRenderTelemetry.fallbackSnapshot()
        assertEquals(1, snapshot[RichHtmlFallbackKey(RichHtmlFallbackStage.Render, "SyntheticRenderFailure")])
    }

    @Test
    fun `route closure telemetry tracks mismatch reasons decision sources and planned pairs`() {
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlRenderTelemetry.recordRouteClosure(
            id = "route-closure",
            report = buildRichRouteClosureReport(
                plannedRoute = RichRenderPlanRoute.Native.name,
                actualRoute = RichRenderPlanRoute.Snapshot.name,
                actualDecisionSource = RichActualDecisionSource.SnapshotPolicy,
            ),
        )

        val summary = RichHtmlRenderTelemetry.routeClosureSummary()

        assertEquals(1, summary.sampleCount)
        assertEquals(1, summary.mismatches["PlanNativeActualSnapshot"])
        assertEquals(1, summary.decisionSources["SnapshotPolicy"])
        assertEquals(1, summary.plannedActualPairs["Native->Snapshot"])
    }

    @Test
    fun `font shorthand resolves size line height weight style and fallback family`() {
        RichHtmlCompiler.clearCacheForTest()
        val declarations = RichCssDeclarationParser.parse("font: italic 700 0.75rem/1.6 'Fira Code', monospace; margin:0;")
        assertTrue(declarations["font"]?.contains("0.75rem") == true)

        val html = """
            <div id="vcp-root">
              <p style="font: italic 700 0.75rem/1.6 'Fira Code', monospace; margin:0;">compact code card</p>
            </div>
        """.trimIndent()
        val document = Jsoup.parseBodyFragment(html)
        val paragraph = document.selectFirst("p")!!
        assertTrue(paragraph.attr("style").contains("font:"))
        val parsedAttr = RichCssDeclarationParser.parse(paragraph.attr("style"))
        assertTrue(parsedAttr.toString(), parsedAttr["font"]?.contains("0.75rem") == true)
        val resolved = StyleResolver.from(document, RichHtmlCompileOptions())
            .resolve(paragraph, ComputedStyle.Initial, emptyMap())
            .style
        assertEquals(resolved.toString(), 12f, resolved.fontSize.value, 0.01f)

        val text = RichHtmlCompiler.compile(html).blocks.textLike("compact code card")

        assertEquals(text.style.toString(), 12f, text.style.fontSize.value, 0.01f)
        assertEquals(19.2f, text.style.lineHeight.value, 0.01f)
        assertEquals(FontWeight.Bold, text.style.fontWeight)
        assertEquals(FontStyle.Italic, text.style.fontStyle)
        assertEquals(FontFamily.Monospace, text.style.fontFamily)
    }

    @Test
    fun `unitless line height inherits as multiplier and rem parses before em`() {
        RichHtmlCompiler.clearCacheForTest()
        val html = """
            <div id="vcp-root" style="font-size: 1rem; line-height: 1.6;">
              <p style="font-size: 0.75rem; text-transform: uppercase; margin: 0;">Glassmorphism</p>
              <button style="font-size: 0.9rem; padding: 10px 20px; border: none;">Continue</button>
            </div>
        """.trimIndent()

        val blocks = RichHtmlCompiler.compile(html).blocks.flatMap(::flattenRichBlocks)
        val label = blocks.textLike("GLASSMORPHISM")
        val button = blocks.filterIsInstance<RichButtonBlock>().singleOrNull()
            ?: error(blocks.joinToString { "${it::class.simpleName}:${it.style.fontSize}" })

        assertEquals(12f, label.style.fontSize.value, 0.01f)
        assertEquals(19.2f, label.style.lineHeight.value, 0.01f)
        assertEquals("GLASSMORPHISM", label.text)
        assertEquals(14.4f, button.style.fontSize.value, 0.01f)
        assertEquals(17.28f, button.style.lineHeight.value, 0.01f)
    }

    private data class TextLikeBlock(
        val text: String,
        val style: ComputedStyle,
    )

    private fun List<RichBlock>.textLike(expected: String): TextLikeBlock =
        flatMap(::textLikeBlocks)
            .first { it.text == expected }

    private fun textLikeBlocks(block: RichBlock): List<TextLikeBlock> = when (block) {
        is RichTextBlock -> listOf(TextLikeBlock(block.content.text, block.style)) +
            block.inlineBoxes.flatMap { textLikeBlocks(it.block) }
        is RichTextFlowBlock -> block.paragraphs.map { TextLikeBlock(it.content.text, it.style) }
        is RichContainerBlock -> block.children.flatMap(::textLikeBlocks)
        is RichButtonBlock -> block.children.flatMap(::textLikeBlocks) + TextLikeBlock(block.label.text, block.style)
        else -> emptyList()
    }

    private fun flattenRichBlocks(block: RichBlock): List<RichBlock> = when (block) {
        is RichContainerBlock -> listOf(block) + block.children.flatMap(::flattenRichBlocks)
        is RichButtonBlock -> listOf(block) + block.children.flatMap(::flattenRichBlocks)
        is RichTextBlock -> listOf(block) + block.inlineBoxes.flatMap { flattenRichBlocks(it.block) }
        else -> listOf(block)
    }
}
