package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSettingsTest {
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
