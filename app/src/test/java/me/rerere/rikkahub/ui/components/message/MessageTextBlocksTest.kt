package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.text.style.BaselineShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import me.rerere.rikkahub.ui.components.richtext.RichBackgroundRepeat
import me.rerere.rikkahub.ui.components.richtext.RichBackgroundSize
import me.rerere.rikkahub.ui.components.richtext.RichButtonBlock
import me.rerere.rikkahub.ui.components.richtext.RichBackgroundImage
import me.rerere.rikkahub.ui.components.richtext.RichBackgroundBox
import me.rerere.rikkahub.ui.components.richtext.RichAlign
import me.rerere.rikkahub.ui.components.richtext.RichAlignContent
import me.rerere.rikkahub.ui.components.richtext.RichAnimationDirection
import me.rerere.rikkahub.ui.components.richtext.RichAnimationEasing
import me.rerere.rikkahub.ui.components.richtext.RichAnimationFillMode
import me.rerere.rikkahub.ui.components.richtext.RichAnimationStrategy
import me.rerere.rikkahub.ui.components.richtext.RichBorderStyle
import me.rerere.rikkahub.ui.components.richtext.RichBorderCollapse
import me.rerere.rikkahub.ui.components.richtext.RichCaptionSide
import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichColorResolver
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichCssColor
import me.rerere.rikkahub.ui.components.richtext.RichDisplay
import me.rerere.rikkahub.ui.components.richtext.RichFlexDirection
import me.rerere.rikkahub.ui.components.richtext.RichFlexWrap
import me.rerere.rikkahub.ui.components.richtext.RichGridColumns
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompileOptions
import me.rerere.rikkahub.ui.components.richtext.hasLowCostColorEffect
import me.rerere.rikkahub.ui.components.richtext.RichImageBlock
import me.rerere.rikkahub.ui.components.richtext.RichJustify
import me.rerere.rikkahub.ui.components.richtext.RichListStylePosition
import me.rerere.rikkahub.ui.components.richtext.RichListStyleType
import me.rerere.rikkahub.ui.components.richtext.RichMathBlock
import me.rerere.rikkahub.ui.components.richtext.RichPosition
import me.rerere.rikkahub.ui.components.richtext.RichSize
import me.rerere.rikkahub.ui.components.richtext.RichSvgBlock
import me.rerere.rikkahub.ui.components.richtext.RichSvgCommand
import me.rerere.rikkahub.ui.components.richtext.RichSvgLineCap
import me.rerere.rikkahub.ui.components.richtext.RichSvgLineJoin
import me.rerere.rikkahub.ui.components.richtext.RichSvgPaint
import me.rerere.rikkahub.ui.components.richtext.RichSvgTextAnchor
import me.rerere.rikkahub.ui.components.richtext.RichTableBlock
import me.rerere.rikkahub.ui.components.richtext.RichTableSectionType
import me.rerere.rikkahub.ui.components.richtext.RichTextBlock
import me.rerere.rikkahub.ui.components.richtext.RichTransform
import me.rerere.rikkahub.ui.components.richtext.RichVisualHint
import me.rerere.rikkahub.ui.components.richtext.RichWhiteSpace
import me.rerere.rikkahub.ui.components.richtext.RichWordBreak
import me.rerere.rikkahub.ui.components.richtext.parseRichCssColor
import me.rerere.rikkahub.ui.components.richtext.validateRichHtmlBubbleBlock

class MessageTextBlocksTest {
    @Test
    fun `pure markdown stays as one markdown block`() {
        val blocks = parseMessageTextBlocks(
            text = "你好\n\n**今天继续学 EV**",
            streaming = false,
        )

        assertEquals(listOf(MessageTextBlock.Markdown("你好\n\n**今天继续学 EV**")), blocks)
    }

