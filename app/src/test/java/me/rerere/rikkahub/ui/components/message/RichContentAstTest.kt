package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentAstTest {
    @Test
    fun `paragraphs and spans preserve source order`() {
        val ast = buildRichContentAstFromHtml(
            """
                <div id="vcp-root">
                  <p>First <span>Second</span></p>
                  <p>Third</p>
                </div>
            """.trimIndent()
        )

        val text = ast.root.flattenText()

        assertEquals(listOf("First", "Second", "Third"), text)
        assertTrue(ast.stats.paragraphCount >= 2)
        assertTrue(ast.stats.textRunCount >= 3)
    }

    @Test
    fun `action buttons are preserved as action nodes`() {
        val ast = buildRichContentAstFromHtml(
            """<div id="vcp-root"><button data-send="go">Secret Action</button></div>"""
        )

        assertTrue(ast.root.flattenNodes().any { it is RichContentNode.ActionButton })
        assertEquals(1, ast.stats.actionCount)
    }

    @Test
    fun `tables images svg and details are dedicated nodes`() {
        val ast = buildRichContentAstFromHtml(
            """
                <div id="vcp-root">
                  <table><tr><td>A</td></tr></table>
                  <img src="https://example.com/a.png" alt="A">
                  <svg><path d="M0 0L1 1"/></svg>
                  <details><summary>More</summary><p>Body</p></details>
                </div>
            """.trimIndent()
        )
        val nodes = ast.root.flattenNodes()

        assertTrue(nodes.any { it is RichContentNode.Table })
        assertTrue(nodes.any { it is RichContentNode.Image })
        assertTrue(nodes.any { it is RichContentNode.Svg })
        assertTrue(nodes.any { it is RichContentNode.Details })
        assertEquals(1, ast.stats.tableCount)
        assertEquals(1, ast.stats.svgCount)
    }

    @Test
    fun `image media safety follows unified loader policy`() {
        val ast = buildRichContentAstFromHtml(
            """
                <div id="vcp-root">
                  <img src="https://example.test/safe.png">
                  <img src="file:///sdcard/private.png">
                  <img src="data:text/html;base64,PHNjcmlwdD4=">
                </div>
            """.trimIndent()
        )
        val images = ast.root.flattenNodes().filterIsInstance<RichContentNode.Image>()

        assertEquals(3, images.size)
        assertTrue(images[0].safeSource)
        assertFalse(images[1].safeSource)
        assertFalse(images[2].safeSource)
    }

    @Test
    fun `script and canvas become browser only`() {
        val ast = buildRichContentAstFromHtml(
            """<div id="vcp-root"><script>alert(1)</script><canvas></canvas></div>"""
        )

        assertEquals(2, ast.stats.browserOnlyCount)
        assertTrue(ast.root.flattenNodes().filterIsInstance<RichContentNode.BrowserOnly>().any { it.tagName == "script" })
        assertTrue(ast.root.flattenNodes().filterIsInstance<RichContentNode.BrowserOnly>().any { it.tagName == "canvas" })
    }

    @Test
    fun `span heavy content increases text flow candidate stats`() {
        val ast = buildRichContentAstFromHtml(
            """
                <div id="vcp-root">
                  <p><span>A</span><strong>B</strong><em>C</em><code>D</code><a href="https://example.com">E</a></p>
                </div>
            """.trimIndent()
        )

        assertTrue(ast.stats.textFlowCandidateCount >= 6)
    }

    @Test
    fun `layout and visual heavy style blocks text flow candidacy`() {
        val ast = buildRichContentAstFromHtml(
            """
                <div id="vcp-root">
                  <p style="display:grid;filter:blur(2px)">Blocked text flow</p>
                </div>
            """.trimIndent()
        )

        assertTrue(ast.stats.blockedTextFlowCount > 0)
    }

    @Test
    fun `stylesheet complex visual style contributes ast capability without leaking css`() {
        val secret = "stylesheet-secret-body"
        val ast = buildRichContentAstFromHtml(
            """
                <div id="vcp-root">
                  <style>.visual { filter: blur(2px); width: 120px; height: 80px; }</style>
                  <div class="visual">$secret</div>
                </div>
            """.trimIndent()
        )
        val plan = buildRichContentDocumentFromAst(
            source = "digest-source",
            sourceKind = RichContentSourceKind.Html,
            legacyAst = ast,
        ).subtreeRoutePlan()
        val serialized = plan.metadataLine()

        assertTrue(ast.stats.blockedTextFlowCount > 0)
        assertTrue(plan.hasSnapshotIslandCandidate)
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("blur"))
        assertFalse(serialized.contains(".visual"))
    }

    @Test
    fun `telemetry and report metadata do not include raw body text`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "ast-secret-body"
        val html = """<div id="vcp-root"><p>Intro</p><button data-send="go">$secret</button></div>"""
        val plan = buildRichRenderPlan(html = html, model = RichHtmlCompiler.compile(html))

        RichHtmlRenderTelemetry.recordRichRenderPlan(plan)

        val serialized = RichHtmlRenderTelemetry.richRenderPlanSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanDebugSummary() + "\n" +
            plan.toMetadataLine()
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<p>"))
        assertTrue(serialized.contains("astNodeCount="))
        assertTrue(serialized.contains("textFlowBlockedReasons="))
        assertTrue(serialized.contains("Action"))
    }

    @Test
    fun `builder is deterministic`() {
        val html = """<div id="vcp-root"><p><span>Stable</span></p></div>"""

        assertEquals(buildRichContentAstFromHtml(html), buildRichContentAstFromHtml(html))
    }
}

private fun RichContentNode.flattenNodes(): List<RichContentNode> {
    return listOf(this) + when (this) {
        is RichContentNode.Document -> children.flatMap { it.flattenNodes() }
        is RichContentNode.Container -> children.flatMap { it.flattenNodes() }
        is RichContentNode.Paragraph -> children.flatMap { it.flattenNodes() }
        is RichContentNode.ListBlock -> items.flatMap { it.flattenNodes() }
        is RichContentNode.ListItem -> children.flatMap { it.flattenNodes() }
        is RichContentNode.Details -> children.flatMap { it.flattenNodes() }
        is RichContentNode.ActionButton,
        is RichContentNode.BrowserOnly,
        is RichContentNode.Formula,
        is RichContentNode.Image,
        is RichContentNode.InlineCodeRun,
        is RichContentNode.LinkRun,
        is RichContentNode.StyledTextRun,
        is RichContentNode.Svg,
        is RichContentNode.Table,
        is RichContentNode.TextRun,
        is RichContentNode.Unsupported -> emptyList()
    }
}

private fun RichContentNode.flattenText(): List<String> {
    return flattenNodes().mapNotNull { node ->
        when (node) {
            is RichContentNode.TextRun -> node.text
            is RichContentNode.StyledTextRun -> node.text
            is RichContentNode.LinkRun -> node.text
            is RichContentNode.InlineCodeRun -> node.text
            else -> null
        }
    }
}
