package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichTextFlowAstLowerer
import me.rerere.rikkahub.ui.components.richtext.RichTextFlowBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichContentDocumentTest {
    @Test
    fun `html import creates deterministic canonical document with capabilities`() {
        val html = """
            <div id="vcp-root">
              <p><strong>Hello</strong> <a href="https://example.test">link</a></p>
              <button data-send="go">Send</button>
              <svg><filter id="f"></filter><rect width="10" height="10"/></svg>
            </div>
        """.trimIndent()

        val first = buildRichContentDocumentFromHtml(html)
        val second = buildRichContentDocumentFromHtml(html)
        val route = first.routeReport()

        assertEquals(first, second)
        assertEquals(RichContentDocumentSchemaVersion, first.schemaVersion)
        assertTrue(first.stats.textRunCount >= 2)
        assertEquals(1, first.stats.actionCount)
        assertEquals(1, first.stats.svgCount)
        assertTrue(first.stats.snapshotIslandEligibleSubtreeCount >= 1)
        assertEquals(RichContentDocumentRoute.NativeWithSnapshotIslands, route.route)
    }

    @Test
    fun `markdown and protocol imports do not roundtrip through html`() {
        val markdown = buildRichContentDocumentFromMarkdown(
            """
                First **bold** paragraph with `code` and [safe link](https://example.test/path?token=secret)

                - First item
                - Second *item*
            """.trimIndent()
        )
        val protocol = buildRichContentDocumentFromProtocolAction("send:stable-action")
        val markdownNodes = markdown.root.flattenDocumentNodes()
        val serialized = markdown.toString() + "\n" +
            markdown.textFlowPlan().metadataLine() + "\n" +
            markdown.subtreeRoutePlan().metadataLine()

        assertEquals(RichContentSourceKind.Markdown, markdown.sourceKind)
        assertEquals(3, markdown.stats.paragraphCount)
        assertEquals(1, markdownNodes.count { it.kind == RichContentNodeKind.ListBlock })
        assertEquals(2, markdownNodes.count { it.kind == RichContentNodeKind.ListItem })
        assertTrue(markdown.stats.markRangeCount >= 4)
        assertTrue(markdownNodes.any { (it as? RichContentElementNode)?.marks?.contains(RichContentMark.Bold) == true })
        assertTrue(markdownNodes.any { (it as? RichContentElementNode)?.marks?.contains(RichContentMark.Code) == true })
        assertTrue(markdownNodes.any { (it as? RichContentElementNode)?.marks?.contains(RichContentMark.Link) == true })
        assertFalse(serialized.contains("token=secret"))
        assertFalse(serialized.contains("https://example.test"))
        assertEquals(RichContentSourceKind.Protocol, protocol.sourceKind)
        assertEquals(1, protocol.stats.actionCount)
        assertEquals(RichContentDocumentRoute.Native, protocol.routeReport().route)
    }

    @Test
    fun `document rebuild rehydrates text store for ast direct lowering`() {
        val markdown = "First **bold** paragraph\n\nSecond paragraph"
        buildRichContentDocumentFromMarkdown(markdown)
        RichContentDocumentTextStore.resetForTest()

        val rebuilt = buildRichContentDocumentFromMarkdown(markdown)
        val lowered = RichTextFlowAstLowerer.lowerDocumentOnly(rebuilt)
        val flow = lowered?.blocks?.single() as RichTextFlowBlock

        assertEquals("First bold paragraph", flow.paragraphs[0].content.text)
        assertEquals("Second paragraph", flow.paragraphs[1].content.text)
    }

    @Test
    fun `runtime and unsafe nodes are explicit browser only capabilities`() {
        val document = buildRichContentDocumentFromHtml(
            """<div id="vcp-root"><script>alert(1)</script><canvas></canvas></div>"""
        )

        assertEquals(2, document.stats.inlineWebViewRequiredCount)
        assertEquals(RichContentDocumentRoute.DynamicPreview, document.routeReport().route)
        assertTrue(document.root.flattenDocumentNodes().any { it.capabilities.unsafeRuntime })
    }

    @Test
    fun `text flow plan reports eligible nodes and hard stop reasons without source text`() {
        val secret = "ast-text-flow-secret"
        val document = buildRichContentDocumentFromHtml(
            """
                <div id="vcp-root">
                  <p>$secret <strong>bold</strong></p>
                  <button data-send="go">Go</button>
                  <img src="https://example.test/private.png?token=secret">
                </div>
            """.trimIndent()
        )

        val plan = document.textFlowPlan()
        val serialized = plan.metadataLine()

        assertTrue(plan.eligibleNodeCount > 0)
        assertTrue(plan.hardStopReasons.contains("Action"))
        assertTrue(plan.hardStopReasons.contains("Media"))
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("private.png"))
    }

    @Test
    fun `subtree route plan reports candidates and preserved actions without source text`() {
        val secret = "subtree-secret-body"
        val document = buildRichContentDocumentFromHtml(
            """
                <div id="vcp-root">
                  <p>$secret</p>
                  <button data-send="go">Go</button>
                  <svg width="120" height="120"><filter id="f"></filter><rect width="120" height="120"/></svg>
                </div>
            """.trimIndent()
        )

        val plan = document.subtreeRoutePlan()
        val serialized = plan.metadataLine()

        assertTrue(plan.hasSnapshotIslandCandidate)
        assertEquals(1, plan.nativePreservedActionCount)
        assertTrue(plan.candidateStablePaths.isNotEmpty())
        assertTrue(plan.candidateKindCounts.isNotEmpty())
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<svg"))
        assertFalse(serialized.contains("data-send"))
    }

    @Test
    fun `subtree route plan treats runtime as inline webview hard stop`() {
        val document = buildRichContentDocumentFromHtml(
            """<div id="vcp-root"><script>window.secret = 1</script><canvas></canvas></div>"""
        )

        val plan = document.subtreeRoutePlan()

        assertFalse(plan.allowsSnapshotIslandOptimization)
        assertEquals(2, plan.inlineWebViewRequiredCount)
        assertTrue(plan.rejectReasons.contains("Runtime"))
        assertEquals(2, plan.rejectReasonCounts["Runtime"])
        assertTrue(plan.rejectedStablePaths.isNotEmpty())
    }

    @Test
    fun `stylesheet visual capability is visible in document route report`() {
        val secret = "document-stylesheet-secret"
        val document = buildRichContentDocumentFromHtml(
            """
                <div id="vcp-root">
                  <style>.visual { filter: blur(2px); width: 120px; height: 80px; }</style>
                  <div class="visual">$secret</div>
                </div>
            """.trimIndent()
        )
        val route = document.routeReport()
        val subtree = document.subtreeRoutePlan()
        val serialized = route.toString() + "\n" + subtree.metadataLine()

        assertEquals(RichContentDocumentRoute.NativeWithSnapshotIslands, route.route)
        assertTrue(subtree.hasSnapshotIslandCandidate)
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains(".visual"))
        assertFalse(serialized.contains("blur"))
    }

    @Test
    fun `document telemetry and metadata are privacy safe`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "document-secret-body"
        val html = """<div id="vcp-root"><p>$secret</p><button data-send="x">Action</button></div>"""
        val plan = buildRichRenderPlan(html)
        val closure = buildRichRouteClosureReport(
            plannedRoute = RichRenderPlanRoute.Native.name,
            actualRoute = RichRenderPlanRoute.Snapshot.name,
            actualDecisionSource = RichActualDecisionSource.SnapshotPolicy,
        )

        RichHtmlRenderTelemetry.recordRichRenderPlan(plan)
        RichHtmlRenderTelemetry.recordRouteClosure(plan.id, closure)
        val serialized = plan.toMetadataLine() + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.routeClosureSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanDebugSummary()

        assertTrue(serialized.contains("documentNodes="))
        assertTrue(serialized.contains("documentId=${plan.documentId}"))
        assertTrue(serialized.contains("documentSchema=$RichContentDocumentSchemaVersion"))
        assertTrue(serialized.contains("documentSourceKind=Html"))
        assertTrue(serialized.contains("documentRoute="))
        assertTrue(serialized.contains("documentSchemas="))
        assertTrue(serialized.contains("documentSourceKinds="))
        assertTrue(serialized.contains("PlanNativeActualSnapshot"))
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<button"))
    }

    @Test
    fun `transform pipeline reports deterministic pass order and route mismatch`() {
        val html = """<div id="vcp-root"><svg><filter id="f"></filter><rect width="10" height="10"/></svg></div>"""
        val document = buildRichContentDocumentFromHtml(html)

        val first = RichContentTransformPipeline.report(
            document = document,
            currentRoute = RichRenderPlanRoute.Native,
        )
        val second = RichContentTransformPipeline.report(
            document = document,
            currentRoute = RichRenderPlanRoute.Native,
        )

        assertEquals(first.passOrder, second.passOrder)
        assertEquals(first.documentId, second.documentId)
        assertEquals(first.routeMismatchReason, second.routeMismatchReason)
        assertEquals(RichContentTransformPipeline.passOrder, first.passOrder)
        assertEquals(RichContentTransformPipelineVersion, first.pipelineVersion)
        assertEquals(RichContentTransformPipeline.passOrder.toSet(), first.passDurationsMs.keys)
        assertTrue(first.totalPassDurationMs >= 0L)
        assertEquals(RichContentRouteMismatchReason.AstSnapshotIslandCurrentNative, first.routeMismatchReason)
        assertTrue(first.snapshotIslandPlannedNodeCount >= 1)
        assertTrue(first.subtreeRouteCandidateNodeCount >= 1)
        assertEquals(RichContentDecisionSource.Document, first.textFlowDecisionSource)
        assertEquals(RichContentDecisionSource.Document, first.subtreeRouteDecisionSource)
        assertEquals(RichContentLoweringMode.Unavailable, first.textFlowLoweringMode)
        assertEquals(RichContentLoweringMode.Unavailable, first.subtreeRouteLoweringMode)
        assertTrue(first.metadataLine().contains("subtreeCandidates="))
        assertTrue(first.metadataLine().contains("textFlowDecisionSource=Document"))
        assertTrue(first.metadataLine().contains("textFlowLoweringMode=Unavailable"))
        assertTrue(first.metadataLine().contains("subtreeRouteDecisionSource=Document"))
        assertTrue(first.metadataLine().contains("subtreeRouteLoweringMode=Unavailable"))
        assertTrue(first.metadataLine().contains("passDurationsMs="))
        assertTrue(first.metadataLine().contains("passDurationTotalMs="))
        assertTrue(first.textFlowHardStopNodeCount >= 1)
        assertTrue(first.metadataLine().contains("textFlowHardStopReasons="))
        assertTrue(first.metadataLine().contains("routeMismatch=AstSnapshotIslandCurrentNative"))
    }

    @Test
    fun `transform pipeline reports opt in subtree digest cache reuse without source text`() {
        val secret = "subtree-cache-secret"
        val document = buildRichContentDocumentFromHtml(
            """<div id="vcp-root"><p>$secret</p><p><strong>again</strong></p></div>"""
        )
        RichContentTransformPipeline.resetSubtreeDigestCacheForTest()

        val first = RichContentTransformPipeline.report(document = document, observeSubtreeCache = true)
        val second = RichContentTransformPipeline.report(document = document, observeSubtreeCache = true)
        val serialized = second.metadataLine()

        assertEquals(0, first.subtreeCacheHitCount)
        assertTrue(first.subtreeCacheMissCount > 0)
        assertTrue(second.subtreeCacheHitCount > 0)
        assertTrue(second.subtreeCacheHitRate > 0f)
        assertTrue(serialized.contains("subtreeCacheHitRate="))
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<p>"))
    }

    @Test
    fun `transform pipeline metadata does not expose source text`() {
        val secret = "transform-secret-body"
        val html = """<div id="vcp-root"><p>$secret</p></div>"""
        val plan = buildRichRenderPlan(html)
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlRenderTelemetry.recordRichRenderPlan(plan)

        val serialized = plan.toMetadataLine() + "\n" +
            plan.transformReport?.metadataLine().orEmpty() + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanDebugSummary()

        assertTrue(serialized.contains("transformPipeline="))
        assertTrue(serialized.contains("transformPassDurationsMs="))
        assertTrue(serialized.contains("transformPassDurationTotalMs="))
        assertTrue(serialized.contains("transformRouteMismatch="))
        assertTrue(serialized.contains("transformTextFlowSource=Document"))
        assertTrue(serialized.contains("transformTextFlowLowering=Unavailable"))
        assertTrue(serialized.contains("transformSubtreeRouteSource=Document"))
        assertTrue(serialized.contains("transformSubtreeRouteLowering=Unavailable"))
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<p>"))
    }
}
