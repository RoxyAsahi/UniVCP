package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModel
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichUnsupportedReason
import me.rerere.rikkahub.ui.components.richtext.RichVisualHint
import me.rerere.rikkahub.ui.components.richtext.richInitialSnapshotDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class RichHtmlSnapshotPolicyTest {
    @Test
    fun `native static webview confidence snapshots before compile`() {
        val decision = RichHtmlSnapshotPolicy.beforeCompile(
            RichHtmlAnalysis(
                kind = RichHtmlRenderKind.NativeStatic,
                previewText = "preview",
                nativeConfidence = NativeConfidence.WebViewFallback,
            )
        )

        assertEquals(RichHtmlSnapshotRoute.Snapshot, decision.route)
        assertEquals("NativeConfidenceWebViewFallback", decision.reason)
    }

    @Test
    fun `interactive static stays native unless native fails`() {
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.InteractiveStatic,
            previewText = "button",
            nativeConfidence = NativeConfidence.Medium,
        )

        assertEquals(RichHtmlSnapshotRoute.Native, RichHtmlSnapshotPolicy.beforeCompile(analysis).route)
        assertEquals(
            RichHtmlSnapshotRoute.Native,
            RichHtmlSnapshotPolicy.afterCompile(analysis, RichHtmlRenderModel("id", emptyList())).route,
        )
        assertEquals(RichHtmlSnapshotRoute.Snapshot, RichHtmlSnapshotPolicy.nativeFailure(analysis).route)
    }

    @Test
    fun `critical visual hints snapshot native static content`() {
        val decision = RichHtmlSnapshotPolicy.afterCompile(
            RichHtmlAnalysis(
                kind = RichHtmlRenderKind.NativeStatic,
                previewText = "blend",
                nativeConfidence = NativeConfidence.Medium,
            ),
            RichHtmlRenderModel(
                id = "id",
                blocks = emptyList(),
                visualHints = listOf(RichVisualHint.CssMixBlendMode),
            ),
        )

        assertEquals(RichHtmlSnapshotRoute.Snapshot, decision.route)
        assertEquals("VisualHint:CssMixBlendMode", decision.reason)
    }

    @Test
    fun `safe css painting hints stay native after compile`() {
        val model = RichHtmlCompiler.compile(
            """
                <div id="vcp-root">
                  <div style="clip-path:circle(50%);mask-image:linear-gradient(to bottom,#000,transparent);backdrop-filter:blur(8px);">Avatar</div>
                </div>
            """.trimIndent(),
        )

        val decision = RichHtmlSnapshotPolicy.afterCompile(
            RichHtmlAnalysis(
                kind = RichHtmlRenderKind.NativeStatic,
                previewText = "avatar",
                nativeConfidence = NativeConfidence.Medium,
            ),
            model,
        )

        assertEquals(RichHtmlSnapshotRoute.Native, decision.route)
    }

    @Test
    fun `unsafe html never snapshots`() {
        val decision = RichHtmlSnapshotPolicy.afterCompile(
            RichHtmlAnalysis(
                kind = RichHtmlRenderKind.NativeStatic,
                previewText = "unsafe",
                nativeConfidence = NativeConfidence.Medium,
            ),
            RichHtmlRenderModel(
                id = "id",
                blocks = emptyList(),
                unsupported = listOf(RichUnsupportedReason.UnsafeHtml),
            ),
        )

        assertEquals(RichHtmlSnapshotRoute.DynamicPreview, decision.route)
        assertEquals("UnsafeHtml", decision.reason)
    }

    @Test
    fun `safe unsupported native static content snapshots`() {
        val decision = RichHtmlSnapshotPolicy.afterCompile(
            RichHtmlAnalysis(
                kind = RichHtmlRenderKind.NativeStatic,
                previewText = "svg",
                nativeConfidence = NativeConfidence.Medium,
            ),
            RichHtmlRenderModel(
                id = "id",
                blocks = emptyList(),
                unsupported = listOf(RichUnsupportedReason.SvgTooComplex),
            ),
        )

        assertEquals(RichHtmlSnapshotRoute.Snapshot, decision.route)
        assertEquals("Unsupported:SvgTooComplex", decision.reason)
    }

    @Test
    fun `layout animation snapshot candidate snapshots after compile`() {
        val model = RichHtmlCompiler.compile(
            """
                <div id="vcp-root">
                  <style>@keyframes grow { from { width:10px; } to { width:40px; } }</style>
                  <div style="animation:grow .4s ease-out forwards;">Grow</div>
                </div>
            """.trimIndent(),
        )

        val decision = RichHtmlSnapshotPolicy.afterCompile(
            RichHtmlAnalysis(
                kind = RichHtmlRenderKind.NativeStatic,
                previewText = "grow",
                nativeConfidence = NativeConfidence.Medium,
            ),
            model,
        )

        assertEquals(1, model.animationStats.layoutAnimationCount)
        assertEquals(1, model.animationStats.snapshotCandidateCount)
        assertEquals(RichHtmlSnapshotRoute.Snapshot, decision.route)
        assertEquals("AnimationSnapshotCandidate", decision.reason)
    }

    @Test
    fun `initial snapshot decision uses cached compiled animation policy`() {
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.NativeStatic,
            previewText = "nova",
            nativeConfidence = NativeConfidence.Medium,
        )
        val model = RichHtmlCompiler.compile(
            """
                <div id="response-root">
                  <style>
                    @keyframes grow {
                      from { width: 60px; }
                      to { width: 120px; }
                    }
                  </style>
                  <div style="width:60px;height:60px;border-radius:50%;animation:grow 2s infinite;">
                    Nova
                  </div>
                </div>
            """.trimIndent(),
        )

        assertEquals(RichHtmlSnapshotRoute.Native, RichHtmlSnapshotPolicy.beforeCompile(analysis).route)

        val decision = richInitialSnapshotDecision(analysis, model)

        assertEquals(1, model.animationStats.snapshotCandidateCount)
        assertEquals(RichHtmlSnapshotRoute.Snapshot, decision.route)
        assertEquals("AnimationSnapshotCandidate", decision.reason)
    }

    @Test
    fun `decorative unsupported animations stay native after staticization`() {
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.NativeStatic,
            previewText = "pulse",
            nativeConfidence = NativeConfidence.Medium,
        )
        val model = RichHtmlCompiler.compile(
            """
                <div id="response-root">
                  <style>
                    @keyframes pulse {
                      0% { box-shadow: 0 0 5px rgba(0,242,255,0.5); }
                      50% { box-shadow: 0 0 20px rgba(0,242,255,0.8); }
                      100% { box-shadow: 0 0 5px rgba(0,242,255,0.5); }
                    }
                  </style>
                  <div style="width:60px;height:60px;border-radius:50%;animation:pulse 2s infinite;">
                    Nova
                  </div>
                </div>
            """.trimIndent(),
        )

        val decision = RichHtmlSnapshotPolicy.afterCompile(analysis, model)

        assertEquals(1, model.animationStats.snapshotCandidateCount)
        assertEquals(1, model.animationStats.unsupportedPropertyCount)
        assertEquals(0, model.animationStats.layoutAnimationCount)
        assertEquals(0, model.animationStats.dependentVisibilityCount)
        assertEquals(RichHtmlSnapshotRoute.Native, decision.route)
    }

    @Test
    fun `complex dynamic remains dynamic preview`() {
        val analysis = RichHtmlAnalysis(
            kind = RichHtmlRenderKind.ComplexDynamic,
            previewText = "canvas",
            nativeConfidence = NativeConfidence.DynamicPreview,
        )

        assertEquals(RichHtmlSnapshotRoute.DynamicPreview, RichHtmlSnapshotPolicy.beforeCompile(analysis).route)
        assertEquals(
            RichHtmlSnapshotRoute.DynamicPreview,
            RichHtmlSnapshotPolicy.afterCompile(analysis, RichHtmlRenderModel("id", emptyList())).route,
        )
    }
}
