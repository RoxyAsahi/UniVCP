package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichUnsupportedReason
import me.rerere.rikkahub.ui.components.richtext.RichVisualHint
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichRenderPlanTest {
    @Test
    fun `simple static card plans as native with high or medium confidence`() {
        val html = """<div id="vcp-root"><p>Hello static card</p></div>"""

        val plan = buildRichRenderPlan(html)

        assertEquals(RichRenderPlanRoute.Native, plan.route)
        assertTrue(plan.nativeConfidence in setOf(RichRenderNativeConfidence.High, RichRenderNativeConfidence.Medium))
        assertEquals(RichRenderHeightCacheState.Unknown, plan.heightCache)
        assertTrue(plan.sourceNodeCount >= 2)
    }

    @Test
    fun `runtime content plans as dynamic preview`() {
        val script = """<div id="vcp-root"><script>alert('x')</script></div>"""
        val canvas = """<div id="response-root"><canvas></canvas></div>"""

        assertEquals(RichRenderPlanRoute.DynamicPreview, buildRichRenderPlan(script).route)
        assertEquals(RichRenderPlanRoute.DynamicPreview, buildRichRenderPlan(canvas).route)
        assertEquals(RichRenderNativeConfidence.BrowserRequired, buildRichRenderPlan(script).nativeConfidence)
    }

    @Test
    fun `complex visual hints plan as snapshot or low confidence`() {
        val html = """
            <div id="response-root" style="backdrop-filter:blur(8px);mask-image:linear-gradient(#000,transparent);">
              <p>Glass</p>
            </div>
        """.trimIndent()

        val plan = buildRichRenderPlan(html)

        assertTrue(
            plan.route == RichRenderPlanRoute.Snapshot ||
                plan.nativeConfidence in setOf(RichRenderNativeConfidence.Low, RichRenderNativeConfidence.BrowserRequired)
        )
        assertTrue(plan.visualHints.contains(RichVisualHint.CssBackdropFilter))
        assertTrue(plan.visualHints.contains(RichVisualHint.CssMask))
        assertTrue(plan.snapshotIslandCandidateCount > 0)
    }

    @Test
    fun `interactive button content increments action count`() {
        val html = """
            <div id="vcp-root">
              <button data-send="go">Go</button>
              <button onclick="input('next')">Next</button>
            </div>
        """.trimIndent()

        val plan = buildRichRenderPlan(html)

        assertTrue(plan.interactiveActionCount >= 2)
    }

    @Test
    fun `span heavy paragraph increments text flow candidates`() {
        val html = """
            <div id="vcp-root">
              <p><span>A</span><strong>B</strong><em>C</em><code>D</code><a href="https://example.com">E</a></p>
            </div>
        """.trimIndent()

        val plan = buildRichRenderPlan(html)

        assertTrue(plan.textFlowCandidateCount >= 6)
    }

    @Test
    fun `complex svg features increment snapshot island candidates`() {
        val html = """
            <div id="vcp-root">
              <svg width="40" height="40" viewBox="0 0 40 40">
                <defs><filter id="f"/><clipPath id="c"><rect width="10" height="10"/></clipPath></defs>
                <use href="#x"/><foreignObject width="10" height="10"></foreignObject>
              </svg>
            </div>
        """.trimIndent()

        val plan = buildRichRenderPlan(html)

        assertTrue(plan.snapshotIslandCandidateCount > 0)
        assertTrue(plan.visualHints.contains(RichVisualHint.SvgUse))
        assertTrue(plan.visualHints.contains(RichVisualHint.SvgForeignObject))
    }

    @Test
    fun `builder is deterministic for the same input`() {
        val html = """<div id="vcp-root" style="display:grid;gap:8px;"><p>Stable</p></div>"""

        assertEquals(buildRichRenderPlan(html), buildRichRenderPlan(html))
    }

    @Test
    fun `canonical document identity is deterministic and metadata only`() {
        val secret = "canonical-document-secret"
        val html = """<div id="vcp-root"><p>$secret</p><strong>bold</strong></div>"""

        val first = buildRichRenderPlan(html)
        val second = buildRichRenderPlan(html)
        val serialized = first.toMetadataLine()

        assertEquals(first.documentId, second.documentId)
        assertEquals(renderTextCacheKey(html), first.documentId)
        assertEquals(RichContentDocumentSchemaVersion, first.documentSchemaVersion)
        assertEquals(RichContentSourceKind.Html, first.documentSourceKind)
        assertTrue(serialized.contains("documentId=${first.documentId}"))
        assertTrue(serialized.contains("documentSchema=$RichContentDocumentSchemaVersion"))
        assertTrue(serialized.contains("documentSourceKind=Html"))
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<strong>"))
    }

    @Test
    fun `structural report can be skipped for transient preview plans`() {
        val html = """<div id="vcp-root"><p><span>Streaming</span></p></div>"""

        val plan = buildRichRenderPlan(html, includeStructuralReport = false)

        assertEquals(RichRenderPlanRoute.Native, plan.route)
        assertTrue(plan.sourceNodeCount > 0)
        assertEquals(renderTextCacheKey(html), plan.documentId)
        assertEquals(RichContentDocumentSchemaVersion, plan.documentSchemaVersion)
        assertEquals(RichContentSourceKind.Html, plan.documentSourceKind)
        assertEquals(null, plan.astStats)
        assertEquals(null, plan.documentStats)
        assertEquals(null, plan.transformReport)
    }

    @Test
    fun `telemetry and metadata do not contain raw html text`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "super-secret-plan-body"
        val html = """<div id="vcp-root"><p>$secret</p></div>"""
        val plan = buildRichRenderPlan(html)

        RichHtmlRenderTelemetry.recordRichRenderPlan(plan)

        val serialized = RichHtmlRenderTelemetry.richRenderPlanSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanDebugSummary() + "\n" +
            plan.toMetadataLine()
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<p>"))
        assertTrue(serialized.contains(plan.id))
        assertTrue(serialized.contains("documentId=${plan.documentId}"))
        assertTrue(serialized.contains("documentSchemas="))
    }

    @Test
    fun `compiled model updates visual hints and unsupported fields`() {
        val visualHtml = """
            <div id="vcp-root" style="mix-blend-mode:multiply;filter:blur(2px);">Visual</div>
        """.trimIndent()
        val visualModel = RichHtmlCompiler.compile(visualHtml)
        val visualPlan = buildRichRenderPlan(html = visualHtml, model = visualModel)

        assertTrue(visualPlan.visualHints.contains(RichVisualHint.CssMixBlendMode))
        assertTrue(visualPlan.visualHints.contains(RichVisualHint.CssFilter))

        val unsafeHtml = """<div id="vcp-root"><script>alert('x')</script></div>"""
        val unsafeModel = RichHtmlCompiler.compile(unsafeHtml)
        val unsafePlan = buildRichRenderPlan(html = unsafeHtml, model = unsafeModel)

        assertTrue(unsafePlan.unsupported.contains(RichUnsupportedReason.UnsafeHtml))
        assertEquals(RichRenderNativeConfidence.BrowserRequired, unsafePlan.nativeConfidence)
    }

    @Test
    fun `height cache state is preserved in plan`() {
        val html = """<div id="vcp-root"><p>Height</p></div>"""

        val hit = buildRichRenderPlan(html, heightCacheState = RichRenderHeightCacheState.Hit)
        val miss = buildRichRenderPlan(html, heightCacheState = RichRenderHeightCacheState.Miss)

        assertEquals(RichRenderHeightCacheState.Hit, hit.heightCache)
        assertEquals(RichRenderHeightCacheState.Miss, miss.heightCache)
    }

    @Test
    fun `height cache telemetry is capped resettable and metadata only`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "height-cache-secret-body"
        val id = renderTextCacheKey(secret)

        repeat(110) { index ->
            RichHtmlRenderTelemetry.recordHeightCache(
                id = id,
                contentType = "RichHtmlCell",
                hit = index % 2 == 0,
                heightPx = 200 + index,
                confidence = "MeasuredNative",
                rendererVersion = 1,
                documentSchemaVersion = RichContentDocumentSchemaVersion,
                persistent = true,
            )
        }

        val snapshot = RichHtmlRenderTelemetry.heightCacheSnapshot()
        val summary = RichHtmlRenderTelemetry.heightCacheDebugSummary()
        assertEquals(96, snapshot.size)
        assertTrue(summary.contains("MeasuredNative"))
        assertTrue(summary.contains("documentSchemas="))
        assertFalse(snapshot.joinToString("\n").contains(secret))
        assertFalse(summary.contains(secret))

        RichHtmlRenderTelemetry.resetForTest()
        assertTrue(RichHtmlRenderTelemetry.heightCacheSnapshot().isEmpty())
    }
}