    @Test
    fun `long markdown is split into stable render cells`() {
        val paragraph = "这是一个很长的段落，用来模拟助手一次性输出大量正文。\n\n"
        val text = paragraph.repeat(140)

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it is MessageTextBlock.Markdown })
        assertEquals(text, blocks.joinToString(separator = "") { (it as MessageTextBlock.Markdown).text })
    }

    @Test
    fun `mixed vcp transcript is split in source order`() {
        val text = """
            [--- VCP元思考链: "default" (Auto模式) ---]
            【阶段1: 前思维簇】
            [--- 元思考链结束 ---]

            先看这个卡片：

            <div id="vcp-root"><h2>学习计划</h2><button onclick="input('继续')">继续</button></div>

            ```VCPToolCall
            <<<[TOOL_REQUEST]>>>
            tool_name:「始」DailyNote「末」
            <<<[END_TOOL_REQUEST]>>>
            ```

            [[VCP调用结果信息汇总:
            - 执行状态: ✅ SUCCESS
            VCP调用结果结束]]

            完成。
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(6, blocks.size)
        assertEquals(ProtocolKind.MetaThinking, (blocks[0] as MessageTextBlock.Protocol).kind)
        assertTrue((blocks[1] as MessageTextBlock.Markdown).text.contains("先看这个卡片"))
        assertFalse((blocks[2] as MessageTextBlock.VcpHtml).executable)
        assertEquals(ProtocolKind.ToolRequest, (blocks[3] as MessageTextBlock.Protocol).kind)
        assertEquals(true, (blocks[4] as MessageTextBlock.Protocol).success)
        assertTrue((blocks[5] as MessageTextBlock.Markdown).text.contains("完成"))
    }

    @Test
    fun `vcp root supports nested div and ignores script div text`() {
        val text = """
            before
            <div id="vcp-root">
              <div>inner</div>
              <script>
                const text = '</div><div>';
              </script>
              <style>.x:before { content: '</div>'; }</style>
            </div>
            after
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as MessageTextBlock.Markdown).text.contains("before"))
        val html = blocks[1] as MessageTextBlock.VcpHtml
        assertTrue(html.html.contains("<script>"))
        assertTrue(html.executable)
        assertFalse(html.partial)
        assertTrue((blocks[2] as MessageTextBlock.Markdown).text.contains("after"))
    }

    @Test
    fun `response root is treated as rich html block`() {
        val blocks = parseMessageTextBlocks(
            text = "before\n<div id=\"response-root\"><h2>特色气泡</h2></div>\nafter",
            streaming = false,
        )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageTextBlock.Markdown)
        assertTrue((blocks[1] as MessageTextBlock.VcpHtml).html.contains("response-root"))
        assertTrue(blocks[2] is MessageTextBlock.Markdown)
    }

    @Test
    fun `streaming partial vcp root becomes partial html block`() {
        val blocks = parseMessageTextBlocks(
            text = "intro\n<div id=\"vcp-root\"><div>loading",
            streaming = true,
        )

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MessageTextBlock.Markdown)
        val html = blocks[1] as MessageTextBlock.VcpHtml
        assertTrue(html.partial)
        assertEquals("<div id=\"vcp-root\"><div>loading", html.html)
        assertTrue(html.previewHtml?.contains("loading") == true)
        assertTrue(html.previewHtml?.endsWith("</div>") == true)
    }

    @Test
    fun `streaming root keeps partial until closing tag then becomes final html`() {
        val partialBlocks = parseMessageTextBlocks(
            text = "intro\n<div id=\"vcp-root\"><div>loading",
            streaming = true,
        )
        val finalBlocks = parseMessageTextBlocks(
            text = "intro\n<div id=\"vcp-root\"><div>loading</div></div>",
            streaming = true,
        )

        val partialHtml = partialBlocks.last() as MessageTextBlock.VcpHtml
        val finalHtml = finalBlocks.last() as MessageTextBlock.VcpHtml

        assertTrue(partialHtml.partial)
        assertFalse(finalHtml.partial)
        assertTrue(partialHtml.previewHtml?.contains("loading") == true)
        assertEquals(null, finalHtml.previewHtml)
        assertTrue(finalHtml.html.endsWith("</div></div>"))
    }

    @Test
    fun `streaming partial rich html preview compiles nested and styled content`() {
        val blocks = parseMessageTextBlocks(
            text = """
                intro
                <style>.card{background:#101827;color:white;padding:16px;border-radius:12px;}</style>
                <div id="vcp-root" class="card"><h2>学习计划</h2><div><p>第一步
            """.trimIndent(),
            streaming = true,
        )

        val html = blocks.last() as MessageTextBlock.VcpHtml
        val preview = html.previewHtml

        assertTrue(html.partial)
        assertTrue(preview?.contains("<style>") == true)
        assertTrue(preview?.contains("学习计划") == true)
        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(preview!!).kind)
        assertTrue(RichHtmlCompiler.compile(preview).blocks.isNotEmpty())
    }

    @Test
    fun `streaming partial details preview can render before final close`() {
        val block = parseMessageTextBlocks(
            text = "<details><summary>更多</summary><p>正在生成",
            streaming = true,
        ).single() as MessageTextBlock.VcpHtml

        val preview = block.previewHtml

        assertTrue(block.partial)
        assertTrue(preview?.contains("<details") == true)
        assertTrue(preview?.contains("正在生成") == true)
        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(preview!!).kind)
    }

    @Test
    fun `streaming partial dynamic html stays classified as dynamic preview`() {
        val block = parseMessageTextBlocks(
            text = """<div id="vcp-root"><canvas></canvas><p>chart loading""",
            streaming = true,
        ).single() as MessageTextBlock.VcpHtml

        val preview = block.previewHtml

        assertTrue(block.partial)
        assertTrue(preview?.contains("<canvas") == true)
        assertEquals(RichHtmlRenderKind.ComplexDynamic, analyzeRichHtml(preview!!).kind)
    }

    @Test
    fun `streaming partial unsafe html preview is rejected by safety gate`() {
        val block = parseMessageTextBlocks(
            text = """<div id="vcp-root"><img src="javascript:alert(1)">""",
            streaming = true,
        ).single() as MessageTextBlock.VcpHtml

        val preview = block.previewHtml

        assertTrue(block.partial)
        assertTrue(preview?.contains("javascript:alert") == true)
        assertFalse(inspectRichHtmlSafety(preview!!).safeForNative)
    }

    @Test
    fun `stable message text block key includes content and block type`() {
        val first = MessageTextBlock.Markdown("hello")
        val second = MessageTextBlock.Markdown("hello!")
        val html = MessageTextBlock.VcpHtml("<div id=\"vcp-root\">hello</div>", partial = false, executable = false)

        val firstKey = stableMessageTextBlockKey("message-1", 0, first)

        assertEquals(firstKey, stableMessageTextBlockKey("message-1", 0, first))
        assertNotEquals(firstKey, stableMessageTextBlockKey("message-1", 0, second))
        assertNotEquals(firstKey, stableMessageTextBlockKey("message-1", 0, html))
        assertTrue(stableMessageTextBlockKey("message-1", 0, html).contains(":html:"))
    }

    @Test
    fun `streaming partial rich html keeps stable block key across increments`() {
        val first = MessageTextBlock.VcpHtml(
            html = "<div id=\"vcp-root\"><h2>title",
            partial = true,
            executable = false,
            previewHtml = "<div id=\"vcp-root\"><h2>title</h2></div>",
        )
        val second = first.copy(
            html = "<div id=\"vcp-root\"><h2>title</h2><p>body",
            previewHtml = "<div id=\"vcp-root\"><h2>title</h2><p>body</p></div>",
        )
        val final = MessageTextBlock.VcpHtml(
            html = "<div id=\"vcp-root\"><h2>title</h2><p>body</p></div>",
            partial = false,
            executable = false,
        )

        val firstKey = stableMessageTextBlockKey("message-1", 0, first)

        assertEquals(firstKey, stableMessageTextBlockKey("message-1", 0, second))
        assertNotEquals(firstKey, stableMessageTextBlockKey("message-1", 0, final))
    }

    @Test
    fun `non streaming unclosed vcp root falls back to markdown`() {
        val blocks = parseMessageTextBlocks(
            text = "intro\n<div id=\"vcp-root\"><div>broken",
            streaming = false,
        )

        assertEquals(1, blocks.size)
        assertTrue((blocks.single() as MessageTextBlock.Markdown).text.contains("vcp-root"))
    }

    @Test
    fun `static rich html is classified as native static`() {
        val analysis = analyzeRichHtml(
            """
                <div id="response-root" style="background:#101827;color:white;padding:16px;">
                  <h2>学习计划</h2>
                  <p>今天复习 Kotlin 协程。</p>
                </div>
            """.trimIndent()
        )

        assertEquals(RichHtmlRenderKind.NativeStatic, analysis.kind)
        assertTrue(analysis.previewText.contains("学习计划"))
    }

    @Test
    fun `button input html is classified as interactive static`() {
        val html = """
            <div id="vcp-root">
              <button onclick="input('继续学习')">继续</button>
            </div>
        """.trimIndent()
        val analysis = analyzeRichHtml(html)
        val input = extractInputActionFromElement(
            dataSend = "",
            dataInput = "",
            value = "",
            onclick = "input('继续学习')",
            fallbackText = "继续",
        )

        assertEquals(RichHtmlRenderKind.InteractiveStatic, analysis.kind)
        assertEquals("继续学习", input)
    }

    @Test
    fun `dynamic html is classified as complex dynamic`() {
        val samples = listOf(
            """<div id="vcp-root"><script>requestAnimationFrame(loop)</script></div>""",
            """<div id="response-root"><canvas></canvas></div>""",
            """<div id="response-root"><pre>const renderer = new THREE.WebGLRenderer()</pre></div>""",
        )

        samples.forEach { html ->
            assertEquals(RichHtmlRenderKind.ComplexDynamic, analyzeRichHtml(html).kind)
        }
    }

    @Test
    fun `absolute positioning and css effects stay native static`() {
        val html = """
            <div id="response-root" style="position:absolute;top:0;left:0;background:linear-gradient(135deg,#1e1e2e 0%,#2a2a3e 100%);box-shadow:0 10px 30px rgba(0,0,0,.3);">
              <p>floating</p>
            </div>
        """.trimIndent()

        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(html).kind)
        assertEquals(NativeConfidence.Medium, analyzeRichHtml(html).nativeConfidence)
    }

    @Test
    fun `unsafe html is classified as complex dynamic`() {
        val samples = listOf(
            """<div id="vcp-root"><iframe src="https://example.com"></iframe></div>""",
            """<div id="vcp-root"><img src="javascript:alert(1)" /></div>""",
            """<div id="vcp-root"><a href="javascript:alert(1)">bad link</a></div>""",
        )

        samples.forEach { html ->
            assertEquals(RichHtmlRenderKind.ComplexDynamic, analyzeRichHtml(html).kind)
            assertFalse(inspectRichHtmlSafety(html).safeForNative)
        }
    }

    @Test
    fun `hover only event attributes are ignored for native static rendering`() {
        val html = """
            <div id="response-root">
              <button onmouseover="this.style.transform='scale(1.05)'" onmouseout="this.style.transform='scale(1)'">
                Hover only
              </button>
            </div>
        """.trimIndent()

        assertTrue(inspectRichHtmlSafety(html).safeForNative)
        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(html).kind)
    }

    @Test
    fun `safe input onclick stays interactive static`() {
        val html = """<div id="vcp-root"><button onclick="input('继续')">继续</button></div>"""

        assertTrue(inspectRichHtmlSafety(html).safeForNative)
        assertEquals(RichHtmlRenderKind.InteractiveStatic, analyzeRichHtml(html).kind)
    }

    @Test
    fun `styled visual card with hover effects stays interactive static`() {
        val html = """
            <div id="response-root" style="font-family: 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #333; padding: 20px; line-height: 1.6; background: #fdfdfd; border-radius: 16px;">
                <style>
                    @keyframes slideUp {
                        from { opacity: 0; transform: translateY(20px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                    .bubble-container {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
                        gap: 25px;
                        margin-top: 20px;
                    }
                    .bubble-card {
                        padding: 20px;
                        border-radius: 18px;
                        position: relative;
                        animation: slideUp 0.6s ease-out forwards;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                    }
                    .label {
                        font-size: 0.75rem;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                        margin-bottom: 10px;
                        font-weight: bold;
                        opacity: 0.7;
                    }
                </style>

                <div style="border-left: 4px solid #6366f1; padding-left: 15px; margin-bottom: 30px;">
                    <h2 style="margin: 0; font-weight: 800; color: #1f2937;">视觉容器：形态探索</h2>
                    <p style="margin: 5px 0 0 0; color: #6b7280;">我为你构建了四种迥异的视觉语境。</p>
                </div>

                <div class="bubble-container">
                    <div class="bubble-card" style="background: #0f172a; border-left: 4px solid #38bdf8; box-shadow: 0 0 20px rgba(56, 189, 248, 0.15); animation-delay: 0.2s;">
                        <span class="label" style="color: #38bdf8;">Neo-Terminal</span>
                        <div style="color: #cbd5e1; font-family: 'Fira Code', monospace; font-size: 0.9rem;">
                            &gt; 系统就绪 <br>
                            &gt; 它是冷冽的、精确的。
                        </div>
                    </div>
                </div>

                <div style="margin-top: 35px; text-align: center;">
                    <button onclick="input('尝试赛博风格')" style="background: #333; color: white; border: none; padding: 10px 20px; border-radius: 30px; cursor: pointer; transition: 0.3s; font-size: 0.9rem; margin: 0 5px;" onmouseover="this.style.transform='scale(1.05)'" onmouseout="this.style.transform='scale(1)'">
                        切换为冷峻风格
                    </button>
                    <button onclick="input('用柔和风格继续')" style="background: white; color: #333; border: 1px solid #ddd; padding: 10px 20px; border-radius: 30px; cursor: pointer; transition: 0.3s; font-size: 0.9rem; margin: 0 5px;" onmouseover="this.style.boxShadow='0 4px 12px rgba(0,0,0,0.1)'" onmouseout="this.style.boxShadow='none'">
                        保持温柔叙述
                    </button>
                </div>
            </div>
        """.trimIndent()

        val blocks = parseMessageTextBlocks(html, streaming = false)

        assertEquals(1, blocks.size)
        assertTrue((blocks.single() as MessageTextBlock.VcpHtml).html.contains("response-root"))
        assertTrue(inspectRichHtmlSafety(html).safeForNative)
        assertEquals(RichHtmlRenderKind.InteractiveStatic, analyzeRichHtml(html).kind)

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val flattened = flattenRichBlocks(root)

        assertTrue(model.unsupported.isEmpty())
        assertTrue(flattened.filterIsInstance<RichTextBlock>().any { it.content.text.contains("视觉容器") })
        assertTrue(flattened.filterIsInstance<RichButtonBlock>().any { it.action == "尝试赛博风格" })
        assertTrue(flattened.filterIsInstance<RichButtonBlock>().any { it.action == "用柔和风格继续" })
        assertFiniteTypography(root)
    }

    @Test
    fun `full visual container sample compiles to native rich blocks`() {
        val html = """
            <div id="response-root" style="font-family: 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; color: #333; padding: 20px; line-height: 1.6; background: #fdfdfd; border-radius: 16px;">
                <style>
                    @keyframes slideUp {
                        from { opacity: 0; transform: translateY(20px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                    .bubble-container {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
                        gap: 25px;
                        margin-top: 20px;
                    }
                    .bubble-card {
                        padding: 20px;
                        border-radius: 18px;
                        position: relative;
                        animation: slideUp 0.6s ease-out forwards;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                    }
                    .label {
                        font-size: 0.75rem;
                        text-transform: uppercase;
                        letter-spacing: 1px;
                        margin-bottom: 10px;
                        font-weight: bold;
                        opacity: 0.7;
                    }
                </style>

                <div style="border-left: 4px solid #6366f1; padding-left: 15px; margin-bottom: 30px;">
                    <h2 style="margin: 0; font-weight: 800; color: #1f2937;">视觉容器：形态探索</h2>
                    <p style="margin: 5px 0 0 0; color: #6b7280;">我为你构建了四种迥异的视觉语境，它们分别代表了理性的秩序、未来的幻象、柔软的触感以及纯粹的留白。</p>
                </div>

                <div class="bubble-container">
                    <div class="bubble-card" style="background: rgba(255, 255, 255, 0.7); backdrop-filter: blur(12px); border: 1px solid rgba(255, 255, 255, 0.3); box-shadow: 0 8px 32px rgba(31, 38, 135, 0.1); animation-delay: 0.1s;">
                        <span class="label" style="color: #4338ca;">Glassmorphism</span>
                        <div style="color: #1e1b4b; font-size: 0.95rem;">轻盈且通透。利用背景模糊与微弱的高光，营造出悬浮于界面之上的空气感，适合传递现代与优雅的情绪。</div>
                    </div>

                    <div class="bubble-card" style="background: #0f172a; border-left: 4px solid #38bdf8; box-shadow: 0 0 20px rgba(56, 189, 248, 0.15); animation-delay: 0.2s;">
                        <span class="label" style="color: #38bdf8;">Neo-Terminal</span>
                        <div style="color: #cbd5e1; font-family: 'Fira Code', monospace; font-size: 0.9rem;">
                            &gt; 系统就绪 <br>
                            &gt; 这种风格剥离了多余的装饰，保留了极致的对比。它是冷冽的、精确的，充满逻辑的美感。
                        </div>
                    </div>

                    <div class="bubble-card" style="background: #e0e5ec; box-shadow: 9px 9px 16px #becad9, -9px -9px 16px #ffffff; animation-delay: 0.3s;">
                        <span class="label" style="color: #7d8da1;">Neumorphism</span>
                        <div style="color: #4a5568; font-size: 0.95rem;">仿佛是从背景中“挤压”出的实体。它强调触觉的模拟，通过阴影的推拉创造出一种介于数字与现实之间的柔软质地。</div>
                    </div>

                    <div class="bubble-card" style="background: #fdf6e3; border: 1px solid #eee8d5; position: relative; animation-delay: 0.4s;">
                        <div style="position: absolute; top: 0; left: 0; right: 0; height: 100%; opacity: 0.05; pointer-events: none; background-image: url('https://www.transparenttextures.com/patterns/p6.png');"></div>
                        <span class="label" style="color: #859900;">Wabi-sabi</span>
                        <div style="color: #586e75; font-family: 'Georgia', serif; font-style: italic; font-size: 0.95rem;">回归质朴。偏暖的色调与微小的纹理模拟了纸张的阻尼感，让信息在流动的屏幕中拥有了一份沉静的重量。</div>
                    </div>
                </div>

                <div style="margin-top: 35px; text-align: center;">
                    <button onclick="input('尝试赛博风格')" style="background: #333; color: white; border: none; padding: 10px 20px; border-radius: 30px; cursor: pointer; transition: 0.3s; font-size: 0.9rem; margin: 0 5px;" onmouseover="this.style.transform='scale(1.05)'" onmouseout="this.style.transform='scale(1)'">
                        切换为冷峻风格
                    </button>
                    <button onclick="input('用柔和风格继续')" style="background: white; color: #333; border: 1px solid #ddd; padding: 10px 20px; border-radius: 30px; cursor: pointer; transition: 0.3s; font-size: 0.9rem; margin: 0 5px;" onmouseover="this.style.boxShadow='0 4px 12px rgba(0,0,0,0.1)'" onmouseout="this.style.boxShadow='none'">
                        保持温柔叙述
                    </button>
                </div>
            </div>
        """.trimIndent()

        val blocks = parseMessageTextBlocks(html, streaming = false)
        val analysis = analyzeRichHtml(html)
        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val flattened = flattenRichBlocks(root)

        assertEquals(1, blocks.size)
        assertFalse((blocks.single() as MessageTextBlock.VcpHtml).partial)
        assertEquals(RichHtmlRenderKind.InteractiveStatic, analysis.kind)
        assertTrue(inspectRichHtmlSafety(html).safeForNative)
        assertTrue(model.unsupported.isEmpty())
        assertTrue(flattened.filterIsInstance<RichTextBlock>().any { it.content.text.contains("GLASSMORPHISM") })
        assertTrue(flattened.filterIsInstance<RichTextBlock>().any { it.content.text.contains("WABI-SABI") })
        assertEquals(
            RichGridColumns.Count(1),
            flattened.filterIsInstance<RichContainerBlock>().first { it.style.display == RichDisplay.Grid }.style.gridColumns,
        )
        assertEquals(2, flattened.filterIsInstance<RichButtonBlock>().size)
        assertFiniteTypography(root)
    }

    @Test
    fun `oversized native html falls back to complex dynamic`() {
        val tooManyNodes = buildString {
            append("""<div id="vcp-root">""")
            repeat(RichHtmlBudget().maxNodes + 1) { append("<span>$it</span>") }
            append("</div>")
        }
        val tooDeep = "<div id=\"vcp-root\">" +
            "<div>".repeat(RichHtmlBudget().maxDepth + 2) +
            "deep" +
            "</div>".repeat(RichHtmlBudget().maxDepth + 3)
        val tooLargeTable = "<div id=\"vcp-root\"><table><tr>" +
            (0..RichHtmlBudget().maxTableCells).joinToString("") { "<td>$it</td>" } +
            "</tr></table></div>"
        val tooLargeSvgPath = "<div id=\"vcp-root\"><svg><path d=\"" +
            "M0 0 ".repeat(RichHtmlBudget().maxSvgPathChars / 5 + 2) +
            "\" /></svg></div>"

        listOf(tooManyNodes, tooDeep, tooLargeTable, tooLargeSvgPath).forEach { html ->
            assertEquals(RichHtmlRenderKind.ComplexDynamic, analyzeRichHtml(html).kind)
            assertFalse(inspectRichHtmlSafety(html).safeForNative)
        }
    }

    @Test
    fun `unknown safe tags degrade to native children but dangerous tags do not`() {
        val safeUnknown = """<div id="vcp-root"><custom-panel><p>ok</p></custom-panel></div>"""
        val dangerousUnknown = """<div id="vcp-root"><embed src="bad.swf" /></div>"""

        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(safeUnknown).kind)
        assertTrue(inspectRichHtmlSafety(safeUnknown).safeForNative)
        assertEquals(RichHtmlRenderKind.ComplexDynamic, analyzeRichHtml(dangerousUnknown).kind)
        assertFalse(inspectRichHtmlSafety(dangerousUnknown).safeForNative)
    }

    @Test
    fun `matching rich root depth limit falls back for non streaming`() {
        val html = "<div id=\"vcp-root\">" +
            "<div>".repeat(132) +
            "too deep" +
            "</div>".repeat(133)
        val blocks = parseMessageTextBlocks(html, streaming = false)

        assertEquals(1, blocks.size)
        assertTrue((blocks.single() as MessageTextBlock.Markdown).text.contains("vcp-root"))
    }

    @Test
    fun `style before vcp root is merged into html block`() {
        val text = """
            before
            <style>
              .vcp-card{background:#0f172a;color:white;padding:12px;}
            </style>
            <div id="vcp-root" class="vcp-card">卡片</div>
            after
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(3, blocks.size)
        val html = blocks[1] as MessageTextBlock.VcpHtml
        assertTrue(html.html.trimStart().startsWith("<style>"))
        assertTrue(html.html.contains("vcp-card"))
        assertFalse((blocks[0] as MessageTextBlock.Markdown).text.contains("<style>"))
    }

    @Test
    fun `classless styled visual root is treated as rich html block`() {
        val text = """
            # Synesthesial

            <div style="background:#1d1730;color:#f8fafc;padding:28px 24px;border-radius:18px;">
              <p>第五关的数学迷宫</p>
              <div style="margin-top:15px;background:#eef2f7;border-radius:10px;padding:14px;">0.15 / 0.45</div>
            </div>

            after
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as MessageTextBlock.Markdown).text.contains("Synesthesial"))
        val html = blocks[1] as MessageTextBlock.VcpHtml
        assertFalse(html.partial)
        assertTrue(html.html.trimStart().startsWith("<div style=\"background:#1d1730"))
        assertTrue(html.html.contains("0.15 / 0.45"))
        assertTrue((blocks[2] as MessageTextBlock.Markdown).text.contains("after"))
    }

    @Test
    fun `stylesheet styled class root is treated as rich html block`() {
        val text = """
            # Synesthesial

            <style>
              .syn-card {
                background:#1d1730;
                color:#f8fafc;
                padding:28px 24px;
                border-radius:18px;
              }
              .inner-note { border-left:4px solid #ff4f86; padding:12px; }
            </style>
            <div class="syn-card">
              <h1>UvA 模拟实战训练营</h1>
              <div class="inner-note">货币时间价值 (TVM)</div>
            </div>

            after
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as MessageTextBlock.Markdown).text.contains("Synesthesial"))
        val html = blocks[1] as MessageTextBlock.VcpHtml
        assertTrue(html.html.trimStart().startsWith("<style>"))
        assertTrue(html.html.contains("""<div class="syn-card">"""))
        assertTrue(html.html.contains("TVM"))
        val root = RichHtmlCompiler.compile(html.html).blocks.single() as RichContainerBlock
        assertTrue(root.style.backgroundColor != null)
        assertEquals(24f, root.style.padding.left.value, 0.01f)
        assertTrue((blocks[2] as MessageTextBlock.Markdown).text.contains("after"))
    }

    @Test
    fun `stylesheet styled semantic root keeps outer visual shell`() {
        val text = """
            # Synesthesial

            <style>
              .syn-shell {
                background:#1d1730;
                color:#f8fafc;
                padding:28px 24px;
                border-radius:18px;
              }
              .card-heading { color:#fde047; }
              .inner-note { border-left:4px solid #ff4f86; padding:12px; }
            </style>
            <section class="syn-shell">
              <div class="card-heading">FINANCE FOR QUANTITATIVE ECONOMICS</div>
              <div class="inner-note">货币时间价值 (TVM)</div>
            </section>

            after
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(3, blocks.size)
        val html = blocks[1] as MessageTextBlock.VcpHtml
        assertTrue(html.html.trimStart().startsWith("<style>"))
        assertTrue(html.html.contains("""<section class="syn-shell">"""))
        assertFalse(html.html.trimStart().startsWith("""<div class="card-heading""""))
        val root = RichHtmlCompiler.compile(html.html).blocks.single() as RichContainerBlock
        assertEquals("section", root.tagName)
        assertTrue(root.style.backgroundColor != null)
        assertEquals(24f, root.style.padding.left.value, 0.01f)
        assertTrue((blocks[2] as MessageTextBlock.Markdown).text.contains("after"))
    }

    @Test
    fun `plain color only classless div remains markdown`() {
        val text = """before <div style="color:red;">inline note</div> after"""

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(1, blocks.size)
        assertTrue((blocks.single() as MessageTextBlock.Markdown).text.contains("inline note"))
    }

    @Test
    fun `style rich html validates for native rendering`() {
        val html = """
            <style>
              .vcp-card{background:#0f172a;color:white;padding:12px;border-radius:10px;}
              .vcp-title{font-weight:700;color:#38bdf8;}
            </style>
            <div id="vcp-root" class="vcp-card">
              <p class="vcp-title">VCP Card</p>
              <p>Native rich HTML should not crash during style parsing.</p>
            </div>
        """.trimIndent()

        assertTrue(inspectRichHtmlSafety(html).safeForNative)
        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(html).kind)
        validateRichHtmlBubbleBlock(html)
    }

    @Test
    fun `css gradient alpha colors and shadows validate for native rendering`() {
        val html = """
            <style>
              .vcp-card{
                background-image:linear-gradient(135deg, rgb(15 23 42 / .94), #22c55e88);
                color:gold;
                box-shadow:0 10px 28px rgba(0,0,0,.28);
                text-shadow:0 1px 2px rgba(0,0,0,.35);
                padding:12px 16px;
                border-radius:14px;
              }
            </style>
            <div id="vcp-root" class="vcp-card">Gradient card</div>
        """.trimIndent()

        assertTrue(inspectRichHtmlSafety(html).safeForNative)
        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(html).kind)
        validateRichHtmlBubbleBlock(html)
    }

    @Test
    fun `rich html inside plain markdown fence stays markdown`() {
        val text = """
            这里是源码：

            ```html
            <div id="vcp-root" style="background:#111;color:white;padding:12px;">不要渲染</div>
            ```

            结束。
        """.trimIndent()

        val blocks = parseMessageTextBlocks(text, streaming = false)

        assertEquals(1, blocks.size)
        val markdown = blocks.single() as MessageTextBlock.Markdown
        assertTrue(markdown.text.contains("```html"))
        assertTrue(markdown.text.contains("vcp-root"))
    }

    @Test
    fun `real vcp widget details and math roots are recognized`() {
        val samples = listOf(
            """<div id='vcp-net-widget' style='width:220px;background:#0f172a;'>net</div>""",
            """<div id='vcp-clock-widget' style='width:220px;background:#fff;'>clock</div>""",
            """<details open><summary>更多</summary><p>内容</p></details>""",
            """<div class='math-block' style='background:#fff7ed;color:#1f2937;padding:16px;'>H<sub>2</sub>O</div>""",
        )

        samples.forEach { sample ->
            val blocks = parseMessageTextBlocks(sample, streaming = false)
            assertEquals(sample, (blocks.single() as MessageTextBlock.VcpHtml).html)
        }
    }

    @Test
    fun `css only animation and filters stay native static`() {
        val html = """
            <style>
              .status{animation:pulse 1.6s infinite;filter:brightness(1.1);backdrop-filter:blur(8px);}
              @keyframes pulse{0%,100%{opacity:.6}50%{opacity:1}}
            </style>
            <div id="vcp-root" class="status">LIVE</div>
        """.trimIndent()

        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(html).kind)
        val model = RichHtmlCompiler.compile(html)
        assertTrue(model.visualHints.contains(RichVisualHint.CssAnimation))
        assertTrue(model.visualHints.contains(RichVisualHint.CssKeyframes))
        assertTrue(model.visualHints.contains(RichVisualHint.CssInfiniteAnimation))
    }

    @Test
    fun `native compiler records safe css filter subset`() {
        val html = """
            <div id="vcp-root" style="opacity:.8;filter:opacity(50%) brightness(1.15) grayscale(25%);backdrop-filter:blur(8px);">
              Filtered
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock

        assertEquals(0.8f, root.style.opacity, 0.001f)
        assertEquals(0.5f, root.style.cssFilter.opacity ?: -1f, 0.001f)
        assertEquals(1.15f, root.style.cssFilter.brightness ?: -1f, 0.001f)
        assertEquals(0.25f, root.style.cssFilter.grayscale ?: -1f, 0.001f)
        assertTrue(root.style.cssFilter.requiresVisualApproximation)
        assertTrue(root.style.cssFilter.hasLowCostColorEffect())
        assertEquals(8.dp, root.style.backdropFilter.blurRadius)
        assertTrue(model.visualHints.any { it.name == "CssFilter" })
        assertTrue(model.visualHints.any { it.name == "CssBackdropFilter" })
    }

    @Test
    fun `css animation is native static with visibility protection`() {
        val html = """
            <div id="vcp-root">
              <style>
                @keyframes fadeIn { from { opacity:0; transform:translateY(12px); } to { opacity:1; transform:translateY(0); } }
                .card:hover { transform:scale(1.02); }
              </style>
              <div class="card" style="opacity:0;animation:fadeIn .6s ease-out forwards;transition:transform .2s ease;">Visible</div>
            </div>
        """.trimIndent()

        val analysis = analyzeRichHtml(html)
        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val card = flattenRichBlocks(root).filterIsInstance<RichTextBlock>().single()

        assertEquals(RichHtmlRenderKind.NativeStatic, analysis.kind)
        assertEquals(1f, card.style.opacity, 0.001f)
        assertTrue(card.style.animation.isDeclared)
        assertEquals(600, card.style.animation.durationMs)
        assertTrue(card.style.animation.fillModeForwards)
        assertEquals(0f, card.style.animation.nativeAnimation?.fromOpacity ?: -1f, 0.001f)
        assertEquals(1f, card.style.animation.nativeAnimation?.toOpacity ?: -1f, 0.001f)
        assertEquals(12.dp, card.style.animation.nativeAnimation?.fromTransform?.translateY)
        assertEquals(RichTransform.None, card.style.animation.nativeAnimation?.toTransform)
        assertEquals(RichAnimationStrategy.NativeAnimated, model.animationStats.strategy)
        assertEquals(1, model.animationStats.animatedElementCount)
        assertEquals(1, model.animationStats.nativeAnimatedCount)
        assertTrue(card.style.transition.isDeclared)
        assertTrue(model.visualHints.contains(RichVisualHint.CssAnimation))
        assertTrue(model.visualHints.contains(RichVisualHint.CssTransition))
        assertTrue(model.visualHints.contains(RichVisualHint.CssKeyframes))
        assertTrue(model.visualHints.contains(RichVisualHint.CssInteractivePseudoClass))
        assertTrue(model.visualHints.contains(RichVisualHint.AnimationDependentVisibility))
    }

    @Test
    fun `layout and massive infinite css animation stay observable hints`() {
        val html = """
            <div id="vcp-root">
              <style>
                @keyframes grow { from { width:10px; } to { width:80px; } }
              </style>
              <div style="animation:grow 1s infinite alternate;transition:width .2s ease;">Grow</div>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val block = flattenRichBlocks(root).filterIsInstance<RichTextBlock>().single()

        assertTrue(block.style.animation.isInfinite)
        assertTrue(block.style.animation.hasLayoutProperty)
        assertEquals(null, block.style.animation.nativeAnimation)
        assertEquals(RichAnimationStrategy.Staticized, model.animationStats.strategy)
        assertEquals(1, model.animationStats.animatedElementCount)
        assertEquals(1, model.animationStats.staticizedCount)
        assertEquals(1, model.animationStats.infiniteCount)
        assertEquals(1, model.animationStats.layoutAnimationCount)
        assertEquals(1, model.animationStats.transitionCount)
        assertTrue(model.visualHints.contains(RichVisualHint.CssAnimation))
        assertTrue(model.visualHints.contains(RichVisualHint.CssInfiniteAnimation))
        assertTrue(model.visualHints.contains(RichVisualHint.CssLayoutAnimation))
        assertTrue(model.visualHints.contains(RichVisualHint.CssTransition))
    }

    @Test
    fun `finite transform and multi keyframes become native animation`() {
        val html = """
            <div id="vcp-root">
              <style>
                @keyframes pop { from { transform:scale(.96) rotate(-2deg); opacity:.3; } to { transform:scale(1) rotate(0deg); opacity:1; } }
                @keyframes mid { 0% { opacity:0; } 50% { opacity:.6; } 100% { opacity:1; } }
              </style>
              <div class="ok" style="animation:pop 900ms ease-out forwards;">Pop</div>
              <div class="no" style="animation:mid 900ms ease-out forwards;">Mid</div>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val textBlocks = flattenRichBlocks(root).filterIsInstance<RichTextBlock>()
        val pop = textBlocks.single { it.content.text == "Pop" }
        val mid = textBlocks.single { it.content.text == "Mid" }

        assertEquals(900, pop.style.animation.nativeAnimation?.durationMs)
        assertEquals(0.96f, pop.style.animation.nativeAnimation?.fromTransform?.scaleX ?: -1f, 0.001f)
        assertEquals(-2f, pop.style.animation.nativeAnimation?.fromTransform?.rotateZ ?: 0f, 0.001f)
        assertEquals(3, mid.style.animation.nativeAnimation?.stops?.size)
        assertEquals(1, mid.style.animation.multiKeyframeCount)
        assertEquals(1, mid.style.animation.nativeAnimation?.iterationCount)
    }

    @Test
    fun `animation direction fill mode easing and finite iteration compile to native animation`() {
        val html = """
            <div id="vcp-root">
              <style>
                @keyframes slide { from { opacity:.2; transform:translateX(10px); } to { opacity:1; transform:translateX(0); } }
              </style>
              <div style="animation:slide .4s cubic-bezier(.2,.8,.2,1) 0s 2 reverse both;">Slide</div>
              <div style="animation:slide .4s linear none;">NoFill</div>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val textBlocks = flattenRichBlocks(root).filterIsInstance<RichTextBlock>()
        val slide = textBlocks.single { it.content.text == "Slide" }
        val noFill = textBlocks.single { it.content.text == "NoFill" }

        assertEquals(RichAnimationDirection.Reverse, slide.style.animation.direction)
        assertEquals(RichAnimationFillMode.Both, slide.style.animation.fillMode)
        assertTrue(slide.style.animation.easing is RichAnimationEasing.CubicBezier)
        assertEquals(2, slide.style.animation.nativeAnimation?.iterationCount)
        assertEquals(800, slide.style.animation.nativeAnimation?.totalDurationMs)
        assertEquals(0.dp, slide.style.animation.nativeAnimation?.fromTransform?.translateX)
        assertEquals(10.dp, slide.style.animation.nativeAnimation?.toTransform?.translateX)
        assertEquals(RichAnimationFillMode.None, noFill.style.animation.fillMode)
        assertEquals(RichAnimationEasing.Linear, noFill.style.animation.easing)
        assertEquals(RichTransform.None, noFill.style.animation.nativeAnimation?.toTransform)
    }

    @Test
    fun `infinite opacity pulse staticizes to most visible state without native playback`() {
        val html = """
            <div id="vcp-root">
              <style>@keyframes pulse { 0% { opacity:.35; } 50% { opacity:1; } 100% { opacity:.55; } }</style>
              <span style="opacity:.4; animation:pulse 1s infinite ease-in-out;">LIVE</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val live = flattenRichBlocks(root).filterIsInstance<RichTextBlock>().single()

        assertEquals(null, live.style.animation.nativeAnimation)
        assertEquals(1f, live.style.opacity, 0.001f)
        assertEquals(1f, live.style.animation.staticOpacity ?: -1f, 0.001f)
    }

    @Test
    fun `multiple animations keep first native candidate and count unsupported extras`() {
        val html = """
            <div id="vcp-root">
              <style>
                @keyframes fade { from { opacity:0; transform:translateY(8px); } to { opacity:1; transform:translateY(0); } }
                @keyframes grow { from { width:10px; } to { width:20px; } }
              </style>
              <div style="animation:fade .4s ease-out forwards, grow .4s ease-out forwards;">Combo</div>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val combo = flattenRichBlocks(model.blocks.single()).filterIsInstance<RichTextBlock>().single()

        assertEquals(2, combo.style.animation.declaredAnimationCount)
        assertEquals(400, combo.style.animation.nativeAnimation?.durationMs)
        assertEquals(2, combo.style.animation.unsupportedPropertyCount)
        assertEquals(1, model.animationStats.nativeAnimatedCount)
        assertEquals(2, model.animationStats.unsupportedPropertyCount)
        assertEquals(1, model.animationStats.snapshotCandidateCount)
    }

    @Test
    fun `native animation budget keeps first safe animations and staticizes overflow`() {
        val html = """
            <div id="vcp-root">
              <style>
                @keyframes fade { from { opacity:0; transform:translateY(8px); } to { opacity:1; transform:translateY(0); } }
              </style>
              <div style="animation:fade .4s ease-out forwards;">One</div>
              <div style="animation:fade .4s ease-out forwards;">Two</div>
              <div style="animation:fade .4s ease-out forwards;">Three</div>
              <div style="animation:fade .4s ease-out forwards;">Four</div>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(
            html,
            options = RichHtmlCompileOptions(
                budget = RichHtmlBudget(maxNativeAnimatedElements = 2),
            ),
        )
        val root = model.blocks.single() as RichContainerBlock
        val textBlocks = flattenRichBlocks(root).filterIsInstance<RichTextBlock>()

        assertEquals(RichAnimationStrategy.BudgetExceededStaticized, model.animationStats.strategy)
        assertEquals(4, model.animationStats.animatedElementCount)
        assertEquals(2, model.animationStats.nativeAnimatedCount)
        assertEquals(2, model.animationStats.staticizedCount)
        assertEquals(2, model.animationStats.budgetExceededCount)
        assertTrue(model.visualHints.contains(RichVisualHint.AnimationBudgetExceeded))
        assertTrue(textBlocks.take(2).all { it.style.animation.nativeAnimation != null })
        assertTrue(textBlocks.drop(2).all { it.style.animation.nativeAnimation == null })
    }

    @Test
    fun `native compiler supports hsl colors for static painting`() {
        val html = """
            <div id="vcp-root" style="color:hsl(210 100% 50%);background-color:hsla(280,100%,50%,.5);">
              hsl color
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock

        assertEquals(Color(0, 128, 255), root.style.color)
        assertEquals(Color(170, 0, 255, 128), root.style.backgroundColor)
    }

    @Test
    fun `rich css color parser distinguishes semantic color values`() {
        assertEquals(RichCssColor.Solid(Color(17, 34, 51)), parseRichCssColor("#123"))
        assertEquals(RichCssColor.Solid(Color(17, 34, 51, 68)), parseRichCssColor("#1234"))
        assertEquals(RichCssColor.Solid(Color(255, 0, 0, 128)), parseRichCssColor("rgb(100% 0% 0% / 50%)"))
        assertEquals(RichCssColor.Solid(Color(80, 122, 206, 204)), parseRichCssColor("hsl(220 56% 56% / .8)"))
        assertEquals(RichCssColor.Transparent, parseRichCssColor("transparent"))
        assertEquals(RichCssColor.CurrentColor, parseRichCssColor("currentColor"))
        assertTrue(parseRichCssColor("color-mix(in srgb, red 40%, blue)") is RichCssColor.Unresolved)
        assertEquals(null, parseRichCssColor("definitely-not-a-color"))
    }

    @Test
    fun `native compiler resolves currentColor and preserves explicit transparent`() {
        val html = """
            <div id="vcp-root" style="color:#336699;">
              <div style="color:currentColor;background:transparent;border:1px solid currentColor;">Inherited</div>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = flattenRichBlocks(root).filterIsInstance<RichTextBlock>().single()

        assertEquals(Color(0xFF336699), text.style.color)
        assertEquals(RichCssColor.CurrentColor, text.style.declaredColor)
        assertEquals(Color.Transparent, text.style.backgroundColor)
        assertEquals(RichCssColor.Transparent, text.style.declaredBackgroundColor)
        assertEquals(Color(0xFF336699), text.style.border.top.color)
    }

    @Test
    fun `native compiler records unsupported color hint without black fallback`() {
        val html = """
            <div id="vcp-root" style="color:color-mix(in srgb, red 40%, blue);background:#fff;">Text</div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock

        assertEquals(null, root.style.color)
        assertTrue(root.style.declaredColor is RichCssColor.Unresolved)
        assertTrue(model.visualHints.contains(RichVisualHint.CssUnsupportedColor))
    }

    @Test
    fun `rich color resolver fixes only low contrast text`() {
        val adjustedLight = RichColorResolver.resolveTextColor(
            requested = Color(0xFFDDDDDD),
            fallback = Color.Black,
            background = Color.White,
        )
        val adjustedDark = RichColorResolver.resolveTextColor(
            requested = Color(0xFF222222),
            fallback = Color.White,
            background = Color.Black,
        )
        val unchanged = RichColorResolver.resolveTextColor(
            requested = Color(0xFF111111),
            fallback = Color.Black,
            background = Color.White,
        )
        val gradientUnknown = RichColorResolver.resolveTextColor(
            requested = Color(0xFFDDDDDD),
            fallback = Color.Black,
            background = null,
        )

        assertTrue(RichColorResolver.contrastRatio(adjustedLight, Color.White) > 1.4)
        assertTrue(RichColorResolver.contrastRatio(adjustedDark, Color.Black) > 1.4)
        assertEquals(Color(0xFF111111), unchanged)
        assertEquals(Color(0xFFDDDDDD), gradientUnknown)
    }

    @Test
    fun `native compiler keeps flex bubble layout and background clip text hint`() {
        val html = """
            <div id="response-root" style="position:relative;overflow:hidden;min-height:500px;background:#050505;color:#fff;padding:40px 20px;">
              <style>
                .bg-orb{position:absolute;border-radius:50%;filter:blur(60px);z-index:0;opacity:.4;}
                .glass-bubble{position:relative;z-index:1;max-width:80%;background:rgba(255,255,255,.03);}
                .gradient-text{background:linear-gradient(90deg,#818cf8,#c084fc,#fb7185);-webkit-background-clip:text;-webkit-text-fill-color:transparent;}
              </style>
              <div class="bg-orb" style="width:300px;height:300px;top:-100px;right:-50px;"></div>
              <h1 class="gradient-text">Uika's Rendering Test</h1>
              <div style="display:flex;flex-direction:column;align-items:flex-start;">
                <div class="glass-bubble">left</div>
                <div class="glass-bubble" style="align-self:flex-end;">right</div>
              </div>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val title = flattenRichBlocks(root).filterIsInstance<RichTextBlock>()
            .first { it.content.text.contains("Uika") }
        val flex = flattenRichBlocks(root).filterIsInstance<RichContainerBlock>()
            .first { it.style.display == RichDisplay.Flex }
        val bubbles = flex.children.filterIsInstance<RichTextBlock>()

        assertEquals(RichBackgroundBox.Text, title.style.backgroundClip)
        assertTrue(model.visualHints.any { it.name == "CssBackgroundClipText" })
        assertEquals(0.8f, (bubbles[0].style.maxWidth?.value ?: -1f) / 360f, 0.05f)
        assertEquals(0.8f, (bubbles[1].style.maxWidth?.value ?: -1f) / 360f, 0.05f)
        assertEquals(RichAlign.End, bubbles[1].style.alignSelf)
    }

    @Test
    fun `native compiler keeps gradient text style for brush renderer`() {
        val html = """
            <div id="vcp-root">
              <h1 style="background:linear-gradient(90deg,#818cf8 0%,#c084fc 50%,#fb7185 100%);background-clip:text;-webkit-text-fill-color:transparent;">Gradient Title</h1>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val title = flattenRichBlocks(root).filterIsInstance<RichTextBlock>().single()
        val gradient = title.style.backgroundImage as RichBackgroundImage.LinearGradient

        assertEquals(RichBackgroundBox.Text, title.style.backgroundClip)
        assertEquals(3, gradient.stops.size)
        assertEquals(0f, gradient.stops[0].offset)
        assertEquals(0.5f, gradient.stops[1].offset)
        assertEquals(1f, gradient.stops[2].offset)
        assertTrue(model.visualHints.any { it.name == "CssBackgroundClipText" })
    }

    @Test
    fun `native compiler keeps css flexbox item constraints for official flexbox renderer`() {
        val html = """
            <div id="vcp-root" style="display:flex;flex-wrap:wrap;gap:12px;justify-content:space-between;align-items:center;align-content:stretch;">
              <span style="order:2;flex:1 1 120px;max-width:80%;align-self:flex-end;">A</span>
              <span style="width:40%;flex-shrink:0;">B</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val children = root.children.filterIsInstance<RichTextBlock>()
        val first = children.first { it.content.text.contains("A") }
        val second = children.first { it.content.text.contains("B") }

        assertEquals(RichDisplay.Flex, root.style.display)
        assertEquals(RichFlexWrap.Wrap, root.style.flexWrap)
        assertEquals(RichAlignContent.Stretch, root.style.alignContent)
        assertEquals(12.dp, root.style.gap)
        assertEquals(12.dp, root.style.rowGap)
        assertEquals(12.dp, root.style.columnGap)
        assertEquals(2, first.style.order)
        assertEquals(1f, first.style.flexGrow, 0.01f)
        assertEquals(1f, first.style.flexShrink, 0.01f)
        assertEquals(120f, (first.style.flexBasis as RichSize.DpSize).value.value, 0.01f)
        assertEquals(0.8f, (first.style.maxWidth?.value ?: -1f) / 360f, 0.05f)
        assertEquals(RichAlign.End, first.style.alignSelf)
        assertEquals(0.4f, (second.style.width as RichSize.Fraction).value, 0.01f)
        assertEquals(0f, second.style.flexShrink, 0.01f)
    }

    @Test
    fun `native compiler maps full css flexbox semantics for official flexbox renderer`() {
        val html = """
            <div id="vcp-root" style="display:flex;flex-flow:column-reverse wrap-reverse;gap:8px 12px;row-gap:10px;column-gap:16px;justify-content:space-evenly;align-items:baseline;align-content:normal;">
              <span style="align-self:baseline;">A</span>
              <span style="align-self:auto;">B</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val children = root.children.filterIsInstance<RichTextBlock>()
        val baseline = children.first { it.content.text.contains("A") }
        val auto = children.first { it.content.text.contains("B") }

        assertEquals(RichDisplay.Flex, root.style.display)
        assertEquals(RichFlexDirection.ColumnReverse, root.style.flexDirection)
        assertEquals(RichFlexWrap.WrapReverse, root.style.flexWrap)
        assertEquals(10.dp, root.style.gap)
        assertEquals(10.dp, root.style.rowGap)
        assertEquals(16.dp, root.style.columnGap)
        assertEquals(RichJustify.SpaceEvenly, root.style.justifyContent)
        assertEquals(RichAlign.Baseline, root.style.alignItems)
        assertEquals(RichAlignContent.Stretch, root.style.alignContent)
        assertEquals(RichAlign.Baseline, baseline.style.alignSelf)
        assertEquals(null, auto.style.alignSelf)
    }

    @Test
    fun `native rich html compiler cascades selectors and flattens inline text`() {
        val html = """
            <style>
              :root{--accent:#38bdf8;}
              #vcp-root .title{color:var(--accent);font-weight:700;}
              #vcp-root > p:first-child{font-size:18px;}
            </style>
            <div id="vcp-root" style="display:grid;grid-template-columns:repeat(2,1fr);gap:8px;">
              <p class="title">Hello <strong>Compose</strong> $${'$'}x^2$${'$'}</p>
              <button onclick="input('go')">Go</button>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val root = model.blocks.single() as RichContainerBlock
        val text = root.children.first() as RichTextBlock
        val button = root.children.last() as RichButtonBlock

        assertEquals(RichDisplay.Grid, root.style.display)
        assertTrue(text.content.text.contains("Hello Compose"))
        assertTrue(text.inlineMath.any { it.latex == "x^2" })
        assertEquals("go", button.action)
    }

    @Test
    fun `native compiler resolves css length functions background object fit and z index`() {
        val html = """
            <div id="vcp-root" style="width:calc(50% - 20px);padding:clamp(8px,10px,16px);gap:min(12px,20px);background-image:url(https://example.com/bg.png);background-size:contain;background-position:right bottom;background-repeat:no-repeat;">
              <img src="https://example.com/a.png" style="object-fit:cover;width:120px;height:80px;" />
              <span style="position:absolute;z-index:3;left:max(4px,8px);top:calc(10px + 2px);">A</span>
              <span style="position:absolute;z-index:1;left:0;top:0;">B</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val image = root.children.first { it is RichImageBlock } as RichImageBlock
        val overlays = root.children.filter { it.style.position == RichPosition.Absolute }

        assertEquals(160f, (root.style.width as RichSize.DpSize).value.value, 0.01f)
        assertEquals(10f, root.style.padding.top.value)
        assertEquals(12f, root.style.gap.value)
        assertEquals(2, overlays.size)
        assertTrue(overlays.maxOf { it.style.zIndex } > overlays.minOf { it.style.zIndex })
        assertTrue(image.style.objectFit.name.equals("Cover", ignoreCase = true))
    }

    @Test
    fun `native compiler supports list style position and static pseudo attr content`() {
        val html = """
            <style>
              .item::before { content: "[" attr(data-code) "] "; }
              .item::after { content: " done"; }
            </style>
            <ol id="vcp-root" style="list-style-type:upper-alpha;list-style-position:inside;">
              <li class="item" data-code="A1">Alpha</li>
            </ol>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val item = root.children.single() as RichTextBlock

        assertEquals(RichListStyleType.UpperAlpha, item.style.listStyleType)
        assertEquals(RichListStylePosition.Inside, item.style.listStylePosition)
        assertTrue(item.content.text.contains("A. [A1] Alpha done"))
    }

    @Test
    fun `native compiler resolves nested length functions and background image geometry`() {
        val html = """
            <div id="vcp-root" style="width:calc(min(80%, 320px) - clamp(8px, 2rem, 40px));padding:calc(4px + min(6px, 10px));gap:calc(max(4px, 8px) + 2px);background-image:url(data:image/png;base64,AAAA);background-size:100% auto;background-position:right 12px bottom 8px;background-repeat:repeat-x;">
              <span style="position:absolute;left:calc(max(4px, 8px) + min(2px, 3px));top:calc(20px - clamp(4px, 6px, 8px));">A</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val overlay = root.children.single() as RichTextBlock
        val size = root.style.backgroundSize as RichBackgroundSize.Explicit

        assertEquals(256f, (root.style.width as RichSize.DpSize).value.value, 0.01f)
        assertEquals(10f, root.style.padding.top.value, 0.01f)
        assertEquals(10f, root.style.gap.value, 0.01f)
        assertTrue(size.width is RichSize.Fraction)
        assertEquals(RichSize.Auto, size.height)
        assertEquals(1f, root.style.backgroundPosition.xFraction, 0.01f)
        assertEquals(1f, root.style.backgroundPosition.yFraction, 0.01f)
        assertEquals(-12f, root.style.backgroundPosition.xOffset.value, 0.01f)
        assertEquals(-8f, root.style.backgroundPosition.yOffset.value, 0.01f)
        assertEquals(RichBackgroundRepeat.RepeatX, root.style.backgroundRepeat)
        assertEquals(10f, overlay.style.offset.left?.value ?: -1f, 0.01f)
        assertEquals(14f, overlay.style.offset.top?.value ?: -1f, 0.01f)
    }

    @Test
    fun `native compiler records background v2 boxes repeat and extra layers`() {
        val html = """
            <div id="vcp-root" style="background-image:url(data:image/png;base64,AAAA),linear-gradient(90deg,#000,#fff);background-origin:content-box;background-clip:padding-box;background-repeat:round;">
              layered
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock

        assertEquals("data:image/png;base64,AAAA", root.style.backgroundUrl)
        assertEquals(RichBackgroundRepeat.Round, root.style.backgroundRepeat)
        assertEquals(RichBackgroundBox.ContentBox, root.style.backgroundOrigin)
        assertEquals(RichBackgroundBox.PaddingBox, root.style.backgroundClip)
        assertEquals(1, root.style.extraBackgroundLayers)
    }

    @Test
    fun `native compiler supports ordered list attributes list image and css content escapes`() {
        val html = """
            <style>
              .item::before { content: "\00BB\20" attr(data-a) "-" attr(data-b) " "; }
            </style>
            <ol id="vcp-root" start="4" reversed type="a" style="list-style-image:url(data:image/png;base64,AAAA);list-style-position:outside;">
              <li class="item" data-a="A" data-b="1">Alpha</li>
              <li class="item" data-a="B" data-b="2">Beta</li>
            </ol>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val first = root.children[0] as RichTextBlock
        val second = root.children[1] as RichTextBlock

        assertEquals("data:image/png;base64,AAAA", first.style.listStyleImage)
        assertEquals(RichListStyleType.LowerAlpha, first.style.listStyleType)
        assertTrue(first.content.text.contains("d. » A-1 Alpha"))
        assertTrue(second.content.text.contains("c. » B-2 Beta"))
    }

    @Test
    fun `native compiler records text layout tail css and inline vertical align`() {
        val html = """
            <div id="vcp-root">
              <p style="white-space:nowrap;overflow-wrap:anywhere;text-transform:capitalize;font-variant-numeric:tabular-nums slashed-zero;">
                long-token-example <span style="vertical-align:super">x2</span>
              </p>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val text = root.children.single() as RichTextBlock

        assertEquals(RichWhiteSpace.NoWrap, text.style.whiteSpace)
        assertEquals(RichWordBreak.BreakWord, text.style.wordBreak)
        assertEquals("tabular-nums slashed-zero", text.style.fontVariantNumeric)
        assertTrue(text.content.text.contains("Long-Token-Example"))
        assertTrue(text.content.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript })
    }

    @Test
    fun `native compiler keeps safe list style image rejects unsafe marker and resolves list counters`() {
        val html = """
            <style>
              .safe { list-style-image:url(data:image/png;base64,AAAA); }
              .unsafe { list-style-image:url(javascript:alert(1)); }
              li::before { content: counter(section) "." counter(list-item) ") "; }
            </style>
            <ol id="vcp-root" start="3" style="list-style-type:none;">
              <li class="safe">Alpha</li>
              <li class="unsafe">Beta</li>
            </ol>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val first = root.children[0] as RichTextBlock
        val second = root.children[1] as RichTextBlock

        assertEquals("data:image/png;base64,AAAA", first.style.listStyleImage)
        assertEquals(null, second.style.listStyleImage)
        assertTrue(first.content.text.contains("1.3) Alpha"))
        assertTrue(second.content.text.contains("2.4) Beta"))
    }

    @Test
    fun `native compiler preserves nested list as nested blocks with accumulated indent`() {
        val html = """
            <ul id="vcp-root">
              <li>Parent
                <ol start="2" type="A">
                  <li>Child</li>
                </ol>
              </li>
            </ul>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val parent = root.children.single() as RichContainerBlock
        val parentText = parent.children[0] as RichTextBlock
        val nested = parent.children[1] as RichContainerBlock
        val child = nested.children.single() as RichTextBlock

        assertTrue(parentText.content.text.contains("Parent"))
        assertTrue(child.content.text.contains("B. Child"))
        assertTrue(child.style.margin.left > parentText.style.margin.left)
    }

    @Test
    fun `native compiler records phase three flex order align content and grid spans`() {
        val html = """
            <div id="vcp-root">
              <div style="display:flex;flex-wrap:wrap;align-content:space-around;gap:8px;">
                <span style="order:2;flex-grow:1.5;flex-basis:120px;align-self:center;">Second</span>
                <span style="order:-1;">First</span>
              </div>
              <div style="display:grid;grid-template-columns:repeat(3,1fr);gap:6px;">
                <span style="grid-column:1 / 3;grid-row:span 3;">Wide</span>
              </div>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val containers = flattenRichBlocks(root).filterIsInstance<RichContainerBlock>()
        val flex = containers.first { it.style.display == RichDisplay.Flex }
        val grid = containers.first { it.style.display == RichDisplay.Grid }
        val flexTexts = flex.children.filterIsInstance<RichTextBlock>()
        val flexSecond = flexTexts.first { it.content.text.contains("Second") }
        val flexFirst = flexTexts.first { it.content.text.contains("First") }
        val gridWide = grid.children.filterIsInstance<RichTextBlock>().single()

        assertEquals(RichDisplay.Flex, flex.style.display)
        assertEquals(RichFlexWrap.Wrap, flex.style.flexWrap)
        assertEquals(RichAlignContent.SpaceAround, flex.style.alignContent)
        assertEquals(2, flexSecond.style.order)
        assertEquals(-1, flexFirst.style.order)
        assertEquals(1.5f, flexSecond.style.flexGrow, 0.01f)
        assertEquals(120f, (flexSecond.style.flexBasis as RichSize.DpSize).value.value, 0.01f)
        assertEquals(RichAlign.Center, flexSecond.style.alignSelf)
        assertEquals(RichGridColumns.Count(3), grid.style.gridColumns)
        assertEquals(2, gridWide.style.gridColumnSpan)
        assertEquals(3, gridWide.style.gridRowSpan)
    }

    @Test
    fun `native compiler preserves phase three v3 shrink stretch and grid line spans`() {
        val html = """
            <div id="vcp-root">
              <div style="display:flex;gap:10px;">
                <span style="flex-basis:220px;flex-shrink:2;">Wide</span>
                <span style="flex-basis:160px;flex-shrink:1;">Narrow</span>
              </div>
              <div style="display:flex;flex-wrap:wrap;align-content:stretch;gap:4px;">
                <span style="flex-basis:80px;">A</span>
                <span style="flex-basis:80px;">B</span>
              </div>
              <div style="display:grid;grid-template-columns:repeat(4,1fr);">
                <span style="grid-column:2 / 5;grid-row:1 / 3;">Span</span>
              </div>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val containers = flattenRichBlocks(root).filterIsInstance<RichContainerBlock>()
        val flex = containers.first { it.style.display == RichDisplay.Flex && it.style.flexWrap == RichFlexWrap.NoWrap }
        val wrap = containers.first { it.style.display == RichDisplay.Flex && it.style.flexWrap != RichFlexWrap.NoWrap }
        val grid = containers.first { it.style.display == RichDisplay.Grid }
        val wide = flex.children.filterIsInstance<RichTextBlock>().first { it.content.text.contains("Wide") }
        val narrow = flex.children.filterIsInstance<RichTextBlock>().first { it.content.text.contains("Narrow") }
        val gridSpan = grid.children.filterIsInstance<RichTextBlock>().single()

        assertEquals(220f, (wide.style.flexBasis as RichSize.DpSize).value.value, 0.01f)
        assertEquals(2f, wide.style.flexShrink, 0.01f)
        assertEquals(160f, (narrow.style.flexBasis as RichSize.DpSize).value.value, 0.01f)
        assertEquals(1f, narrow.style.flexShrink, 0.01f)
        assertEquals(RichAlignContent.Stretch, wrap.style.alignContent)
        assertEquals(RichGridColumns.Count(4), grid.style.gridColumns)
        assertEquals(2, gridSpan.style.gridColumnStart)
        assertEquals(1, gridSpan.style.gridRowStart)
        assertEquals(3, gridSpan.style.gridColumnSpan)
        assertEquals(2, gridSpan.style.gridRowSpan)
    }

    @Test
    fun `native compiler preserves explicit grid longhand placement`() {
        val html = """
            <div id="vcp-root" style="display:grid;grid-template-columns:repeat(4,1fr);">
              <span style="grid-column-start:2;grid-column-end:span 2;grid-row-start:3;grid-row-end:5;">Placed</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val placed = root.children.filterIsInstance<RichTextBlock>().single()

        assertEquals(RichDisplay.Grid, root.style.display)
        assertEquals(2, placed.style.gridColumnStart)
        assertEquals(3, placed.style.gridRowStart)
        assertEquals(2, placed.style.gridColumnSpan)
        assertEquals(2, placed.style.gridRowSpan)
    }

    @Test
    fun `native compiler preserves table caption sections colspan and cell style`() {
        val html = """
            <div id="vcp-root">
              <table style="border-collapse:collapse;caption-side:bottom;">
                <caption style="color:#2563eb;text-align:right;">Quarterly Stats</caption>
                <thead>
                  <tr><th colspan="2" style="background:#111827;color:white;text-align:center;">Metric</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td style="background:#f8fafc;border:1px solid #cbd5e1;padding:6px;text-align:right;vertical-align:bottom;">A</td>
                    <td rowspan="2">42</td>
                  </tr>
                </tbody>
                <tfoot><tr><td>Total</td><td>42</td></tr></tfoot>
              </table>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val table = root.children.single() as RichTableBlock
        val headerCell = table.sections.single { it.type == RichTableSectionType.Head }.rows.single().single()
        val bodyCell = table.sections.single { it.type == RichTableSectionType.Body }.rows.single().first()
        val footRow = table.sections.single { it.type == RichTableSectionType.Foot }.rows.single()

        assertEquals("Quarterly Stats", table.caption?.text)
        assertTrue(table.captionStyle?.color != null)
        assertEquals(RichBorderCollapse.Collapse, table.style.borderCollapse)
        assertEquals(RichCaptionSide.Bottom, table.style.captionSide)
        assertEquals(3, table.sections.size)
        assertTrue(headerCell.isHeader)
        assertEquals(2, headerCell.colspan)
        assertEquals(2, table.sections.single { it.type == RichTableSectionType.Body }.rows.single()[1].rowspan)
        assertEquals(2, footRow.size)
        assertEquals(1f, bodyCell.style.border.top.width.value, 0.01f)
        assertEquals(6f, bodyCell.style.padding.top.value, 0.01f)
        assertTrue(bodyCell.style.backgroundColor != null)
    }

    @Test
    fun `native compiler preserves phase four v3 multi header footer and rowspan metadata`() {
        val html = """
            <div id="vcp-root">
              <table style="border-collapse:collapse;">
                <thead>
                  <tr><th colspan="3">Report</th></tr>
                  <tr><th>A</th><th>B</th><th>C</th></tr>
                </thead>
                <tbody>
                  <tr><td rowspan="3">Group</td><td>One</td><td>10</td></tr>
                  <tr><td>Two</td><td>20</td></tr>
                  <tr><td>Three</td><td>30</td></tr>
                </tbody>
                <tfoot>
                  <tr><td colspan="2">Total</td><td>60</td></tr>
                </tfoot>
              </table>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val table = root.children.single() as RichTableBlock
        val head = table.sections.single { it.type == RichTableSectionType.Head }
        val body = table.sections.single { it.type == RichTableSectionType.Body }
        val foot = table.sections.single { it.type == RichTableSectionType.Foot }

        assertEquals(2, head.rows.size)
        assertEquals(3, body.rows.size)
        assertEquals(1, foot.rows.size)
        assertEquals(3, head.rows.first().single().colspan)
        assertEquals(3, body.rows.first().first().rowspan)
        assertEquals(2, foot.rows.single().first().colspan)
    }

    @Test
    fun `native rich html compiler emits image math svg and table blocks`() {
        val html = """
            <div id="vcp-root">
              <img src="https://placehold.co/100x50/png" alt="ok" />
              <div class="formula-box">E = mc^2</div>
              <svg width="80" height="40" viewBox="0 0 80 40"><rect x="4" y="4" width="20" height="20" fill="#38bdf8"/><circle cx="52" cy="20" r="10" fill="#22c55e"/></svg>
              <table><tr><th>A</th></tr><tr><td>B</td></tr></table>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock

        assertTrue(root.children.any { it is RichImageBlock })
        assertTrue(root.children.any { it is RichMathBlock })
        assertTrue(root.children.any { it is RichSvgBlock })
        assertTrue(root.children.any { it.javaClass.simpleName == "RichTableBlock" })
    }

    @Test
    fun `button action extraction only sends explicit input actions`() {
        assertEquals(
            "",
            extractInputActionFromElement(
                dataSend = "",
                dataInput = "",
                value = "",
                onclick = "",
                fallbackText = "Dry run",
            )
        )
        assertEquals(
            "继续学习",
            extractInputActionFromElement(
                dataSend = "继续学习",
                dataInput = "",
                value = "",
                onclick = "",
                fallbackText = "继续",
            )
        )
        assertEquals(
            "打开 CAPM",
            extractInputActionFromElement(
                dataSend = "",
                dataInput = "",
                value = "",
                onclick = "input('打开 CAPM')",
                fallbackText = "CAPM",
            )
        )
    }

    @Test
    fun `compiler preserves advanced visual styles for native renderer`() {
        val html = """
            <style>
              #vcp-root{
                border-top:2px dashed #38bdf8;
                border-right:3px dotted rgba(34,197,94,.8);
                border-bottom:4px double #f97316;
                box-shadow:0 2px 4px rgba(0,0,0,.25), 4px 8px 12px #00000033;
                background:linear-gradient(90deg, #000000 0%, rgba(34,197,94,.8) 45%, white 100%);
              }
              .badge{position:absolute;right:8px;top:6px;}
            </style>
            <div id="vcp-root">
              <span>body</span>
              <span class="badge">badge</span>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val background = root.style.backgroundImage as RichBackgroundImage.LinearGradient
        val badge = root.children.last() as RichTextBlock

        assertEquals(RichBorderStyle.Dashed, root.style.border.top.style)
        assertEquals(RichBorderStyle.Dotted, root.style.border.right.style)
        assertEquals(RichBorderStyle.Double, root.style.border.bottom.style)
        assertEquals(2, root.style.shadows.size)
        assertEquals(0f, background.stops[0].offset)
        assertEquals(0.45f, background.stops[1].offset)
        assertEquals(1f, background.stops[2].offset)
        assertEquals(RichPosition.Absolute, badge.style.position)
        assertEquals(RichHtmlRenderKind.NativeStatic, analyzeRichHtml(html).kind)
    }

    @Test
    fun `svg linear gradient keeps stop offsets and opacity`() {
        val html = """
            <div id="vcp-root">
              <svg width="80" height="40" viewBox="0 0 80 40">
                <defs>
                  <linearGradient id="g">
                    <stop offset="0%" stop-color="#38bdf8" stop-opacity=".25"/>
                    <stop offset="60%" style="stop-color:#22c55e;stop-opacity:.8"/>
                    <stop offset="1" stop-color="#ffffff"/>
                  </linearGradient>
                </defs>
                <rect x="0" y="0" width="80" height="40" fill="url(#g)"/>
              </svg>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val svg = root.children.single() as RichSvgBlock
        val rect = svg.model.commands.single() as RichSvgCommand.Rect
        val paint = rect.fill as RichSvgPaint.LinearGradient

        assertEquals(3, paint.stops.size)
        assertEquals(0f, paint.stops[0].offset)
        assertEquals(0.6f, paint.stops[1].offset)
        assertEquals(1f, paint.stops[2].offset)
        assertTrue(paint.stops[0].color.alpha < paint.stops[1].color.alpha)
    }

    @Test
    fun `svg v25 keeps radial gradient stroke style opacity and text anchor`() {
        val html = """
            <div id="vcp-root">
              <svg width="120" height="80" viewBox="0 0 120 80">
                <defs>
                  <radialGradient id="rg">
                    <stop offset="0%" stop-color="skyblue" stop-opacity=".5"/>
                    <stop offset="100%" stop-color="#0f172a"/>
                  </radialGradient>
                </defs>
                <g fill="url(#rg)" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="bevel" stroke-dasharray="4 2" opacity=".8">
                  <circle cx="30" cy="30" r="20"/>
                  <polyline points="60,10 90,30 60,50"/>
                </g>
                <text x="60" y="70" text-anchor="middle" font-size="12" font-weight="700" fill="green">OK</text>
              </svg>
            </div>
        """.trimIndent()

        val root = RichHtmlCompiler.compile(html).blocks.single() as RichContainerBlock
        val svg = root.children.single() as RichSvgBlock
        val circle = svg.model.commands.filterIsInstance<RichSvgCommand.Circle>().single()
        val polyline = svg.model.commands.filterIsInstance<RichSvgCommand.Polyline>().single()
        val text = svg.model.commands.filterIsInstance<RichSvgCommand.Text>().single()
        val fill = circle.fill as RichSvgPaint.RadialGradient
        val stroke = circle.stroke as RichSvgPaint.Solid

        assertEquals(2, fill.stops.size)
        assertTrue(fill.stops.first().color.alpha < 0.5f)
        assertTrue(stroke.color.alpha < 1f)
        assertEquals(RichSvgLineCap.Round, circle.strokeLineCap)
        assertEquals(RichSvgLineJoin.Bevel, circle.strokeLineJoin)
        assertEquals(listOf(4f, 2f), circle.strokeDashArray)
        assertEquals(RichSvgLineCap.Round, polyline.strokeLineCap)
        assertEquals(RichSvgTextAnchor.Middle, text.textAnchor)
        assertEquals(700, text.fontWeight)
        assertTrue((text.fill as RichSvgPaint.Solid).color.green > 0f)
    }

    @Test
    fun `render seed assistant html samples produce rich blocks`() {
        val texts = loadRenderSeedAssistantTexts()
        val htmlBlocks = texts.flatMap { text ->
            parseMessageTextBlocks(text, streaming = false).filterIsInstance<MessageTextBlock.VcpHtml>()
        }

        assertTrue(htmlBlocks.size >= 7)
        assertTrue(htmlBlocks.any { it.html.contains("vcp-card-header") })
        assertTrue(htmlBlocks.any { it.html.contains("response-root") })
        assertTrue(htmlBlocks.any { it.html.contains("math-block") })
        assertTrue(htmlBlocks.any { it.html.contains("vcp-net-widget") })
        assertTrue(htmlBlocks.any { it.html.contains("vcp-clock-widget") })
        assertTrue(htmlBlocks.any { it.html.trimStart().startsWith("<details") })
        assertTrue(htmlBlocks.none { it.html.contains("&lt;script&gt;") && it.executable })
    }

    private fun loadRenderSeedAssistantTexts(): List<String> {
        val seedFile = sequenceOf(
            File("src/debug/assets/render_seed/chat_render_seed.json"),
            File("app/src/debug/assets/render_seed/chat_render_seed.json"),
        ).first { it.exists() }

        val root = Json.parseToJsonElement(seedFile.readText()).jsonObject
        return root.getValue("conversations").jsonArray.flatMap { conversation ->
            conversation.jsonObject.getValue("messages").jsonArray
                .filter { message -> message.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
                .flatMap { message ->
                    message.jsonObject.getValue("parts").jsonArray
                        .filter { part -> part.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                        .map { part -> part.jsonObject.getValue("text").jsonPrimitive.content }
                }
        }
    }

    private fun flattenRichBlocks(block: RichBlock): List<RichBlock> {
        return when (block) {
            is RichContainerBlock -> listOf(block) + block.children.flatMap(::flattenRichBlocks)
            is RichTextBlock -> listOf(block) + block.inlineBoxes.flatMap { flattenRichBlocks(it.block) }
            is RichButtonBlock -> listOf(block) +
                block.inlineBoxes.flatMap { flattenRichBlocks(it.block) } +
                block.children.flatMap(::flattenRichBlocks)
            else -> listOf(block)
        }
    }

    private fun assertFiniteTypography(block: RichBlock) {
        assertFiniteTextUnit("${block.blockId}.fontSize", block.style.fontSize)
        assertFiniteTextUnit("${block.blockId}.lineHeight", block.style.lineHeight)
        when (block) {
            is RichContainerBlock -> block.children.forEach(::assertFiniteTypography)
            is RichTextBlock -> block.inlineBoxes.forEach { assertFiniteTypography(it.block) }
            is RichButtonBlock -> {
                block.inlineBoxes.forEach { assertFiniteTypography(it.block) }
                block.children.forEach(::assertFiniteTypography)
            }
            else -> Unit
        }
    }

    private fun assertFiniteTextUnit(label: String, value: TextUnit) {
        if (value.isSpecified) {
            assertTrue("$label should be finite but was $value", value.value.isFinite())
        }
    }
}
