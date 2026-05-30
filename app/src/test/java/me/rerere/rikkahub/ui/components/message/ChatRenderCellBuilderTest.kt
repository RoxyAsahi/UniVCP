package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatRenderCellBuilderTest {
    @Test
    fun `long assistant markdown is flattened into stable markdown cells`() {
        val text = "段落内容用于验证长消息 cell 化。\n\n".repeat(180)
        val conversation = conversationOf(UIMessage.assistant(text))

        val first = buildChatRenderCells(conversation, Settings(), loading = false)
        val second = buildChatRenderCells(conversation, Settings(), loading = false)
        val markdownCells = first.filterIsInstance<ChatRenderCell.MarkdownCell>()

        assertTrue(markdownCells.size > 1)
        assertEquals(
            first.map { it.stableKey },
            second.map { it.stableKey },
        )
        assertTrue(markdownCells.all { it.contentType == ChatRenderCellContentType.MarkdownCell })
        assertEquals(text, markdownCells.joinToString(separator = "") { it.text })
    }

    @Test
    fun `html protocol and markdown cells keep source order`() {
        val text = """
            intro
            <div id="vcp-root"><h2>Card</h2></div>
            ```VCPToolCall
            <<<[TOOL_REQUEST]>>>
            tool_name:「始」Echo「末」
            <<<[END_TOOL_REQUEST]>>>
            ```
            outro
        """.trimIndent()
        val conversation = conversationOf(UIMessage.assistant(text))

        val contentTypes = buildChatRenderCells(conversation, Settings(), loading = false)
            .filterNot { it is ChatRenderCell.AvatarCell || it is ChatRenderCell.ActionsCell }
            .filterNot { it is ChatRenderCell.BottomSpacerCell }
            .map { it.contentType }

        assertEquals(
            listOf(
                ChatRenderCellContentType.MarkdownCell,
                ChatRenderCellContentType.RichHtmlCell,
                ChatRenderCellContentType.ProtocolCell,
                ChatRenderCellContentType.MarkdownCell,
            ),
            contentTypes,
        )
    }

    @Test
    fun `message behaviors stay aggregated by node id after flattening`() {
        val node = MessageNode.of(
            UIMessage(
                role = me.rerere.ai.core.MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("hello"),
                    UIMessagePart.Image("file:///tmp/a.png"),
                ),
            )
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(node),
        )

        val cells = buildChatRenderCells(conversation, Settings(), loading = false)
        val messageCells = cells.filter { it.nodeId == node.id }

        assertFalse(messageCells.isEmpty())
        assertTrue(messageCells.any { it is ChatRenderCell.UserBubbleCell })
        assertTrue(messageCells.any { it is ChatRenderCell.AttachmentCell })
        assertTrue(messageCells.any { it is ChatRenderCell.ActionsCell })
        assertTrue(messageCells.all { it.messageId == node.currentMessage.id })
        assertEquals(0, cells.firstCellIndexForNodeId(node.id))
    }

    @Test
    fun `rich html risk score routes very complex svg to snapshot`() {
        val path = "M0 0 " + "L1 1 ".repeat(4000)
        val html = """<div id="vcp-root"><svg viewBox="0 0 10 10"><path d="$path"/></svg></div>"""
        val conversation = conversationOf(UIMessage.assistant(html))

        val cell = buildChatRenderCells(conversation, Settings(), loading = false)
            .filterIsInstance<ChatRenderCell.RichHtmlCell>()
            .single()

        assertTrue(cell.renderRisk.score >= 80)
        assertEquals(RichContentRoute.Snapshot, cell.renderRisk.route)
    }

    @Test
    fun `streaming markdown cell key stays stable across text increments`() {
        val message = UIMessage.assistant("hello")
        val node = MessageNode.of(message)
        val first = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(node),
        )
        val second = first.copy(
            messageNodes = listOf(
                node.copy(
                    messages = listOf(
                        message.copy(parts = listOf(UIMessagePart.Text("hello world"))),
                    ),
                ),
            ),
        )

        val firstKey = buildChatRenderCells(first, Settings(), loading = true)
            .filterIsInstance<ChatRenderCell.MarkdownCell>()
            .single()
            .stableKey
        val secondKey = buildChatRenderCells(second, Settings(), loading = true)
            .filterIsInstance<ChatRenderCell.MarkdownCell>()
            .single()
            .stableKey

        assertEquals(firstKey, secondKey)
    }

    @Test
    fun `streaming text override feeds stable content into active assistant cell`() {
        val message = UIMessage.assistant("hello world")
        val node = MessageNode.of(message)
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(node),
        )
        val key = ChatStreamingTextKey(
            nodeId = node.id,
            messageId = message.id,
            partIndex = 0,
        )

        val cell = buildChatRenderCells(
            conversation = conversation,
            settings = Settings(),
            loading = true,
            streamingTextOverrides = mapOf(key to "hello"),
        ).filterIsInstance<ChatRenderCell.MarkdownCell>().single()

        assertEquals("hello", cell.text)
    }

    private fun conversationOf(message: UIMessage): Conversation {
        return Conversation.ofId(
            id = Uuid.random(),
            messages = listOf(MessageNode.of(message)),
        )
    }
}
