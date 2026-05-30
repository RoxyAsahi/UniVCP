package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSubtreeRoutePlannerTest {
    @Test
    fun `visual only complex svg becomes snapshot island candidate`() {
        val model = RichHtmlCompiler.compile(
            """
                <div id="vcp-root">
                  <svg width="120" height="120" viewBox="0 0 120 120">
                    <defs><filter id="f"><feGaussianBlur stdDeviation="4"/></filter></defs>
                    <rect width="100" height="100" filter="url(#f)"/>
                  </svg>
                </div>
            """.trimIndent()
        )

        val plan = RichSubtreeRoutePlanner.plan(model)

        assertEquals(RichSubtreeRoute.NativeWithSnapshotIslands, plan.rootRoute)
        assertTrue(plan.candidates.any { it.reason == RichSnapshotIslandReason.SvgFilter })
    }

    @Test
    fun `button or action ancestor rejects visual subtree`() {
        val svg = svgBlock("button-svg", listOf(RichVisualHint.SvgMask))
        val model = RichHtmlRenderModel(
            id = "button",
            blocks = listOf(
                RichButtonBlock(
                    blockId = "button",
                    style = ComputedStyle.Initial,
                    label = AnnotatedString("go"),
                    action = "go",
                    children = listOf(svg),
                )
            ),
        )

        val plan = RichSubtreeRoutePlanner.plan(model)

        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.rejected.any { it.reason == RichSnapshotIslandRejectReason.ContainsAction })
    }

    @Test
    fun `css mask filter backdrop and blend-like visuals produce candidate`() {
        val block = RichContainerBlock(
            blockId = "glass",
            style = ComputedStyle.Initial.copy(
                width = RichSize.DpSize(120.dp),
                height = RichSize.DpSize(96.dp),
                cssFilter = RichCssFilter(blurRadius = 4.dp),
                backdropFilter = RichCssFilter(blurRadius = 8.dp),
                maskImage = RichBackgroundImage.LinearGradient(180f, emptyList()),
            ),
            children = emptyList(),
        )

        val plan = RichSubtreeRoutePlanner.plan(RichHtmlRenderModel(id = "css", blocks = listOf(block)))

        assertEquals(RichSubtreeRoute.NativeWithSnapshotIslands, plan.rootRoute)
        assertTrue(plan.candidates.single().reason in setOf(
            RichSnapshotIslandReason.CssMask,
            RichSnapshotIslandReason.CssBackdropFilter,
            RichSnapshotIslandReason.CssFilter,
        ))
    }

    @Test
    fun `runtime content is not an island and stays dynamic`() {
        val model = RichHtmlRenderModel(
            id = "runtime",
            blocks = listOf(
                RichUnsupportedBlock(
                    blockId = "runtime",
                    style = ComputedStyle.Initial,
                    reason = RichUnsupportedReason.DynamicRuntime,
                    previewText = "runtime",
                )
            ),
            unsupported = listOf(RichUnsupportedReason.DynamicRuntime),
        )

        val plan = RichSubtreeRoutePlanner.plan(model)

        assertEquals(RichSubtreeRoute.DynamicPreview, plan.rootRoute)
        assertTrue(plan.candidates.isEmpty())
    }

    @Test
    fun `candidate count over budget keeps whole snapshot`() {
        val model = RichHtmlRenderModel(
            id = "budget",
            blocks = listOf(
                svgBlock("s0", listOf(RichVisualHint.SvgMask)),
                svgBlock("s1", listOf(RichVisualHint.SvgFilter)),
                svgBlock("s2", listOf(RichVisualHint.SvgClipPath)),
            ),
        )

        val plan = RichSubtreeRoutePlanner.plan(model)

        assertEquals(RichSubtreeRoute.WholeSnapshot, plan.rootRoute)
        assertEquals(3, plan.candidates.size)
        assertTrue(plan.rejected.any { it.reason == RichSnapshotIslandRejectReason.TooManyIslands })
    }

    @Test
    fun `tiny visual subtree is rejected`() {
        val model = RichHtmlRenderModel(id = "tiny", blocks = listOf(svgBlock("tiny", listOf(RichVisualHint.SvgMask), 32)))

        val plan = RichSubtreeRoutePlanner.plan(model)

        assertTrue(plan.candidates.isEmpty())
        assertTrue(plan.rejected.any { it.reason == RichSnapshotIslandRejectReason.TooSmall })
    }

    @Test
    fun `stable path is deterministic and not text derived`() {
        val secret = "never-use-this-text"
        val model = RichHtmlRenderModel(
            id = "stable",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    children = listOf(
                        RichTextBlock("text", ComputedStyle.Initial, AnnotatedString(secret)),
                        svgBlock("svg", listOf(RichVisualHint.SvgClipPath)),
                    ),
                )
            ),
        )

        val first = RichSubtreeRoutePlanner.plan(model)
        val second = RichSubtreeRoutePlanner.plan(model)

        assertEquals(first.candidates.single().stablePath, second.candidates.single().stablePath)
        assertFalse(first.candidates.single().stablePath.contains(secret))
    }

    @Test
    fun `telemetry summary does not leak raw text or html`() {
        RichHtmlRenderTelemetry.resetForTest()
        val secret = "snapshot-island-secret"
        val model = RichHtmlRenderModel(
            id = "telemetry",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    children = listOf(
                        RichTextBlock("text", ComputedStyle.Initial, AnnotatedString(secret)),
                        svgBlock("svg", listOf(RichVisualHint.SvgFilter)),
                    ),
                )
            ),
        )
        val plan = RichSubtreeRoutePlanner.plan(model)

        RichHtmlRenderTelemetry.recordSnapshotIslandPlan("digest-only", plan, appliedCount = 1)

        val serialized = RichHtmlRenderTelemetry.snapshotIslandPlanSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.snapshotIslandDebugSummary()
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<svg"))
        assertTrue(serialized.contains("digest-only"))
    }

    private fun svgBlock(
        id: String,
        hints: List<RichVisualHint>,
        size: Int = 120,
    ): RichSvgBlock = RichSvgBlock(
        blockId = id,
        style = ComputedStyle.Initial,
        model = RichSvgModel(
            width = size.dp,
            height = size.dp,
            viewBox = RichSvgViewBox(0f, 0f, size.toFloat(), size.toFloat()),
            commands = listOf(
                RichSvgCommand.Rect(
                    x = 0f,
                    y = 0f,
                    width = size.toFloat(),
                    height = size.toFloat(),
                    rx = 0f,
                    ry = 0f,
                    fill = RichSvgPaint.Solid(androidx.compose.ui.graphics.Color.Black),
                    stroke = null,
                    strokeWidth = 0f,
                )
            ),
            visualHints = hints,
        ),
    )
}
