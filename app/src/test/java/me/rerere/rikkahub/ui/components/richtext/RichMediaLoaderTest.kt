package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichMediaLoaderTest {
    @Test
    fun `request key is stable and metadata only`() {
        val secretUrl = "https://example.test/private/image.png?token=secret"
        val first = RichMediaRequest.fromSource(secretUrl, RichMediaKind.Image, widthPx = 120, heightPx = 80, themeBucket = "dark")
        val second = RichMediaRequest.fromSource(secretUrl, RichMediaKind.Image, widthPx = 120, heightPx = 80, themeBucket = "dark")
        val resized = RichMediaRequest.fromSource(secretUrl, RichMediaKind.Image, widthPx = 240, heightPx = 80, themeBucket = "dark")

        assertEquals(first.key, second.key)
        assertNotEquals(first.key, resized.key)
        assertTrue(first.key.contains("sha256"))
        assertTrue(!first.key.contains("secret"))
    }

    @Test
    fun `unsafe protocol is rejected`() {
        val request = RichMediaRequest.fromSource("javascript:alert(1)", RichMediaKind.Image)

        assertEquals(RichMediaSafety.UnsupportedProtocol, RichMediaLoader.safety(request))
        assertNull(RichMediaLoader.safeData(request))
    }

    @Test
    fun `data uri handling only allows images`() {
        val image = RichMediaRequest.fromSource("data:image/png;base64,AAAA", RichMediaKind.Image)
        val html = RichMediaRequest.fromSource("data:text/html;base64,AAAA", RichMediaKind.Image)

        assertEquals(RichMediaSafety.Safe, RichMediaLoader.safety(image))
        assertEquals(RichMediaSafety.DataUriRejected, RichMediaLoader.safety(html))
    }

    @Test
    fun `local file access is explicitly rejected for rich media`() {
        val request = RichMediaRequest.fromSource("file:///sdcard/private.png", RichMediaKind.Image)

        assertEquals(RichMediaSafety.LocalFileRejected, RichMediaLoader.safety(request))
    }
}
