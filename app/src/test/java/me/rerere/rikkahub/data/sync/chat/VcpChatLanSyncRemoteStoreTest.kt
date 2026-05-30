package me.rerere.rikkahub.data.sync.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VcpChatLanSyncRemoteStoreTest {
    private lateinit var server: HttpServer
    private lateinit var store: VcpChatLanSyncRemoteStore
    private val requests = mutableListOf<RecordedRequest>()
    private val json = JsonInstant
    private val conversation = SyncConversation(
        id = "vcpchat:_Agent_1:topic_1",
        title = "LAN topic",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        source = SyncSource(
            app = CHAT_SYNC_APP_VCPCHAT,
            agentId = "_Agent_1",
            topicId = "topic_1",
            itemType = "agent",
        ),
        assistant = SyncAssistant(
            id = "_Agent_1",
            name = "Nova",
            source = SyncSource(app = CHAT_SYNC_APP_VCPCHAT, agentId = "_Agent_1"),
        ),
        messages = listOf(
            SyncMessage(
                id = "msg_1",
                role = MessageRole.USER,
                createdAt = 1_700_000_000_500L,
                source = SyncSource(app = CHAT_SYNC_APP_VCPCHAT, agentId = "_Agent_1", topicId = "topic_1"),
                parts = listOf(SyncMessagePart.Text("hello")),
            )
        ),
    )

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += RecordedRequest(
                method = exchange.requestMethod,
                path = path,
                authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty(),
                token = exchange.requestHeaders.getFirst("x-chat-sync-token").orEmpty(),
                body = if (exchange.requestMethod == "GET") {
                    ""
                } else {
                    exchange.requestBody.bufferedReader().use { it.readText() }
                },
            )
            if (path == "/chat-sync/events") {
                exchange.respondSse(
                    "event: topic-changed\n" +
                        "data: {\"type\":\"topic-changed\",\"itemType\":\"agent\",\"itemId\":\"_Agent_1\"," +
                        "\"topicId\":\"topic_1\",\"updatedAt\":1700000002000,\"sourceDeviceId\":\"vcpchat-test\"}\n\n"
                )
                return@createContext
            }
            exchange.respond(responseFor(exchange.requestURI.path))
        }
        server.start()

        store = VcpChatLanSyncRemoteStore(
            client = OkHttpClient(),
            config = VcpChatLanSyncConfig(
                baseUrl = "http://127.0.0.1:${server.address.port}",
                authToken = "secret",
                deviceId = "univcp-test",
                pollIntervalMs = 1_000L,
                targetItemType = "group",
                targetItemId = "_Group_1",
                targetTopicId = "topic_mobile",
            ),
            json = json,
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `requests lan bridge endpoints with auth and decodes responses`() = runBlocking {
        val diagnostics = store.getDiagnostics()
        val targets = store.listTargets()
        val emoticons = store.fetchEmoticonLibrary()
        val topic = store.exportTopic(itemId = "_Agent_1", topicId = "topic_1")
        val pull = store.pullChanges(ChatSyncCursor(updatedAfter = 1_000L))
        val push = store.pushChanges(listOf(conversation))

        assertTrue(diagnostics.ok)
        assertEquals("_Agent_1", targets.items.single().id)
        assertEquals("wave.png", emoticons.items.single().filename)
        assertEquals("vcpchat:_Agent_1:topic_1", topic.id)
        assertEquals(listOf(conversation.id), pull.conversations.map { it.id })
        assertEquals(conversation.updatedAt, pull.nextCursor?.updatedAfter)
        assertEquals(listOf(conversation.id), push.pushedConversationIds)

        assertEquals(
            listOf(
                "/chat-sync/diagnostics",
                "/chat-sync/list-targets",
                "/chat-sync/emoticons",
                "/chat-sync/export-topic",
                "/chat-sync/export-all",
                "/chat-sync/import-topic",
            ),
            requests.map { it.path },
        )
        assertTrue(requests.all { it.authorization == "Bearer secret" })
        assertTrue(requests.all { it.token == "secret" })
        assertTrue(requests.first { it.path == "/chat-sync/export-all" }.body.contains("\"updatedAfter\":1000"))
        val importBody = requests.first { it.path == "/chat-sync/import-topic" }.body
        assertTrue(importBody.contains(conversation.id))
        assertTrue(importBody.contains("\"itemType\":\"group\""))
        assertTrue(importBody.contains("\"targetItemId\":\"_Group_1\""))
        assertTrue(importBody.contains("\"targetTopicId\":\"topic_mobile\""))
    }

    @Test
    fun `uses import bundle for batched lan pushes`() = runBlocking {
        val second = conversation.copy(
            id = "vcpchat:_Agent_1:topic_2",
            title = "Second topic",
            updatedAt = conversation.updatedAt + 1,
            source = conversation.source.copy(topicId = "topic_2"),
        )

        val push = store.pushChanges(listOf(conversation, second))

        assertEquals(listOf(conversation.id, second.id), push.pushedConversationIds)
        assertEquals(listOf("/chat-sync/import-bundle"), requests.map { it.path })
        val importBody = requests.single().body
        assertTrue(importBody.contains(conversation.id))
        assertTrue(importBody.contains(second.id))
        assertTrue(importBody.contains("\"targetItemId\":\"_Group_1\""))
    }

    @Test
    fun `observe changes uses sse topic events to export changed topic`() = runBlocking {
        val event = withTimeout(5_000L) {
            store.observeChanges(null).first()
        }

        assertEquals(conversation.id, event.conversation.id)
        assertEquals(conversation.updatedAt, event.conversation.updatedAt)
        assertEquals(1_700_000_002_000L, event.cursor.updatedAfter)
        assertEquals("vcpchat-test", event.sourceDeviceId)
        assertEquals(
            listOf("/chat-sync/events", "/chat-sync/export-topic"),
            requests.map { it.path },
        )
        assertTrue(requests.first { it.path == "/chat-sync/events" }.authorization == "Bearer secret")
    }

    private fun responseFor(path: String): String {
        return when (path) {
            "/chat-sync/diagnostics" -> """
                {
                  "ok": true,
                  "checkedAt": 1700000002000,
                  "status": {
                    "deviceId": "vcpchat-test",
                    "started": true,
                    "version": "0.1.0"
                  },
                  "checks": [
                    { "name": "service_started", "ok": true, "detail": "service loop is active" }
                  ]
                }
            """.trimIndent()

            "/chat-sync/list-targets" -> """
                {
                  "status": "success",
                  "result": {
                    "appDataDir": "C:/VCP/VCPChat/AppData",
                    "items": [
                      {
                        "id": "_Agent_1",
                        "itemType": "agent",
                        "name": "Nova",
                        "topics": [
                          { "id": "topic_1", "name": "LAN topic" }
                        ]
                      }
                    ]
                  }
                }
            """.trimIndent()

            "/chat-sync/export-topic" -> """
                {
                  "status": "success",
                  "result": ${json.encodeToString(conversation)}
                }
            """.trimIndent()

            "/chat-sync/emoticons" -> """
                {
                  "status": "success",
                  "result": {
                    "sourceApp": "vcpchat",
                    "appDataDir": "C:/VCP/VCPChat/AppData",
                    "generatedAt": 1700000002000,
                    "count": 1,
                    "items": [
                      {
                        "url": "http://127.0.0.1:6005/pw=secret/images/Nova%E8%A1%A8%E6%83%85%E5%8C%85/wave.png",
                        "category": "Nova表情包",
                        "filename": "wave.png",
                        "searchKey": "nova表情包/wave.png"
                      }
                    ]
                  }
                }
            """.trimIndent()

            "/chat-sync/export-all" -> {
                val bundle = ChatSyncBundle(
                    exportedAt = 1_700_000_002_000L,
                    conversations = listOf(conversation),
                )
                """
                    {
                      "status": "success",
                      "result": ${json.encodeToString(bundle)}
                    }
                """.trimIndent()
            }

            "/chat-sync/import-bundle" -> """
                {
                  "status": "success",
                  "result": {
                    "imported": 1,
                    "results": []
                  }
                }
            """.trimIndent()

            "/chat-sync/import-topic" -> """
                {
                  "status": "success",
                  "result": {
                    "changed": true
                  }
                }
            """.trimIndent()

            else -> error("Unexpected path: $path")
        }
    }

    private fun HttpExchange.respond(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondSse(body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "text/event-stream")
        responseHeaders.add("Cache-Control", "no-cache")
        sendResponseHeaders(200, 0)
        responseBody.write(bytes)
        responseBody.flush()
        Thread.sleep(5_000L)
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val authorization: String,
        val token: String,
        val body: String,
    )
}
