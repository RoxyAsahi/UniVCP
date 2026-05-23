package me.rerere.rikkahub.data.renderseed

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RenderSeedFixtureTest {
    @Test
    fun `debug render seed fixture is valid and representative`() {
        val fixture = File("src/debug/assets/render_seed/chat_render_seed.json")
        assertTrue("Missing render seed fixture at ${fixture.absolutePath}", fixture.exists())

        val root = Json.parseToJsonElement(fixture.readText()).jsonObject
        val assistants = root["assistants"]!!.jsonArray
        val conversations = root["conversations"]!!.jsonArray

        assertEquals(2, assistants.size)
        assertEquals(9, conversations.size)

        val ids = mutableSetOf<String>()
        assistants.forEach { assistant ->
            val id = assistant.jsonObject.string("id")
            Uuid.parse(id)
            assertTrue("Duplicate assistant id: $id", ids.add(id))
            assertTrue(assistant.jsonObject.string("name").isNotBlank())
            assertTrue(assistant.jsonObject.string("systemPrompt").isNotBlank())
        }

        conversations.forEach { conversation ->
            val objectValue = conversation.jsonObject
            val id = objectValue.string("id")
            Uuid.parse(id)
            assertTrue("Duplicate conversation id: $id", ids.add(id))
            assertTrue(objectValue.string("title").startsWith("Seed:"))

            val messages = objectValue["messages"]!!.jsonArray
            assertTrue(messages.size >= 2)
            messages.forEach { message ->
                val messageObject = message.jsonObject
                val messageId = messageObject.string("id")
                val nodeId = messageObject.string("nodeId")
                Uuid.parse(messageId)
                Uuid.parse(nodeId)
                assertTrue("Duplicate message id: $messageId", ids.add(messageId))
                assertTrue("Duplicate node id: $nodeId", ids.add(nodeId))
                assertTrue(messageObject["parts"] is JsonArray)
            }
        }

        val allText = fixture.readText()
        assertTrue(allText.contains("```mermaid"))
        assertTrue(allText.contains("$$"))
        assertTrue(allText.contains("<div id=\\\"vcp-root\\\""))
        assertTrue(allText.contains("vcp-card-header"))
        assertTrue(allText.contains("action-btn"))
        assertTrue(allText.contains("window-item"))
        assertTrue(allText.contains("<details"))
        assertTrue(allText.contains("<svg"))
        assertTrue(allText.contains("VCPDesktop"))
        assertTrue(allText.contains("@keyframes"))
        assertTrue(allText.contains("vcp-net-widget"))
        assertTrue(allText.contains("<button"))
        assertTrue(allText.contains("math-block"))
        assertTrue(allText.contains("&lt;script&gt;"))
        assertTrue(allText.contains("\"type\": \"tool\""))
        assertTrue(allText.contains("\"type\": \"image\""))
        assertTrue(allText.contains("\"type\": \"document\""))
    }
}

private fun JsonObject.string(key: String): String {
    return this[key]!!.jsonPrimitive.content
}
