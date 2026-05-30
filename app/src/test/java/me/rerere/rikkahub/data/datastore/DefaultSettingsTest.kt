package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSettingsTest {
    @Test
    fun `built in rikka provider keeps original name`() {
        val provider = DEFAULT_PROVIDERS.firstOrNull {
            it.id.toString() == "a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"
        }

        assertNotNull(provider)
        assertEquals("Rikka", provider!!.name)
    }

    @Test
    fun `univcp visual bubble is enabled by default`() {
        val injection = DEFAULT_MODE_INJECTIONS.firstOrNull {
            it.id == UNIVCP_RENDERING_MODE_INJECTION_ID
        }

        assertNotNull(injection)
        assertEquals("UniVCP Visual Bubble", injection!!.name)
        assertTrue(injection.enabled)
    }

    @Test
    fun `default assistant selects univcp visual bubble by default`() {
        val assistant = DEFAULT_ASSISTANTS.firstOrNull {
            it.id == DEFAULT_ASSISTANT_ID
        }

        assertNotNull(assistant)
        assertTrue(UNIVCP_RENDERING_MODE_INJECTION_ID in assistant!!.modeInjectionIds)
    }
}
