package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class RichHtmlImagePresentationTest {
    @Test
    fun `img width and height attributes become presentation sizes`() {
        val html = """<img src="https://example.com/a.png" width="120" height="80">"""

        val image = RichHtmlCompiler.compile(html).blocks.single() as RichImageBlock

        assertEquals(120f, (image.style.width as RichSize.DpSize).value.value, 0.01f)
        assertEquals(80f, (image.style.height as RichSize.DpSize).value.value, 0.01f)
    }

    @Test
    fun `img css width wins over presentation width attribute`() {
        val html = """<img src="https://example.com/a.png" width="120" style="width: 64px;">"""

        val image = RichHtmlCompiler.compile(html).blocks.single() as RichImageBlock

        assertEquals(64f, (image.style.width as RichSize.DpSize).value.value, 0.01f)
    }
}
