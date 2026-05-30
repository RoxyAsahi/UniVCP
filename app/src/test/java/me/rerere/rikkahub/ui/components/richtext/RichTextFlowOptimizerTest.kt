package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.message.RichContentLoweringMode
import me.rerere.rikkahub.ui.components.message.RichContentTextFlowPlan
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.buildRichContentDocumentFromMarkdown
import me.rerere.rikkahub.ui.components.message.buildRichRenderPlan
import me.rerere.rikkahub.ui.components.message.countRichRenderBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextFlowOptimizerTest {
    @Test
    fun `simple paragraph model converts to text flow block`() {
        val model = textContainerModel(
            RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("First")),
            RichTextBlock("t1", ComputedStyle.Initial, AnnotatedString("Second")),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val flow = optimized.blocks.single() as RichTextFlowBlock

        assertEquals("root-flow", flow.blockId)
        assertEquals(2, flow.paragraphs.size)
        assertTrue(countRichRenderBlocks(flow) < countRichRenderBlocks(model.blocks.single()))
    }

    @Test
    fun `span heavy text reduces block count`() {
        val model = textContainerModel(
            RichTextBlock("s0", ComputedStyle.Initial, AnnotatedString("A")),
            RichTextBlock("s1", ComputedStyle.Initial, AnnotatedString("B")),
            RichTextBlock("s2", ComputedStyle.Initial, AnnotatedString("C")),
        )

        val before = model.blocks.sumOf(::countRichRenderBlocks)
        val optimized = RichTextFlowOptimizer.optimize(model)
        val after = optimized.blocks.sumOf(::countRichRenderBlocks)

        assertTrue(optimized.blocks.single() is RichTextFlowBlock)
        assertTrue(after < before)
    }

    @Test
    fun `ast text flow gate can prevent model-only flattening`() {
        val model = textContainerModel(
            RichTextBlock("s0", ComputedStyle.Initial, AnnotatedString("A")),
            RichTextBlock("s1", ComputedStyle.Initial, AnnotatedString("B")),
        )

        val skipped = RichTextFlowOptimizer.optimize(model, RichContentTextFlowPlan.Empty)
        val allowed = RichTextFlowOptimizer.optimize(
            model,
            RichContentTextFlowPlan(
                eligibleNodeCount = 2,
                hardStopNodeCount = 0,
                hardStopReasons = emptySet(),
            )
        )

        assertFalse(skipped.blocks.single() is RichTextFlowBlock)
        assertTrue(allowed.blocks.single() is RichTextFlowBlock)
    }

    @Test
    fun `compiler can lower simple ast text directly to text flow`() {
        val secret = "ast-direct-secret"
        val html = """
                <div id="vcp-root">
                  <p>$secret first paragraph</p>
                  <p>second paragraph</p>
                </div>
            """.trimIndent()
        val model = RichHtmlCompiler.compile(html)
        val flow = model.blocks.single() as RichTextFlowBlock

        assertEquals(RichContentLoweringMode.AstDirect, model.textFlowLoweringMode)
        assertEquals(2, flow.paragraphs.size)
        assertTrue(flow.paragraphs.all { it.content.text.isNotBlank() })

        RichHtmlRenderTelemetry.resetForTest()
        val plan = buildRichRenderPlan(
            html = html,
            model = model,
        )
        RichHtmlRenderTelemetry.recordRichRenderPlan(plan)
        val serialized = plan.toMetadataLine() + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanSnapshot().joinToString("\n") + "\n" +
            RichHtmlRenderTelemetry.richRenderPlanDebugSummary()
        assertTrue(serialized.contains("transformTextFlowLowering=AstDirect"))
        assertFalse(serialized.contains(secret))
    }

    @Test
    fun `ast direct text flow lowers from canonical document without html reparse`() {
        val document = buildRichContentDocumentFromMarkdown("First paragraph\n\nSecond paragraph")
        val model = textContainerModel(
            RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("First paragraph")),
            RichTextBlock("t1", ComputedStyle.Initial, AnnotatedString("Second paragraph")),
        )

        val lowered = RichTextFlowAstLowerer.lower(
            document = document,
            model = model,
            html = "",
            parsedHtml = null,
        )
        val flow = lowered?.blocks?.single() as RichTextFlowBlock

        assertEquals(RichContentLoweringMode.AstDirect, lowered.textFlowLoweringMode)
        assertEquals(2, flow.paragraphs.size)
        assertEquals("First paragraph", flow.paragraphs[0].content.text)
        assertEquals("Second paragraph", flow.paragraphs[1].content.text)
    }

    @Test
    fun `document only markdown text flow preserves simple list markers`() {
        val document = buildRichContentDocumentFromMarkdown(
            """
                First paragraph with **bold** text

                - First item
                - Second item
            """.trimIndent()
        )

        val lowered = RichTextFlowAstLowerer.lowerDocumentOnly(document)
        val flow = lowered?.blocks?.single() as RichTextFlowBlock

        assertEquals(RichContentLoweringMode.AstDirect, lowered.textFlowLoweringMode)
        assertEquals(3, flow.paragraphs.size)
        assertEquals("First paragraph with bold text", flow.paragraphs[0].content.text)
        assertEquals("- First item", flow.paragraphs[1].content.text)
        assertEquals("- Second item", flow.paragraphs[2].content.text)
        assertTrue(flow.paragraphs[0].content.spanStyles.isNotEmpty())
    }

    @Test
    fun `ast direct text flow preserves canonical mark ranges`() {
        val html = """
                <div id="vcp-root">
                  <p><strong>Bold</strong> <em>Italic</em> <code>Code</code></p>
                  <p>Second paragraph</p>
                </div>
            """.trimIndent()
        val model = RichHtmlCompiler.compile(html)
        val flow = model.blocks.single() as RichTextFlowBlock
        val first = flow.paragraphs.first().content

        assertEquals(RichContentLoweringMode.AstDirect, model.textFlowLoweringMode)
        assertTrue(first.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        assertTrue(first.spanStyles.any { it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertTrue(first.spanStyles.any { it.item.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace })
    }

    @Test
    fun `ast direct text flow refuses actions and keeps native action model`() {
        val model = RichHtmlCompiler.compile(
            """<div id="vcp-root"><p>Intro</p><button data-send="go">Go</button></div>"""
        )

        assertFalse(model.textFlowLoweringMode == RichContentLoweringMode.AstDirect)
        assertTrue(model.blocks.any { block -> block.containsButtonForTest() })
    }

    @Test
    fun `button containing model does not flatten button to text`() {
        val model = RichHtmlRenderModel(
            id = "button",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    tagName = "div",
                    children = listOf(
                        RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("Intro")),
                        RichButtonBlock("b0", ComputedStyle.Initial, AnnotatedString("Go"), action = "go"),
                    ),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)

        assertFalse(optimized.blocks.single() is RichTextFlowBlock)
        assertTrue((optimized.blocks.single() as RichContainerBlock).children.any { it is RichButtonBlock })
    }

    @Test
    fun `table svg image and details models are not flattened`() {
        val cases = listOf(
            """<div id="vcp-root"><p>A</p><table><tr><td>B</td></tr></table></div>""",
            """<div id="vcp-root"><p>A</p><svg><path d="M0 0L1 1"/></svg></div>""",
            """<div id="vcp-root"><p>A</p><img src="https://example.com/a.png"></div>""",
            """<div id="vcp-root"><p>A</p><details><summary>B</summary><p>C</p></details></div>""",
        )

        cases.forEach { html ->
            val model = RichHtmlCompiler.compile(html)
            assertFalse(model.blocks.anyTextFlowBlock())
        }
    }

    @Test
    fun `visual heavy container is not flattened`() {
        val model = RichHtmlRenderModel(
            id = "visual",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial.copy(backgroundColor = Color.Red),
                    tagName = "div",
                    children = listOf(RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("Visual"))),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)

        assertFalse(optimized.blocks.single() is RichTextFlowBlock)
    }

    @Test
    fun `visual parent keeps wrapper but groups safe text children`() {
        val model = RichHtmlRenderModel(
            id = "visual-child-flow",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial.copy(backgroundColor = Color.Red),
                    tagName = "div",
                    children = listOf(
                        RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("First")),
                        RichTextBlock("t1", ComputedStyle.Initial, AnnotatedString("Second")),
                    ),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val root = optimized.blocks.single() as RichContainerBlock
        val flow = root.children.single() as RichTextFlowBlock

        assertEquals(Color.Red, root.style.backgroundColor)
        assertEquals(listOf("First", "Second"), flow.paragraphs.map { it.content.text })
    }

    @Test
    fun `nested neutral text wrappers flatten without losing inline style`() {
        val model = RichHtmlRenderModel(
            id = "nested-text-wrapper",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    tagName = "div",
                    children = listOf(
                        RichContainerBlock(
                            blockId = "span",
                            style = ComputedStyle.Initial.copy(color = Color.Red),
                            tagName = "span",
                            children = listOf(
                                RichTextBlock(
                                    blockId = "span-t0",
                                    style = ComputedStyle.Initial.copy(fontWeight = FontWeight.Bold),
                                    content = AnnotatedString("Styled"),
                                )
                            ),
                        )
                    ),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val flow = optimized.blocks.single() as RichTextFlowBlock
        val paragraph = flow.paragraphs.single()

        assertEquals("Styled", paragraph.content.text)
        assertTrue(paragraph.content.spanStyles.any { it.item.color == Color.Red })
        assertEquals(FontWeight.Bold, paragraph.style.fontWeight)
    }

    @Test
    fun `nested text wrapper with box spacing keeps box style outside inline paragraph`() {
        val model = RichHtmlRenderModel(
            id = "nested-box-wrapper",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    tagName = "div",
                    children = listOf(
                        RichContainerBlock(
                            blockId = "span",
                            style = ComputedStyle.Initial.copy(padding = RichSpacing.all(4.dp)),
                            tagName = "span",
                            children = listOf(
                                RichTextBlock(
                                    blockId = "span-t0",
                                    style = ComputedStyle.Initial,
                                    content = AnnotatedString("Padded"),
                                )
                            ),
                        )
                    ),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val root = optimized.blocks.single() as RichContainerBlock
        val childFlow = root.children.single() as RichTextFlowBlock

        assertFalse(optimized.blocks.single() is RichTextFlowBlock)
        assertEquals(RichSpacing.all(4.dp), childFlow.style.padding)
        assertEquals("Padded", childFlow.paragraphs.single().content.text)
    }

    @Test
    fun `nested text wrapper with paragraph semantics keeps wrapper text flow block`() {
        val model = RichHtmlRenderModel(
            id = "nested-paragraph-wrapper",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    tagName = "div",
                    children = listOf(
                        RichContainerBlock(
                            blockId = "span",
                            style = ComputedStyle.Initial.copy(whiteSpace = RichWhiteSpace.NoWrap),
                            tagName = "span",
                            children = listOf(
                                RichTextBlock(
                                    blockId = "span-t0",
                                    style = ComputedStyle.Initial,
                                    content = AnnotatedString("No wrap"),
                                )
                            ),
                        )
                    ),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val root = optimized.blocks.single() as RichContainerBlock
        val childFlow = root.children.single() as RichTextFlowBlock

        assertEquals(RichWhiteSpace.NoWrap, childFlow.style.whiteSpace)
        assertEquals("No wrap", childFlow.paragraphs.single().content.text)
    }

    @Test
    fun `child text flow grouping does not cross actions`() {
        val model = RichHtmlRenderModel(
            id = "action-child-flow",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial.copy(backgroundColor = Color.Red),
                    tagName = "div",
                    children = listOf(
                        RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("Intro one")),
                        RichTextBlock("t1", ComputedStyle.Initial, AnnotatedString("Intro two")),
                        RichButtonBlock("b0", ComputedStyle.Initial, AnnotatedString("Go"), action = "go"),
                        RichTextBlock("t2", ComputedStyle.Initial, AnnotatedString("Outro one")),
                        RichTextBlock("t3", ComputedStyle.Initial, AnnotatedString("Outro two")),
                    ),
                )
            ),
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val children = (optimized.blocks.single() as RichContainerBlock).children

        assertTrue(children[0] is RichTextFlowBlock)
        assertTrue(children[1] is RichButtonBlock)
        assertTrue(children[2] is RichTextFlowBlock)
        assertEquals(listOf("Intro one", "Intro two"), (children[0] as RichTextFlowBlock).paragraphs.map { it.content.text })
        assertEquals(listOf("Outro one", "Outro two"), (children[2] as RichTextFlowBlock).paragraphs.map { it.content.text })
    }

    @Test
    fun `list image markers are not flattened and report blocker`() {
        val model = textContainerModel(
            RichTextBlock(
                blockId = "li0",
                style = ComputedStyle.Initial.copy(listStyleImage = "https://example.com/marker.png"),
                content = AnnotatedString("Item"),
                listMarker = "• ",
            )
        )

        val optimized = RichTextFlowOptimizer.optimize(model)
        val inspection = RichTextFlowOptimizer.inspect(optimized)

        assertFalse(optimized.blocks.single() is RichTextFlowBlock)
        assertTrue(inspection.blockedReasons.contains(RichTextFlowBlockedReason.ListImageMarker))
    }

    @Test
    fun `inspection reports applied and blocked reason categories`() {
        val applied = RichTextFlowOptimizer.optimize(
            textContainerModel(
                RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("First")),
                RichTextBlock("t1", ComputedStyle.Initial, AnnotatedString("Second")),
            )
        )
        val blocked = RichHtmlRenderModel(
            id = "blocked",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    tagName = "div",
                    children = listOf(
                        RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("Intro")),
                        RichButtonBlock("b0", ComputedStyle.Initial, AnnotatedString("Go"), action = "go"),
                    ),
                )
            ),
        )

        assertEquals(1, RichTextFlowOptimizer.inspect(applied).appliedCount)
        assertTrue(
            RichTextFlowOptimizer.inspect(blocked)
                .blockedReasons
                .contains(RichTextFlowBlockedReason.Action)
        )
    }

    @Test
    fun `inspection counts inline features preserved inside text flow`() {
        val styled = AnnotatedString(
            text = "Styled math",
            spanStyles = listOf(
                AnnotatedString.Range(
                    item = SpanStyle(color = Color.Red),
                    start = 0,
                    end = 6,
                )
            ),
        )
        val optimized = RichTextFlowOptimizer.optimize(
            textContainerModel(
                RichTextBlock(
                    blockId = "t0",
                    style = ComputedStyle.Initial,
                    content = styled,
                    inlineMath = listOf(InlineMathRun(start = 7, end = 11, latex = "x^2")),
                    listMarker = "1. ",
                )
            )
        )

        val inspection = RichTextFlowOptimizer.inspect(optimized)

        assertEquals(1, inspection.appliedCount)
        assertTrue(inspection.inlineFeaturePreservedCount >= 3)
    }

    @Test
    fun `optimized model keeps id traceable and unsupported reasons`() {
        val model = textContainerModel(
            RichTextBlock("t0", ComputedStyle.Initial, AnnotatedString("First")),
            RichTextBlock("t1", ComputedStyle.Initial, AnnotatedString("Second")),
        )
        val optimized = RichTextFlowOptimizer.optimize(model)

        assertEquals(model.id, optimized.id)

        val unsupported = model.copy(unsupported = listOf(RichUnsupportedReason.Unknown))
        val skipped = RichTextFlowOptimizer.optimize(unsupported)

        assertEquals(listOf(RichUnsupportedReason.Unknown), skipped.unsupported)
        assertFalse(skipped.blocks.single() is RichTextFlowBlock)
    }

    @Test
    fun `countRichRenderBlocks understands text flow block`() {
        val block = RichTextFlowBlock(
            blockId = "flow",
            style = ComputedStyle.Initial,
            paragraphs = listOf(
                RichTextFlowParagraph(AnnotatedString("A"), ComputedStyle.Initial),
                RichTextFlowParagraph(AnnotatedString("B"), ComputedStyle.Initial),
            ),
        )

        assertEquals(1, countRichRenderBlocks(block))
    }

    private fun textContainerModel(vararg blocks: RichTextBlock): RichHtmlRenderModel {
        return RichHtmlRenderModel(
            id = "text",
            blocks = listOf(
                RichContainerBlock(
                    blockId = "root",
                    style = ComputedStyle.Initial,
                    tagName = "div",
                    children = blocks.toList(),
                )
            ),
        )
    }
}

