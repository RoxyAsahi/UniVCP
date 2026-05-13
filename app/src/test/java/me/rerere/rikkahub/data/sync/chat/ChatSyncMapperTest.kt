package me.rerere.rikkahub.data.sync.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ChatSyncMapperTest {
    private val assistantId = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

    @Test
    fun `univcp export keeps only selected message branch by default`() {
        val conversation = Conversation(
            id = Uuid.parse("11111111-1111-4111-8111-111111111111"),
            assistantId = assistantId,
            title = "Branch test",
            createAt = Instant.ofEpochMilli(1_700_000_000_000),
            updateAt = Instant.ofEpochMilli(1_700_000_001_000),
            messageNodes = listOf(
                MessageNode.of(UIMessage.user("hello")),
                MessageNode(
                    messages = listOf(
                        UIMessage.assistant("old answer"),
                        UIMessage.assistant("selected answer"),
                    ),
                    selectIndex = 1,
                )
            ),
        )

        val sync = UniVcpChatSyncMapper.exportConversation(conversation)

        assertEquals(CHAT_SYNC_APP_UNIVCP, sync.source.app)
        assertEquals(true, sync.currentPathOnly)
        assertEquals(2, sync.messages.size)
        assertEquals("hello", sync.messages[0].parts.plainText())
        assertEquals("selected answer", sync.messages[1].parts.plainText())
    }

    @Test
    fun `univcp sync conversation imports into flat current-path conversation`() {
        val source = Conversation(
            id = Uuid.parse("22222222-2222-4222-8222-222222222222"),
            assistantId = assistantId,
            title = "Round trip",
            createAt = Instant.ofEpochMilli(1_700_000_000_000),
            updateAt = Instant.ofEpochMilli(1_700_000_001_000),
            messageNodes = listOf(
                MessageNode.of(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(
                            UIMessagePart.Text("look"),
                            UIMessagePart.Image("file:///tmp/image.png"),
                        ),
                    )
                ),
                MessageNode.of(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Text("nice image"),
                            UIMessagePart.Tool(
                                toolCallId = "tool-1",
                                toolName = "remember",
                                input = """{"value":"x"}""",
                                output = listOf(UIMessagePart.Text("ok")),
                            ),
                        ),
                    )
                ),
            ),
        )

        val sync = UniVcpChatSyncMapper.exportConversation(source)
        val imported = UniVcpChatSyncMapper.importConversation(sync, assistantId)

        assertEquals(source.id, imported.id)
        assertEquals(source.title, imported.title)
        assertEquals(2, imported.messageNodes.size)
        assertTrue(imported.currentMessages[0].parts[1] is UIMessagePart.Image)
        assertTrue(imported.currentMessages[1].parts[1] is UIMessagePart.Tool)
    }

    @Test
    fun `vcpchat history maps to canonical conversation and back`() {
        val history = Json.parseToJsonElement(
            """
            [
              {
                "role": "user",
                "name": "莱恩",
                "content": "你好",
                "timestamp": 1774957438466,
                "id": "msg_1774957438466_user_v684g8r",
                "attachments": [
                  {
                    "type": "image/png",
                    "src": "file:///C:/VCP/VCPChat/AppData/UserData/attachments/a.png",
                    "name": "a.png",
                    "size": 123,
                    "hash": "abc"
                  }
                ]
              },
              {
                "role": "assistant",
                "name": "Nova",
                "content": "<div id=\"vcp-root\">晚上好</div>",
                "timestamp": 1774957438472,
                "id": "msg_1774957438472_assistant_o5mpejm",
                "agentId": "_Agent_1",
                "finishReason": "completed"
              }
            ]
            """.trimIndent()
        ).jsonArray

        val sync = VcpChatSyncMapper.historyToSyncConversation(
            agentId = "_Agent_1",
            topicId = "topic_1",
            topicName = "主要对话",
            history = history,
            assistant = SyncAssistant(
                id = "_Agent_1",
                name = "Nova",
                systemPrompt = "{{Nova}}",
                source = SyncSource(app = CHAT_SYNC_APP_VCPCHAT, agentId = "_Agent_1"),
            ),
        )
        val exported = VcpChatSyncMapper.syncConversationToHistory(sync)

        assertEquals(CHAT_SYNC_APP_VCPCHAT, sync.source.app)
        assertEquals("Nova", sync.assistant?.name)
        assertEquals("{{Nova}}", sync.assistant?.systemPrompt)
        assertEquals("主要对话", sync.title)
        assertEquals(2, sync.messages.size)
        assertEquals(SyncAssetKind.IMAGE, (sync.messages[0].parts[1] as SyncMessagePart.Asset).kind)
        assertEquals("你好", exported[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("completed", exported[1].jsonObject["finishReason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `vcpchat non uuid ids import as stable univcp ids`() {
        val history = Json.parseToJsonElement(
            """
            [
              {
                "role": "user",
                "content": "same",
                "timestamp": 1774957438466,
                "id": "msg_non_uuid"
              }
            ]
            """.trimIndent()
        ).jsonArray
        val sync = VcpChatSyncMapper.historyToSyncConversation(
            agentId = "_Agent_1",
            topicId = "topic_1",
            topicName = "主要对话",
            history = history,
        )

        val first = UniVcpChatSyncMapper.importConversation(sync, assistantId)
        val second = UniVcpChatSyncMapper.importConversation(sync, assistantId)

        assertEquals(first.id, second.id)
        assertEquals(first.currentMessages.single().id, second.currentMessages.single().id)
    }

    @Test
    fun `firebase vcpchat envelope decodes and imports into univcp conversation`() {
        val envelope = Json { ignoreUnknownKeys = true }.decodeFromString<ChatSyncRemoteEnvelope>(
            """
            {
              "schemaVersion": 1,
              "id": "vcpchat:_Agent_1:topic_1",
              "updatedAt": 1774966400000,
              "sourceDeviceId": "vcpchat-desktop-chenxi",
              "conversation": {
                "id": "vcpchat:_Agent_1:topic_1",
                "title": "插件目录查找",
                "createdAt": 1774966396144,
                "updatedAt": 1774966400000,
                "deletedAt": null,
                "pinned": false,
                "source": {
                  "app": "vcpchat",
                  "agentId": "_Agent_1",
                  "topicId": "topic_1",
                  "itemType": "agent",
                  "raw": {}
                },
                "assistant": {
                  "id": "_Agent_1",
                  "name": "Nova",
                  "systemPrompt": "{{Nova}}",
                  "source": {
                    "app": "vcpchat",
                    "agentId": "_Agent_1",
                    "itemType": "agent",
                    "raw": {}
                  },
                  "metadata": {}
                },
                "messages": [
                  {
                    "id": "msg_1774966396144_user_tayngiy",
                    "role": "user",
                    "name": "莱恩",
                    "createdAt": 1774966396144,
                    "updatedAt": 1774966396144,
                    "source": {
                      "app": "vcpchat",
                      "agentId": "_Agent_1",
                      "topicId": "topic_1",
                      "itemType": "agent",
                      "raw": {}
                    },
                    "parts": [
                      {
                        "type": "text",
                        "text": "我想装这个项目的插件配置",
                        "metadata": null
                      }
                    ],
                    "metadata": {}
                  }
                ],
                "currentPathOnly": true,
                "metadata": {}
              }
            }
            """.trimIndent()
        )

        val decodedAssistant = envelope.conversation.assistant!!
        val importedAssistantId = UniVcpChatSyncMapper.stableAssistantId(decodedAssistant)
        val imported = UniVcpChatSyncMapper.importConversation(envelope.conversation, importedAssistantId)

        assertEquals("vcpchat", envelope.conversation.source.app)
        assertEquals("Nova", decodedAssistant.name)
        assertEquals("{{Nova}}", decodedAssistant.systemPrompt)
        assertEquals(importedAssistantId, imported.assistantId)
        assertEquals("插件目录查找", imported.title)
        assertEquals(1, imported.currentMessages.size)
        assertEquals("我想装这个项目的插件配置", (imported.currentMessages.single().parts.single() as UIMessagePart.Text).text)
    }
}
