package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewTest {
    @Test
    fun `console log line is metadata only`() {
        val secret = "webview-secret-message"
        val line = webViewConsoleLogLine(level = "ERROR", lineNumber = 7)

        assertTrue(line.contains("level=ERROR"))
        assertTrue(line.contains("line=7"))
        assertFalse(line.contains(secret))
        assertFalse(line.contains("sourceId"))
    }
}
