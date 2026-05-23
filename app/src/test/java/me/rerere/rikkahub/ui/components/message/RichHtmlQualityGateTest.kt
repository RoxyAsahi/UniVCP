package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichAlign
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichDisplay
import me.rerere.rikkahub.ui.components.richtext.RichFlexDirection
import me.rerere.rikkahub.ui.components.richtext.RichFlexWrap
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichSvgBlock
import me.rerere.rikkahub.ui.components.richtext.RichTableBlock
import me.rerere.rikkahub.ui.components.richtext.RichTextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlQualityGateTest {
    @Test
    fun `native static fidelity fixtures compile within quality gates`() {
        nativeFixtures.forEach { fixture ->
            val analysis = analyzeRichHtml(fixture.html)
            assertEquals("${fixture.name} should stay native static", RichHtmlRenderKind.NativeStatic, analysis.kind)

            val started = System.nanoTime()
            val model = RichHtmlCompiler.compile(fixture.html)
            val compileMs = (System.nanoTime() - started) / 1_000_000
            val blocks = model.blocks.flatMap(::flatten)
            val hints = model.visualHints.map { it.name }.toSet()

            assertTrue("${fixture.name} should compile blocks", blocks.isNotEmpty())
            assertTrue("${fixture.name} should keep block budget, got ${blocks.size}", blocks.size <= fixture.maxBlocks)
            assertTrue("${fixture.name} compile budget exceeded: ${compileMs}ms", compileMs <= fixture.maxCompileMs)
            assertTrue("${fixture.name} unsupported: ${model.unsupported}", model.unsupported.isEmpty())
            fixture.expectedHints.forEach { expected ->
                assertTrue("${fixture.name} missing hint $expected in $hints", hints.contains(expected))
            }
            fixture.assertions(blocks)
            assertMetadataOnly(fixture, "id=${model.id} hints=$hints blocks=${blocks.size} compileMs=$compileMs")
        }
    }

    @Test
    fun `dynamic and unsafe fixtures never enter native static execution`() {
        unsafeFixtures.forEach { fixture ->
            val analysis = analyzeRichHtml(fixture.html)
            val safety = inspectRichHtmlSafety(fixture.html)

            assertFalse("${fixture.name} should not be safe for native", safety.safeForNative)
            assertEquals("${fixture.name} should be dynamic fallback", RichHtmlRenderKind.ComplexDynamic, analysis.kind)
        }
    }

    private fun assertMetadataOnly(fixture: FidelityFixture, metadata: String) {
        fixture.forbiddenText.forEach { forbidden ->
            assertFalse("${fixture.name} leaked body text: $forbidden", metadata.contains(forbidden))
        }
    }

    private fun flatten(block: RichBlock): List<RichBlock> = when (block) {
        is RichContainerBlock -> listOf(block) + block.children.flatMap(::flatten)
        else -> listOf(block)
    }

    private data class FidelityFixture(
        val name: String,
        val html: String,
        val expectedHints: Set<String> = emptySet(),
        val forbiddenText: Set<String> = emptySet(),
        val maxCompileMs: Long = 1_500,
        val maxBlocks: Int = 160,
        val assertions: (List<RichBlock>) -> Unit,
    )

    private val nativeFixtures = listOf(
        FidelityFixture(
            name = "neon glass visual card",
            html = """
                <div id="response-root" style="background:#050505;color:#fff;padding:40px 20px;border-radius:24px;position:relative;overflow:hidden;min-height:500px;">
                  <style>
                    .bg-orb{position:absolute;border-radius:50%;filter:blur(60px);z-index:0;opacity:.4;}
                    .glass-bubble{position:relative;z-index:1;background:rgba(255,255,255,.03);backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,.1);border-radius:24px;padding:20px;margin-bottom:20px;max-width:80%;}
                    .gradient-text{background:linear-gradient(90deg,#818cf8,#c084fc,#fb7185);background-clip:text;-webkit-text-fill-color:transparent;font-weight:800;}
                  </style>
                  <div class="bg-orb" style="width:300px;height:300px;background:#4338ca;top:-100px;right:-50px;"></div>
                  <h1 class="gradient-text">Uika's Rendering Test</h1>
                  <div style="display:flex;flex-direction:column;align-items:flex-start;">
                    <div class="glass-bubble">Glassmorphism bubble</div>
                    <div class="glass-bubble" style="align-self:flex-end;border-left:4px solid #818cf8;">Flexbox bubble</div>
                  </div>
                </div>
            """.trimIndent(),
            expectedHints = setOf("CssFilter", "CssBackdropFilter", "CssBackgroundClipText"),
            forbiddenText = setOf("Glassmorphism bubble", "Flexbox bubble"),
        ) { blocks ->
            val containers = blocks.filterIsInstance<RichContainerBlock>()
            assertTrue(
                "expected absolute decorative container, got ${
                    containers.joinToString { "${it.tagName}:${it.style.position}:${it.style.width}:${it.style.height}" }
                }",
                containers.any { it.style.position.name == "Absolute" },
            )
            assertTrue(blocks.filterIsInstance<RichTextBlock>().any { it.style.backgroundClip.name == "Text" })
            assertTrue(blocks.filterIsInstance<RichContainerBlock>().any { it.style.display == RichDisplay.Flex })
        },
        FidelityFixture(
            name = "flex and grid layout",
            html = """
                <div id="vcp-root">
                  <div style="display:flex;flex-flow:row-reverse wrap-reverse;gap:10px 12px;justify-content:space-between;align-items:baseline;align-content:stretch;">
                    <span style="flex:1 1 120px;order:2;align-self:baseline;max-width:80%;">A</span>
                    <span style="width:40%;flex-shrink:0;order:1;">B</span>
                  </div>
                  <div style="display:grid;grid-template-columns:repeat(4,1fr);gap:8px;">
                    <span style="grid-column:2 / 5;grid-row:span 2;">Grid Span</span>
                  </div>
                </div>
            """.trimIndent(),
        ) { blocks ->
            val flex = blocks.filterIsInstance<RichContainerBlock>().first { it.style.display == RichDisplay.Flex }
            val grid = blocks.filterIsInstance<RichContainerBlock>().first { it.style.display == RichDisplay.Grid }
            assertEquals(RichFlexDirection.RowReverse, flex.style.flexDirection)
            assertEquals(RichFlexWrap.WrapReverse, flex.style.flexWrap)
            assertEquals(10f, flex.style.rowGap.value, 0.01f)
            assertEquals(12f, flex.style.columnGap.value, 0.01f)
            assertEquals(RichAlign.Baseline, flex.style.alignItems)
            assertTrue(flex.children.any { it.style.alignSelf == RichAlign.Baseline })
            assertTrue(grid.children.any { it.style.gridColumnSpan == 3 && it.style.gridRowSpan == 2 })
        },
        FidelityFixture(
            name = "table fidelity",
            html = """
                <div id="vcp-root">
                  <table style="border-collapse:collapse;caption-side:bottom;">
                    <caption style="text-align:right;color:#818cf8;">Quarterly Stats</caption>
                    <thead><tr><th colspan="2" style="background:#111827;color:white;">Metric</th></tr></thead>
                    <tbody><tr><td rowspan="2" style="border:1px solid #334155;padding:6px;">Revenue</td><td>42</td></tr><tr><td>84</td></tr></tbody>
                    <tfoot><tr><td colspan="2">Total</td></tr></tfoot>
                  </table>
                </div>
            """.trimIndent(),
        ) { blocks ->
            val table = blocks.filterIsInstance<RichTableBlock>().single()
            assertEquals("Quarterly Stats", table.caption?.text)
            assertEquals(3, table.sections.size)
            assertEquals(2, table.sections.first().rows.first().first().colspan)
            assertEquals(2, table.sections[1].rows.first().first().rowspan)
        },
        FidelityFixture(
            name = "svg fidelity",
            html = """
                <div id="vcp-root">
                  <svg width="120" height="80" viewBox="0 0 120 80">
                    <defs>
                      <radialGradient id="rg"><stop offset="0%" stop-color="skyblue"/><stop offset="100%" stop-color="#0f172a"/></radialGradient>
                    </defs>
                    <g fill="url(#rg)" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-dasharray="4 2">
                      <circle cx="30" cy="30" r="20"/>
                      <polyline points="60,10 90,30 60,50"/>
                    </g>
                    <text x="60" y="70" text-anchor="middle" font-weight="700">OK</text>
                  </svg>
                </div>
            """.trimIndent(),
        ) { blocks ->
            val svg = blocks.filterIsInstance<RichSvgBlock>().single()
            assertTrue(svg.model.commands.size >= 3)
            assertTrue(svg.model.visualHints.isEmpty())
        },
    )

    private val unsafeFixtures = listOf(
        UnsafeFixture("script", """<div id="vcp-root"><script>alert(1)</script></div>"""),
        UnsafeFixture("iframe", """<div id="vcp-root"><iframe src="https://example.com"></iframe></div>"""),
        UnsafeFixture("javascript url", """<div id="vcp-root"><a href="javascript:alert(1)">bad</a></div>"""),
        UnsafeFixture("canvas runtime", """<div id="response-root"><canvas></canvas></div>"""),
    )

    private data class UnsafeFixture(
        val name: String,
        val html: String,
    )
}
