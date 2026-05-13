package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
