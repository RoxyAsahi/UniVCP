package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.message.RichContentSubtreeRoutePlan
import me.rerere.rikkahub.ui.components.message.RichContentNodeKind
import me.rerere.rikkahub.ui.components.message.countRichRenderBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichSnapshotIslandOptimizerTest {
    @Test
    fun `replaces only candidate subtree with snapshot island`() {
        val model = islandModel()
        val plan = RichSubtreeRoutePlanner.plan(model)

        val optimized = RichSnapshotIslandOptimizer.optimize(model, plan)
        val root = optimized.blocks.single() as RichContainerBlock

        assertTrue(root.children[0] is RichTextBlock)
        assertTrue(root.children[1] is RichSnapshotIslandBlock)
        assertTrue(root.children[2] is RichTextBlock)
        val island = root.children[1] as RichSnapshotIslandBlock
        assertEquals("svg", island.blockId)
        assertEquals(RichSnapshotIslandReason.SvgMask, island.reason)
        assertEquals(RichSnapshotIslandStyleBoundary.NativeWrapper, island.styleBoundary)
        assertTrue(island.sourceHtml.contains("<style>"))
        assertTrue(island.fallbackBlock is RichSvgBlock)
    }

    @Test
    fun `preserves parent container sibling order and native button`() {
        val button = RichButtonBlock(
            blockId = "button",
            style = ComputedStyle.Initial,
            label = AnnotatedString("Go"),
            action = "go",
        )
        val model = islandModel(extraSibling = button)

        val optimized = RichSnapshotIslandOptimizer.optimize(model, RichSubtreeRoutePlanner.plan(model))
        val children = (optimized.blocks.single() as RichContainerBlock).children

        assertTrue(children[1] is RichSnapshotIslandBlock)
        assertEquals(button, children[3])
        assertEquals(1, optimized.snapshotIslandStats.appliedCount)
    }

    @Test
    fun `missing candidate source leaves model unchanged`() {
        val model = islandModel().copy(sourceHtmlByBlockId = emptyMap())
        val optimized = RichSnapshotIslandOptimizer.optimize(model, RichSubtreeRoutePlanner.plan(model))

        assertEquals(model.blocks, optimized.blocks)
        assertEquals(0, optimized.snapshotIslandStats.appliedCount)
    }

    @Test
    fun `ast subtree gate can prevent model only island optimization`() {
        val model = islandModel()
        val modelPlan = RichSubtreeRoutePlanner.plan(model)
        val astPlan = RichContentSubtreeRoutePlan.Empty

        val optimized = RichSnapshotIslandOptimizer.optimize(model, modelPlan, astPlan)

        assertEquals(model.blocks, optimized.blocks)
        assertEquals(0, optimized.snapshotIslandStats.appliedCount)
    }

    @Test
    fun `ast subtree gate prevents applying more islands than canonical document explains`() {
        val model = RichHtmlRenderModel(
            id = "ast-budget",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    children = listOf(svgBlock("s0"), svgBlock("s1")),
                )
            ),
            sourceHtmlByBlockId = mapOf(
                "s0" to """<svg width="120" height="120"><mask id="m0"/></svg>""",
                "s1" to """<svg width="120" height="120"><mask id="m1"/></svg>""",
            ),
        )
        val astPlan = RichContentSubtreeRoutePlan(
            candidateNodeCount = 1,
            rejectedNodeCount = 0,
            rejectReasons = emptySet(),
            nativePreservedActionCount = 0,
            inlineWebViewRequiredCount = 0,
            wholeSnapshotLikely = false,
            candidateStablePaths = setOf("r0/c0"),
            candidateKindCounts = mapOf(RichContentNodeKind.Svg.name to 1),
        )

        val optimized = RichSnapshotIslandOptimizer.optimize(model, RichSubtreeRoutePlanner.plan(model), astPlan)

        assertEquals(model.blocks, optimized.blocks)
        assertEquals(0, optimized.snapshotIslandStats.appliedCount)
    }

    @Test
    fun `interactive candidate leaves model unchanged`() {
        val button = RichButtonBlock(
            blockId = "button",
            style = ComputedStyle.Initial.copy(
                width = RichSize.DpSize(120.dp),
                height = RichSize.DpSize(80.dp),
                cssFilter = RichCssFilter(blurRadius = 4.dp),
            ),
            label = AnnotatedString("Go"),
            action = "go",
        )
        val model = RichHtmlRenderModel(
            id = "interactive",
            blocks = listOf(button),
            sourceHtmlByBlockId = mapOf("button" to """<button style="filter:blur(4px)">Go</button>"""),
        )
        val plan = RichSubtreeRoutePlan(
            rootRoute = RichSubtreeRoute.NativeWithSnapshotIslands,
            candidates = listOf(
                RichSnapshotIslandCandidate(
                    blockId = "button",
                    stablePath = "r0",
                    reason = RichSnapshotIslandReason.CssFilter,
                    estimatedWidthPx = 120,
                    estimatedHeightPx = 80,
                    visualOnly = true,
                )
            ),
            rejected = emptyList(),
            nativePreservedActionCount = 1,
            estimatedWholeSnapshotAvoided = true,
        )

        val optimized = RichSnapshotIslandOptimizer.optimize(model, plan)

        assertEquals(model.blocks, optimized.blocks)
        assertEquals(0, optimized.snapshotIslandStats.appliedCount)
    }

    @Test
    fun `unsafe style boundary rejects candidate`() {
        val visual = RichContainerBlock(
            blockId = "css",
            style = ComputedStyle.Initial.copy(
                width = RichSize.DpSize(160.dp),
                height = RichSize.DpSize(120.dp),
                margin = RichSpacing.all(8.dp),
                cssFilter = RichCssFilter(blurRadius = 4.dp),
            ),
            children = listOf(svgBlock("svg")),
        )
        val model = RichHtmlRenderModel(
            id = "unsafe-boundary",
            blocks = listOf(visual),
            visualHints = listOf(RichVisualHint.CssFilter),
            sourceHtmlByBlockId = mapOf("css" to """<div style="filter:blur(4px);margin:8px"><svg></svg></div>"""),
        )

        val optimized = RichSnapshotIslandOptimizer.optimize(model, RichSubtreeRoutePlanner.plan(model))

        assertEquals(model.blocks, optimized.blocks)
        assertEquals(0, optimized.snapshotIslandStats.appliedCount)
    }

    @Test
    fun `source owned style uses neutral wrapper without duplicate visual metadata`() {
        val visual = RichContainerBlock(
            blockId = "css",
            style = ComputedStyle.Initial.copy(
                width = RichSize.DpSize(160.dp),
                height = RichSize.DpSize(120.dp),
                backgroundColor = androidx.compose.ui.graphics.Color.Red,
                border = RichBorder.all(
                    RichBorderSide(
                        width = 2.dp,
                        color = androidx.compose.ui.graphics.Color.Blue,
                        style = RichBorderStyle.Solid,
                    )
                ),
                borderRadius = RichCornerRadius.all(10.dp),
                cssFilter = RichCssFilter(blurRadius = 4.dp),
            ),
            children = listOf(svgBlock("svg")),
        )
        val model = RichHtmlRenderModel(
            id = "safe-boundary",
            blocks = listOf(visual),
            visualHints = listOf(RichVisualHint.CssFilter),
            sourceHtmlByBlockId = mapOf("css" to """<div style="filter:blur(4px);background:red"><svg></svg></div>"""),
        )

        val optimized = RichSnapshotIslandOptimizer.optimize(model, RichSubtreeRoutePlanner.plan(model))
        val island = optimized.blocks.single() as RichSnapshotIslandBlock

        assertEquals(RichSnapshotIslandStyleBoundary.NeutralWrapper, island.styleBoundary)
        assertEquals(RichBorder.None, island.style.border)
        assertEquals(null, island.style.backgroundColor)
        assertEquals(RichCornerRadius.Zero, island.style.borderRadius)
        assertEquals(RichCssFilter.None, island.style.cssFilter)
        assertEquals(RichSize.DpSize(160.dp), island.style.width)
        assertEquals(RichSize.DpSize(120.dp), island.style.height)
    }

    @Test
    fun `count render blocks understands snapshot island`() {
        val island = RichSnapshotIslandBlock(
            blockId = "island",
            style = ComputedStyle.Initial,
            sourceHtml = "<svg></svg>",
            sourceDigest = "digest",
            reason = RichSnapshotIslandReason.ComplexSvg,
            estimatedHeightPx = 120,
            fallbackBlock = null,
        )

        assertEquals(1, countRichRenderBlocks(island))
    }

    @Test
    fun `text flow optimizer treats island as hard stop`() {
        val optimized = RichSnapshotIslandOptimizer.optimize(islandModel(), RichSubtreeRoutePlanner.plan(islandModel()))
        val afterTextFlow = RichTextFlowOptimizer.optimize(optimized)
        val root = afterTextFlow.blocks.single()

        assertFalse(root is RichTextFlowBlock)
        assertTrue((root as RichContainerBlock).children.any { it is RichSnapshotIslandBlock })
    }

    @Test
    fun `snapshot islands prevent isolated visual hints from deferring whole native presentation`() {
        val heavyVisualHints = listOf(
            RichVisualHint.CssFilter,
            RichVisualHint.CssBackdropFilter,
            RichVisualHint.CssMask,
            RichVisualHint.CssMixBlendMode,
            RichVisualHint.SvgFilter,
            RichVisualHint.SvgMask,
            RichVisualHint.SvgClipPath,
            RichVisualHint.SvgPattern,
            RichVisualHint.SvgUse,
        )
        val island = RichSnapshotIslandBlock(
            blockId = "island",
            style = ComputedStyle.Initial,
            sourceHtml = "<svg></svg>",
            sourceDigest = "digest",
            reason = RichSnapshotIslandReason.SvgFilter,
            estimatedHeightPx = 120,
            fallbackBlock = svgBlock("svg"),
        )
        val islandedModel = RichHtmlRenderModel(
            id = "islanded",
            blocks = listOf(island),
            visualHints = heavyVisualHints,
            snapshotIslandStats = RichSnapshotIslandStats(
                candidateCount = 1,
                appliedCount = 1,
                wholeSnapshotAvoided = true,
            ),
        )
        val nativeModel = islandedModel.copy(
            id = "native",
            blocks = listOf(svgBlock("svg")),
            snapshotIslandStats = RichSnapshotIslandStats.Empty,
        )

        assertTrue(shouldDeferNativePresentationDuringScroll(nativeModel))
        assertFalse(shouldDeferNativePresentationDuringScroll(islandedModel))
    }

    @Test
    fun `compiler does not retain source html for simple native text`() {
        val html = """<div id="vcp-root"><p>${"plain native text ".repeat(80)}</p></div>"""

        val model = RichHtmlCompiler.compile(html)

        assertTrue(model.sourceHtmlByBlockId.isEmpty())
        assertEquals("", model.sourceStyleHtml)
    }

    @Test
    fun `compiler retains source html only for snapshot island candidates`() {
        val html = """
            <div id="vcp-root">
              <p>plain sibling</p>
              <svg width="120" height="120"><mask id="m"></mask><rect width="120" height="120"/></svg>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)

        assertEquals(1, model.sourceHtmlByBlockId.size)
        assertTrue(model.sourceHtmlByBlockId.values.single().contains("<svg"))
        assertFalse(model.sourceHtmlByBlockId.values.any { it.contains("plain sibling") })
    }

    @Test
    fun `whole bubble snapshot over budget is not replaced by islands`() {
        val model = RichHtmlRenderModel(
            id = "budget",
            blocks = listOf(svgBlock("s0"), svgBlock("s1"), svgBlock("s2")),
            sourceHtmlByBlockId = mapOf(
                "s0" to "<svg></svg>",
                "s1" to "<svg></svg>",
                "s2" to "<svg></svg>",
            ),
        )
        val plan = RichSubtreeRoutePlanner.plan(model)

        val optimized = RichSnapshotIslandOptimizer.optimize(model, plan)

        assertEquals(RichSubtreeRoute.WholeSnapshot, plan.rootRoute)
        assertTrue(optimized.blocks.none { it is RichSnapshotIslandBlock })
        assertEquals(0, optimized.snapshotIslandStats.appliedCount)
    }

    private fun islandModel(extraSibling: RichBlock? = null): RichHtmlRenderModel {
        val children = buildList {
            add(RichTextBlock("before", ComputedStyle.Initial, AnnotatedString("Before")))
            add(svgBlock("svg"))
            add(RichTextBlock("after", ComputedStyle.Initial, AnnotatedString("After")))
            extraSibling?.let(::add)
        }
        return RichHtmlRenderModel(
            id = "model",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    children = children,
                )
            ),
            visualHints = listOf(RichVisualHint.SvgMask),
            sourceHtmlByBlockId = mapOf(
                "root" to "<div>...</div>",
                "svg" to """<svg width="120" height="120"><mask id="m"></mask><rect width="120" height="120"/></svg>""",
            ),
            sourceStyleHtml = "<style>.card{color:red}</style>",
        )
    }

    private fun svgBlock(id: String): RichSvgBlock = RichSvgBlock(
        blockId = id,
        style = ComputedStyle.Initial,
        model = RichSvgModel(
            width = 120.dp,
            height = 120.dp,
            viewBox = RichSvgViewBox(0f, 0f, 120f, 120f),
            commands = listOf(
                RichSvgCommand.Rect(
                    x = 0f,
                    y = 0f,
                    width = 120f,
                    height = 120f,
                    rx = 0f,
                    ry = 0f,
                    fill = RichSvgPaint.Solid(androidx.compose.ui.graphics.Color.Black),
                    stroke = null,
                    strokeWidth = 0f,
                )
            ),
            visualHints = listOf(RichVisualHint.SvgMask),
        ),
    )
}
