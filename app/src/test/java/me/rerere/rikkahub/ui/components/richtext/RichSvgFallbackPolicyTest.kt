package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSvgFallbackPolicyTest {
    @Test
    fun `simple svg uses native route`() {
        val report = RichSvgFallbackPolicy.decide(simpleSvg())

        assertEquals(RichSvgRoute.NativeIr, report.route)
        assertEquals("simple-native-ir", report.reason)
    }

    @Test
    fun `complex static svg uses snapshot island until android svg spike is available`() {
        val report = RichSvgFallbackPolicy.decide(
            simpleSvg(
                visualHints = listOf(RichVisualHint.SvgFilter),
            )
        )

        assertEquals(RichSvgRoute.SnapshotIsland, report.route)
        assertEquals("complex-static-snapshot", report.reason)
        assertFalse(report.androidSvgAvailable)
    }

    @Test
    fun `runtime svg is rejected to dynamic preview`() {
        val report = RichSvgFallbackPolicy.decide(simpleSvg(), sourceHtml = "<svg><foreignObject></foreignObject></svg>")

        assertEquals(RichSvgRoute.DynamicPreview, report.route)
    }

    @Test
    fun `compiler lowers runtime svg to dynamic runtime unsupported block`() {
        RichHtmlCompiler.clearCacheForTest()
        val html = """
            <div id="vcp-root">
              <svg width="120" height="80">
                <foreignObject width="120" height="80"><div>browser only</div></foreignObject>
              </svg>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)

        assertTrue(model.unsupported.contains(RichUnsupportedReason.DynamicRuntime))
        assertTrue(model.blocks.any { it.containsUnsupported(RichUnsupportedReason.DynamicRuntime) })
    }

    private fun simpleSvg(visualHints: List<RichVisualHint> = emptyList()): RichSvgModel {
        return RichSvgModel(
            width = 120.dp,
            height = 80.dp,
            viewBox = RichSvgViewBox(0f, 0f, 120f, 80f),
            commands = listOf(
                RichSvgCommand.Rect(
                    x = 0f,
                    y = 0f,
                    width = 120f,
                    height = 80f,
                    rx = 0f,
                    ry = 0f,
                    fill = RichSvgPaint.Solid(Color.Red),
                    stroke = null,
                    strokeWidth = 0f,
                )
            ),
            visualHints = visualHints,
        )
    }

    private fun RichBlock.containsUnsupported(reason: RichUnsupportedReason): Boolean = when (this) {
        is RichUnsupportedBlock -> this.reason == reason
        is RichContainerBlock -> children.any { it.containsUnsupported(reason) }
        is RichButtonBlock -> children.any { it.containsUnsupported(reason) } ||
            inlineBoxes.any { it.block.containsUnsupported(reason) }
        is RichDetailsBlock -> children.any { it.containsUnsupported(reason) }
        is RichTextBlock -> inlineBoxes.any { it.block.containsUnsupported(reason) }
        is RichSnapshotIslandBlock -> fallbackBlock?.containsUnsupported(reason) == true
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichMathBlock,
        is RichSvgBlock,
        is RichTableBlock -> false
    }
}
