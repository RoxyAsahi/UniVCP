package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichSvgBlock
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RichHtmlFidelityReportTest {
    @Test
    fun `render seed fidelity report is metadata only and stable`() {
        val reports = loadRenderSeedAssistantTexts()
            .flatMap { text -> parseMessageTextBlocks(text, streaming = false).filterIsInstance<MessageTextBlock.VcpHtml>() }
            .mapIndexed { index, block -> buildReport(index, block.html) }

        assertTrue("Expected representative rich html samples", reports.size >= 7)
        assertTrue("Expected at least one CSS property in report", reports.sumOf { it.cssProperties.values.sum() } > 0)
        assertTrue("Expected at least one tag in report", reports.sumOf { it.tags.values.sum() } > 0)
        assertTrue("Expected compiled blocks", reports.sumOf { it.blockCount } > 0)

        val serialized = reports.joinToString("\n") { it.toMetadataLine() }
        loadRenderSeedAssistantTexts().forEach { sourceText ->
            sourceText.lineSequence()
                .map { it.trim() }
                .filter { it.length > 24 }
                .take(3)
                .forEach { line ->
                    assertFalse("Report must not contain source body text", serialized.contains(line))
                }
        }
    }

    @Test
    fun `visual hints cover css svg table and background gaps`() {
        val html = """
            <div id="vcp-root" style="background:url(data:image/png;base64,AAAA) no-repeat right 8px bottom 4px / 20px 20px content-box padding-box,linear-gradient(#000,#fff);filter:blur(2px);backdrop-filter:brightness(.9);mix-blend-mode:multiply;clip-path:inset(0);mask-image:url(data:image/png;base64,BBBB);">
              <style>@keyframes fade{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}.x:hover{opacity:.8}</style>
              <div class="x" style="animation:fade .4s ease-out forwards;transition:transform .2s ease;">Animated</div>
              <table><tr><td rowspan="2" colspan="2">A</td></tr></table>
              <svg width="40" height="40" viewBox="0 0 40 40">
                <defs><clipPath id="c"><rect width="10" height="10"/></clipPath><marker id="m"/></defs>
                <use href="#x"/><path d="M0 0 L20 20" clip-path="url(#c)" marker-end="url(#m)"/>
              </svg>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val hints = model.visualHints.map { it.name }.toSet()
        val root = model.blocks.single() as RichContainerBlock

        assertTrue("Background shorthand should parse URL", root.style.backgroundUrl?.startsWith("data:image/png") == true)
        assertTrue("Background shorthand should parse size", root.style.backgroundSize.toString().contains("Explicit"))
        assertTrue("CSS filter should be retained as structured model", root.style.cssFilter.blurRadius != null)
        assertTrue("Backdrop filter should be retained as structured model", root.style.backdropFilter.brightness != null)
        assertTrue("Background shorthand should parse extra layers", hints.contains("BackgroundExtraLayer"))
        assertTrue(hints.contains("CssFilter"))
        assertTrue(hints.contains("CssBackdropFilter"))
        assertTrue(hints.contains("CssMixBlendMode"))
        assertTrue(hints.contains("CssMask"))
        assertTrue(hints.contains("CssClipPath"))
        assertTrue(hints.contains("CssAnimation"))
        assertTrue(hints.contains("CssTransition"))
        assertTrue(hints.contains("CssKeyframes"))
        assertTrue(hints.contains("CssInteractivePseudoClass"))
        assertTrue(hints.contains("TableComplexSpan"))
        assertTrue(hints.contains("SvgClipPath"))
        assertTrue(hints.contains("SvgUse"))
        assertTrue(hints.contains("SvgMarker"))
    }

    private fun buildReport(index: Int, html: String): FidelityReport {
        val start = System.nanoTime()
        val model = RichHtmlCompiler.compile(html)
        val compileMs = (System.nanoTime() - start) / 1_000_000
        val document = Jsoup.parseBodyFragment(html)
        val tags = document.body().select("*")
            .map { it.tagName().lowercase() }
            .groupingBy { it }
            .eachCount()
        val cssProperties = document.select("[style]")
            .flatMap { element -> element.attr("style").split(";") }
            .mapNotNull { declaration -> declaration.substringBefore(":", "").trim().lowercase().takeIf { it.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        val svgFeatures = document.select("svg *")
            .map { it.tagName().lowercase() }
            .groupingBy { "svg:$it" }
            .eachCount()
        return FidelityReport(
            sampleId = "seed-$index:${model.id}",
            tags = tags,
            cssProperties = cssProperties,
            svgFeatures = svgFeatures,
            visualHints = model.visualHints.groupingBy { it.name }.eachCount(),
            unsupported = model.unsupported.groupingBy { it.name }.eachCount(),
            animationStrategy = model.animationStats.strategy.name,
            animationCounts = mapOf(
                "animated" to model.animationStats.animatedElementCount,
                "native" to model.animationStats.nativeAnimatedCount,
                "staticized" to model.animationStats.staticizedCount,
                "infinite" to model.animationStats.infiniteCount,
                "layout" to model.animationStats.layoutAnimationCount,
                "transition" to model.animationStats.transitionCount,
                "dependentVisibility" to model.animationStats.dependentVisibilityCount,
                "budgetExceeded" to model.animationStats.budgetExceededCount,
                "playOnceSuppressed" to model.animationStats.playOnceSuppressedCount,
                "snapshotCandidate" to model.animationStats.snapshotCandidateCount,
                "multiKeyframe" to model.animationStats.multiKeyframeCount,
                "unsupportedProperty" to model.animationStats.unsupportedPropertyCount,
            ).filterValues { it > 0 },
            compileMs = compileMs,
            blockCount = model.blocks.sumOf(::countBlocks),
        )
    }

    private fun countBlocks(block: RichBlock): Int {
        return when (block) {
            is RichContainerBlock -> 1 + block.children.sumOf(::countBlocks)
            is RichSvgBlock -> 1 + block.model.commands.size
            else -> 1
        }
    }

    private fun loadRenderSeedAssistantTexts(): List<String> {
        val seedFile = sequenceOf(
            File("src/debug/assets/render_seed/chat_render_seed.json"),
            File("app/src/debug/assets/render_seed/chat_render_seed.json"),
        ).first { it.exists() }
        val root = Json.parseToJsonElement(seedFile.readText()).jsonObject
        return root.getValue("conversations").jsonArray.flatMap { conversation ->
            conversation.jsonObject.getValue("messages").jsonArray
                .filter { message -> message.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
                .flatMap { message ->
                    message.jsonObject.getValue("parts").jsonArray
                        .filter { part -> part.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                        .map { part -> part.jsonObject.getValue("text").jsonPrimitive.content }
                }
        }
    }
}

private data class FidelityReport(
    val sampleId: String,
    val tags: Map<String, Int>,
    val cssProperties: Map<String, Int>,
    val svgFeatures: Map<String, Int>,
    val visualHints: Map<String, Int>,
    val unsupported: Map<String, Int>,
    val animationStrategy: String,
    val animationCounts: Map<String, Int>,
    val compileMs: Long,
    val blockCount: Int,
) {
    fun toMetadataLine(): String {
        return listOf(
            "id=$sampleId",
            "tags=${tags.toSortedMap()}",
            "css=${cssProperties.toSortedMap()}",
            "svg=${svgFeatures.toSortedMap()}",
            "hints=${visualHints.toSortedMap()}",
            "unsupported=${unsupported.toSortedMap()}",
            "animationStrategy=$animationStrategy",
            "animation=${animationCounts.toSortedMap()}",
            "compileMs=$compileMs",
            "blocks=$blockCount",
        ).joinToString(" ")
    }
}
