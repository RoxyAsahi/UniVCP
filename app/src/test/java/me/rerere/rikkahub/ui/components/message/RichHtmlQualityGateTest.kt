package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichAlign
import me.rerere.rikkahub.ui.components.richtext.RichBackgroundImage
import me.rerere.rikkahub.ui.components.richtext.RichButtonBlock
import me.rerere.rikkahub.ui.components.richtext.RichClipPath
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichDisplay
import me.rerere.rikkahub.ui.components.richtext.RichFlexDirection
import me.rerere.rikkahub.ui.components.richtext.RichFlexWrap
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichJustify
import me.rerere.rikkahub.ui.components.richtext.RichSize
import me.rerere.rikkahub.ui.components.richtext.RichSvgBlock
import me.rerere.rikkahub.ui.components.richtext.RichSvgCommand
import me.rerere.rikkahub.ui.components.richtext.RichTableBlock
import me.rerere.rikkahub.ui.components.richtext.RichTextBlock
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `background clip text keeps gradient out of the text box background`() {
        val html = """
            <div id="response-root" style="position:relative; overflow:hidden; padding:28px 26px; border-radius:24px; background:linear-gradient(135deg,#0f172a 0%,#111827 46%,#1e1b4b 100%); color:#fff;">
              <style>
                #response-root .title {
                  position:relative;
                  z-index:1;
                  margin:0;
                  font-size:clamp(34px, 7vw, 64px);
                  line-height:1.05;
                  font-weight:900;
                  background:linear-gradient(90deg,#38bdf8,#a78bfa,#f472b6,#facc15);
                  background-size:260% 100%;
                  -webkit-background-clip:text;
                  background-clip:text;
                  color:transparent;
                }
              </style>
              <h1 class="title">渐变色大标题</h1>
            </div>
        """.trimIndent()

        val blocks = RichHtmlCompiler.compile(html).blocks.flatMap(::flatten)
        val title = blocks.filterIsInstance<RichTextBlock>().single { it.content.text == "渐变色大标题" }

        assertTrue(title.style.backgroundImage is RichBackgroundImage.LinearGradient)
        assertEquals("Text", title.style.backgroundClip.name)
        assertNull("text-clipped gradients must not paint the whole text box", title.style.backgroundColor)
        assertEquals(34f, title.style.fontSize.value, 0.01f)
    }

    @Test
    fun `safe css painting subset compiles into native style model`() {
        val html = """
            <div id="response-root">
              <img src="data:image/png;base64,iVBORw0KGgo=" style="width:64px;height:64px;clip-path:circle(50% at center);" />
              <div style="height:80px;mask-image:linear-gradient(to bottom,#000 0%,transparent 100%);background:linear-gradient(180deg,#38bdf8,#0f172a);">Fade</div>
              <div style="background:linear-gradient(90deg,#fff,#ddd),url(data:image/png;base64,iVBORw0KGgo=);backdrop-filter:blur(8px) brightness(1.1);">Glass</div>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val blocks = model.blocks.flatMap(::flatten)
        val debug = blocks.joinToString { block ->
            "${block::class.simpleName}(clip=${block.style.clipPath},mask=${block.style.maskImage},backdrop=${block.style.backdropFilter})"
        }
        val avatar = blocks.firstOrNull { it.style.clipPath is RichClipPath.Circle }
        val masked = blocks.firstOrNull { it.style.maskImage is RichBackgroundImage.LinearGradient }
        val glass = blocks.firstOrNull { it.style.backdropFilter.blurRadius != null }

        assertTrue("expected circle clip path in $debug", avatar?.style?.clipPath is RichClipPath.Circle)
        assertTrue("expected gradient mask in $debug", masked?.style?.maskImage is RichBackgroundImage.LinearGradient)
        assertEquals(1, glass?.style?.extraBackgroundLayers)
        assertEquals(2, glass?.style?.backgroundLayers?.size)
        assertTrue(glass?.style?.backgroundLayers?.firstOrNull()?.image is RichBackgroundImage.LinearGradient)
        assertTrue(glass?.style?.backgroundLayers?.any { it.image is RichBackgroundImage.LinearGradient } == true)
        assertTrue(glass?.style?.backgroundLayers?.any { it.url?.startsWith("data:image/png") == true } == true)
        assertTrue(model.unsupported.isEmpty())
    }

    @Test
    fun `inline flex badge is preserved as shrinkable native container`() {
        val html = """
            <div id="vcp-root" style="padding:12px;background:#fff;">
              <span class="sync-badge" style="display:inline-flex;align-items:center;border-radius:999px;padding:4px 12px;background:#dff4ff;color:#0f7490;font-weight:700;">记忆同步成功</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val inlineFlow = root.children.single() as RichTextBlock
        val inlineBox = inlineFlow.inlineBoxes.single()
        val badge = inlineBox.block as RichContainerBlock
        val label = badge.children.single() as RichTextBlock

        assertEquals("\uFFFC", inlineFlow.content.text)
        assertEquals(RichDisplay.InlineFlex, badge.style.display)
        assertEquals(12f, badge.style.padding.left.value, 0.01f)
        assertTrue("badge label should not duplicate the badge box padding", label.style.padding.valueSum() == 0f)
        assertNull("anonymous badge text should not repaint the badge background", label.style.backgroundColor)
    }

    @Test
    fun `inline flex badge stays in surrounding text flow`() {
        val html = """
            <div id="vcp-root">
              <p>状态 <span style="display:inline-flex;align-items:center;border-radius:999px;padding:4px 12px;background:#dff4ff;color:#0f7490;font-weight:700;">记忆同步成功</span> 完成</p>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = root.children.single() as RichTextBlock
        val inlineBox = text.inlineBoxes.single()
        val badge = inlineBox.block as RichContainerBlock

        assertEquals("状态 \uFFFC 完成", text.content.text)
        assertEquals(3, inlineBox.start)
        assertEquals(4, inlineBox.end)
        assertEquals(RichDisplay.InlineFlex, badge.style.display)
        assertEquals(12f, badge.style.padding.left.value, 0.01f)
        assertTrue("inline badge placeholder should reserve shrink-to-content width", inlineBox.width.value in 90f..150f)
    }

    @Test
    fun `inline rounded background span becomes native inline box`() {
        val html = """
            <div id="vcp-root">
              <p>底部 <span style="border-radius:10px;padding:2px 6px;background:#ffe4f0;">♥ 已收藏</span></p>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = root.children.single() as RichTextBlock
        val inlineBox = text.inlineBoxes.single()
        val chip = inlineBox.block as RichContainerBlock

        assertEquals("底部 \uFFFC", text.content.text)
        assertEquals(RichDisplay.Inline, chip.style.display)
        assertEquals(6f, chip.style.padding.left.value, 0.01f)
        assertTrue("rounded inline background should not be flattened into plain SpanStyle", chip.style.borderRadius != me.rerere.rikkahub.ui.components.richtext.RichCornerRadius.Zero)
    }

    @Test
    fun `inline gradient highlighter becomes native text paint run`() {
        val html = """
            <div id="vcp-root">
              <p>关于<span style="background:linear-gradient(180deg, transparent 58%, rgba(255,105,180,.45) 58%, rgba(255,105,180,.45) 100%);">权限边界与GUI原理</span>的讨论</p>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = root.children.single() as RichTextBlock
        val paint = text.inlinePaints.single()

        assertEquals("关于权限边界与GUI原理的讨论", text.content.text)
        assertEquals(2, paint.start)
        assertEquals(12, paint.end)
        assertTrue("highlighter should paint the lower part of the line", paint.topFraction > 0.5f)
    }

    @Test
    fun `inline bottom border underline does not become clipping placeholder`() {
        val html = """
            <div id="vcp-root">
              <p>脚本<span style="border-bottom:2px solid #f8a5bd;">魔法</span>来间接实现它</p>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = root.children.single() as RichTextBlock
        val paint = text.inlinePaints.single()

        assertEquals("脚本魔法来间接实现它", text.content.text)
        assertTrue("decorative underline should stay in the Text flow", text.inlineBoxes.isEmpty())
        assertEquals(2, paint.start)
        assertEquals(4, paint.end)
    }

    @Test
    fun `inline paint survives sibling inline box placeholders`() {
        val html = """
            <div id="vcp-root">
              <p>关于<span style="background:linear-gradient(180deg, transparent 58%, rgba(255,105,180,.45) 58%, rgba(255,105,180,.45) 100%);">权限边界</span> <span style="border-radius:20px;padding:5px 15px;background:linear-gradient(45deg,#4facfe,#00f2fe);color:white;font-weight:bold;">ScreenPilot</span></p>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = root.children.single() as RichTextBlock

        assertEquals(1, text.inlinePaints.size)
        assertEquals(1, text.inlineBoxes.size)
        assertEquals("关于权限边界 \uFFFC", text.content.text)
    }

    @Test
    fun `button labels preserve rich inline content`() {
        val html = """
            <div id="vcp-root">
              <button onclick="input('go')" style="border-radius:24px;padding:8px 16px;background:#111;color:white;">
                运行 <span style="border-radius:20px;padding:3px 10px;background:linear-gradient(45deg,#4facfe,#00f2fe);color:white;font-weight:bold;">ScreenPilot</span>
                <span style="background:linear-gradient(180deg, transparent 60%, rgba(255,255,0,.5) 60%);">检查</span>
              </button>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val inlineFlow = root.children.single() as RichTextBlock
        val button = inlineFlow.inlineBoxes.single().block as RichButtonBlock

        assertEquals("go", button.action)
        assertEquals("\uFFFC", inlineFlow.content.text)
        assertTrue("button label should keep inline chip placeholders", button.inlineBoxes.isNotEmpty())
        assertTrue("button label should keep decorative inline paints", button.inlinePaints.isNotEmpty())
        assertTrue(button.label.text.contains("\uFFFC"))
    }

    @Test
    fun `button content keeps browser-like alignment defaults and flex alignment`() {
        val html = """
            <div id="vcp-root">
              <button onclick="input('a')">默认按钮</button>
              <button onclick="input('b')" style="display:flex;align-items:center;justify-content:center;width:48%;height:72px;">💻 VCPDesktop 教学</button>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val inlineFlow = root.children[0] as RichTextBlock
        val defaultButton = inlineFlow.inlineBoxes.single().block as RichButtonBlock
        val flexButton = root.children[1] as RichButtonBlock

        assertEquals("browser button labels are centered by default", TextAlign.Center, defaultButton.style.textAlign)
        assertEquals(RichDisplay.Flex, flexButton.style.display)
        assertEquals(RichJustify.Center, flexButton.style.justifyContent)
        assertEquals(RichAlign.Center, flexButton.style.alignItems)
        assertEquals(TextAlign.Center, flexButton.style.textAlign)
    }

    @Test
    fun `flex button preserves direct children for gap based layout`() {
        val html = """
            <div id="vcp-root">
              <button onclick="input('opt')" style="display:flex;align-items:center;justify-content:center;gap:10px;">
                <span>🔍</span><span>优化配置</span>
              </button>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val button = root.children.single() as RichButtonBlock

        assertEquals(RichDisplay.Flex, button.style.display)
        assertEquals(10f, button.style.gap.value, 0.01f)
        assertEquals("flex button child spans should not be flattened into a single Text node", 2, button.children.size)
    }

    @Test
    fun `flex item buttons retain sizing cues for stretched browser content`() {
        val html = """
            <div id="vcp-root" style="display:flex;gap:10px;">
              <button onclick="input('a')" style="flex:1;padding:12px;background:#0ea5e9;color:white;">🔍 优化配置</button>
              <button onclick="input('b')" style="flex:1;padding:12px;background:#334155;color:white;">🖥️ VCPDesktop 教学</button>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val first = root.children[0] as RichButtonBlock
        val second = root.children[1] as RichButtonBlock

        assertEquals(RichDisplay.Flex, root.style.display)
        assertEquals(10f, root.style.gap.value, 0.01f)
        listOf(first, second).forEach { button ->
            assertEquals(1f, button.style.flexGrow, 0.01f)
            assertEquals(RichSize.DpSize(0.dp), button.style.flexBasis)
            assertEquals(TextAlign.Center, button.style.textAlign)
        }
    }

    @Test
    fun `ordinary inline block buttons share a browser inline formatting row`() {
        val html = """
            <div id="vcp-root">
              <div style="margin-top:20px;border-top:1px solid #334155;padding-top:20px;">
                <p style="margin:0 0 15px 0;"><strong>🛠️ 接下来小初可以为您做：</strong></p>
                <button onclick="input('请帮我分析这份载荷数据')" style="background:#38bdf8;color:#0f172a;border:none;padding:10px 20px;border-radius:6px;font-weight:bold;cursor:pointer;">🔍 分析数据</button>
                <button onclick="input('清理过期的缓存记录')" style="background:transparent;color:#94a3b8;border:1px solid #475569;padding:10px 20px;border-radius:6px;cursor:pointer;margin-left:10px;">🧹 清理缓存</button>
              </div>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val panel = root.children.single() as RichContainerBlock
        val actions = panel.children[1] as RichTextBlock
        val first = actions.inlineBoxes[0].block as RichButtonBlock
        val second = actions.inlineBoxes[1].block as RichButtonBlock

        assertEquals("\uFFFC\uFFFC", actions.content.text)
        assertEquals(2, actions.inlineBoxes.size)
        assertEquals("请帮我分析这份载荷数据", first.action)
        assertEquals("清理过期的缓存记录", second.action)
        assertEquals("inline-block button should remain atomic in normal flow", RichDisplay.InlineBlock, first.style.display)
        assertEquals(10f, second.style.margin.left.value, 0.01f)
    }

    @Test
    fun `safe background parser retains several decorative layers`() {
        val html = """
            <div id="vcp-root" style="background:
              linear-gradient(90deg,#111,#222),
              radial-gradient(circle,#4facfe,transparent),
              linear-gradient(180deg,rgba(255,255,255,.4),transparent),
              url(data:image/png;base64,iVBORw0KGgo=),
              linear-gradient(45deg,#000,#fff);">Layers</div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock

        assertEquals(4, root.style.backgroundLayers.size)
        assertTrue(root.style.backgroundLayers.count { it.image is RichBackgroundImage.LinearGradient } >= 2)
        assertTrue(root.style.backgroundLayers.any { it.url?.startsWith("data:image/png") == true })
    }

    @Test
    fun `svg dash arrays are normalized before native drawing`() {
        val html = """
            <div id="vcp-root">
              <svg width="80" height="40" viewBox="0 0 80 40">
                <line x1="4" y1="8" x2="76" y2="8" stroke="#fff" stroke-width="2" stroke-dasharray="4"/>
                <line x1="4" y1="20" x2="76" y2="20" stroke="#fff" stroke-width="2" stroke-dasharray="4 0 2"/>
                <line x1="4" y1="32" x2="76" y2="32" stroke="#fff" stroke-width="2" stroke-dasharray="0 -1 none"/>
              </svg>
            </div>
        """.trimIndent()

        val svg = (RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock)
            .children
            .single() as RichSvgBlock
        val lines = svg.model.commands.filterIsInstance<RichSvgCommand.Line>()

        assertEquals(listOf(4f, 4f), lines[0].strokeDashArray)
        assertEquals(listOf(4f, 2f), lines[1].strokeDashArray)
        assertTrue(lines[2].strokeDashArray.isEmpty())
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

    private fun me.rerere.rikkahub.ui.components.richtext.RichSpacing.valueSum(): Float {
        return top.value + right.value + bottom.value + left.value
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
            val gradientTitle = blocks.filterIsInstance<RichTextBlock>().first { it.content.text.contains("Uika") }
            assertTrue(gradientTitle.style.backgroundImage is RichBackgroundImage.LinearGradient)
            assertNull("gradient text should not paint a rectangular background", gradientTitle.style.backgroundColor)
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
            assertTrue(grid.children.any {
                it.style.gridColumnStart == 2 &&
                    it.style.gridColumnSpan == 3 &&
                    it.style.gridRowSpan == 2
            })
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