private fun List<RichBlock>.anyTextFlowBlock(): Boolean {
    return any { block ->
        when (block) {
            is RichTextFlowBlock -> true
            is RichContainerBlock -> block.children.anyTextFlowBlock()
            is RichButtonBlock -> block.children.anyTextFlowBlock() ||
                block.inlineBoxes.any { listOf(it.block).anyTextFlowBlock() }
            is RichDetailsBlock -> block.children.anyTextFlowBlock()
            is RichTextBlock -> block.inlineBoxes.any { listOf(it.block).anyTextFlowBlock() }
            is RichImageBlock,
            is RichMathBlock,
            is RichSvgBlock,
            is RichTableBlock,
            is RichSnapshotIslandBlock,
            is RichUnsupportedBlock -> false
        }
    }
}

private fun RichBlock.containsButtonForTest(): Boolean {
    return when (this) {
        is RichButtonBlock -> true
        is RichContainerBlock -> children.any { it.containsButtonForTest() }
        is RichDetailsBlock -> children.any { it.containsButtonForTest() }
        is RichTextBlock -> inlineBoxes.any { it.block.containsButtonForTest() }
        is RichTextFlowBlock,
        is RichImageBlock,
        is RichMathBlock,
        is RichSnapshotIslandBlock,
        is RichSvgBlock,
        is RichTableBlock,
        is RichUnsupportedBlock -> false
    }
}
