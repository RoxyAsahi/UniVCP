package me.rerere.rikkahub.data.sync.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class VcpChatEmoticonUrlFixerTest {
    private val library = listOf(
        VcpChatEmoticonItem(
            url = "http://127.0.0.1:6005/pw=right/images/Nova%E8%A1%A8%E6%83%85%E5%8C%85/wave.png",
            category = "Nova表情包",
            filename = "wave.png",
            searchKey = "nova表情包/wave.png",
        ),
        VcpChatEmoticonItem(
            url = "http://127.0.0.1:6005/pw=right/images/Nova%E8%A1%A8%E6%83%85%E5%8C%85/smile.gif",
            category = "Nova表情包",
            filename = "smile.gif",
            searchKey = "nova表情包/smile.gif",
        ),
    )

    @Test
    fun `keeps exact match unchanged`() {
        val original = library.first().url

        assertEquals(original, VcpChatEmoticonUrlFixer.fix(original, library))
    }

    @Test
    fun `repairs likely emoticon url by package and filename similarity`() {
        val broken = "http://localhost:6005/pw=wrong/images/Nova表情包/wvae.png"

        assertEquals(library.first().url, VcpChatEmoticonUrlFixer.fix(broken, library))
    }

    @Test
    fun `does not touch non emoticon images`() {
        val url = "https://example.com/images/wvae.png"

        assertEquals(url, VcpChatEmoticonUrlFixer.fix(url, library))
    }
}
