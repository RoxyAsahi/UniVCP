package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHtmlSnapshotCacheTest {
    @Test
    fun `snapshot key is stable and scoped by rendering inputs`() {
        val html = """<div id="response-root">hello</div>"""
        val key = RichHtmlSnapshotCacheKey.create(
            html = html,
            widthPx = 720,
            density = 2.75f,
            fontScale = 1.0f,
            dark = false,
            themeSignature = "light|#fff|#111",
        )

        assertEquals(
            key,
            RichHtmlSnapshotCacheKey.create(
                html = html,
                widthPx = 720,
                density = 2.75f,
                fontScale = 1.0f,
                dark = false,
                themeSignature = "light|#fff|#111",
            ),
        )
        assertNotEquals(key, key.copy(widthPx = 800))
        assertNotEquals(key, key.copy(dark = true))
        assertNotEquals(key, key.copy(fontScaleBucket = 125))
        assertTrue(key.htmlKey.contains("sha256"))
        assertTrue(!key.htmlKey.contains("response-root"))
    }

    @Test
    fun `concurrent snapshot requests share in flight render`() = runBlocking {
        RichHtmlSnapshotCache.clearForTest()
        val key = RichHtmlSnapshotCacheKey.create(
            html = """<div id="response-root">shared</div>""",
            widthPx = 720,
            density = 2f,
            fontScale = 1f,
            dark = false,
            themeSignature = "light|#fff|#111",
        )
        var renderCount = 0

        val first = async {
            RichHtmlSnapshotCache.getOrRenderResult(key) {
                renderCount += 1
                delay(80)
                null
            }
        }
        delay(10)
        val second = async {
            RichHtmlSnapshotCache.getOrRenderResult(key) {
                renderCount += 1
                null
            }
        }

        val firstResult = first.await()
        val secondResult = second.await()

        assertEquals(1, renderCount)
        assertEquals(false, firstResult.cacheHit)
        assertEquals(false, firstResult.joinedInFlight)
        assertEquals(true, secondResult.joinedInFlight)
        assertEquals(0, RichHtmlSnapshotCache.inFlightCountForTest())
    }
}
