package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RichContentDocumentSchemaVersion
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichRenderHeightCacheTest {
    @After
    fun tearDown() {
        RichRenderHeightCache.useInMemoryStoreForTest()
    }

    @Test
    fun `width buckets are separated`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val narrow = key(widthDp = 320f)
        val wide = key(widthDp = 480f)

        RichRenderHeightCache.put(narrow, 240, RichRenderHeightConfidence.MeasuredNative)

        assertEquals(240, RichRenderHeightCache.get(narrow))
        assertNull(RichRenderHeightCache.get(wide))
    }

    @Test
    fun `font scale buckets are separated`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val normal = key(fontScale = 1f)
        val large = key(fontScale = 1.3f)

        RichRenderHeightCache.put(normal, 260, RichRenderHeightConfidence.MeasuredNative)

        assertEquals(260, RichRenderHeightCache.get(normal))
        assertNull(RichRenderHeightCache.get(large))
    }

    @Test
    fun `density theme and content type are separated`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val base = key(density = 2f, themeBucket = "light", contentType = "RichHtmlCell")
        val density = key(density = 3f, themeBucket = "light", contentType = "RichHtmlCell")
        val theme = key(density = 2f, themeBucket = "dark", contentType = "RichHtmlCell")
        val contentType = key(density = 2f, themeBucket = "light", contentType = "SnapshotCell")

        RichRenderHeightCache.put(base, 280, RichRenderHeightConfidence.MeasuredNative)

        assertEquals(280, RichRenderHeightCache.get(base))
        assertNull(RichRenderHeightCache.get(density))
        assertNull(RichRenderHeightCache.get(theme))
        assertNull(RichRenderHeightCache.get(contentType))
    }

    @Test
    fun `renderer version separates entries`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val v1 = key(rendererVersion = 1)
        val v2 = key(rendererVersion = 2)

        RichRenderHeightCache.put(v1, 300, RichRenderHeightConfidence.MeasuredNative)

        assertEquals(300, RichRenderHeightCache.get(v1))
        assertNull(RichRenderHeightCache.get(v2))
    }

    @Test
    fun `document schema version separates entries`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val current = key(documentSchemaVersion = RichContentDocumentSchemaVersion)
        val next = key(documentSchemaVersion = RichContentDocumentSchemaVersion + 1)

        RichRenderHeightCache.put(current, 312, RichRenderHeightConfidence.MeasuredNative)

        assertEquals(312, RichRenderHeightCache.get(current))
        assertNull(RichRenderHeightCache.get(next))
    }

    @Test
    fun `higher confidence is not overwritten by lower confidence`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val key = key()

        RichRenderHeightCache.put(key, 420, RichRenderHeightConfidence.MeasuredNative)
        RichRenderHeightCache.put(key, 800, RichRenderHeightConfidence.Estimated)

        val entry = RichRenderHeightCache.getEntry(key)
        assertEquals(420, entry?.heightPx)
        assertEquals(RichRenderHeightConfidence.MeasuredNative, entry?.confidence)
    }

    @Test
    fun `same confidence requires meaningful delta before updating`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val key = key()

        RichRenderHeightCache.put(key, 1_000, RichRenderHeightConfidence.MeasuredNative)
        RichRenderHeightCache.put(key, 1_020, RichRenderHeightConfidence.MeasuredNative)
        assertEquals(1_000, RichRenderHeightCache.get(key))

        RichRenderHeightCache.put(key, 1_080, RichRenderHeightConfidence.MeasuredNative)
        assertEquals(1_080, RichRenderHeightCache.get(key))
    }

    @Test
    fun `higher confidence can update without meaningful delta`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val key = key()

        RichRenderHeightCache.put(key, 500, RichRenderHeightConfidence.Estimated)
        RichRenderHeightCache.put(key, 508, RichRenderHeightConfidence.MeasuredNative)

        val entry = RichRenderHeightCache.getEntry(key)
        assertEquals(508, entry?.heightPx)
        assertEquals(RichRenderHeightConfidence.MeasuredNative, entry?.confidence)
    }

    @Test
    fun `snapshot and inline measured confidence entries are persisted`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val snapshot = key(id = "snapshot", contentType = "RichHtmlCell")
        val inline = key(id = "inline", contentType = "InlineDynamicWebView")

        RichRenderHeightCache.put(snapshot, 640, RichRenderHeightConfidence.MeasuredSnapshot)
        RichRenderHeightCache.put(inline, 720, RichRenderHeightConfidence.MeasuredInlineWebView)

        assertEquals(RichRenderHeightConfidence.MeasuredSnapshot, RichRenderHeightCache.getEntry(snapshot)?.confidence)
        assertEquals(640, RichRenderHeightCache.getEntry(snapshot)?.heightPx)
        assertEquals(
            RichRenderHeightConfidence.MeasuredInlineWebView,
            RichRenderHeightCache.getEntry(inline)?.confidence,
        )
        assertEquals(720, RichRenderHeightCache.getEntry(inline)?.heightPx)
    }

    @Test
    fun `height is clamped to safe range`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val short = key(id = "short")
        val tall = key(id = "tall")

        RichRenderHeightCache.put(short, 1, RichRenderHeightConfidence.MeasuredNative)
        RichRenderHeightCache.put(tall, 80_000, RichRenderHeightConfidence.MeasuredNative)

        assertEquals(RichRenderHeightCache.MinHeightPx, RichRenderHeightCache.get(short))
        assertEquals(RichRenderHeightCache.MaxHeightPx, RichRenderHeightCache.get(tall))
    }

    @Test
    fun `entry count is bounded and older keys are evicted`() {
        RichRenderHeightCache.useInMemoryStoreForTest(maxEntries = 3)
        val first = key(id = "digest-0")
        RichRenderHeightCache.put(first, 100, RichRenderHeightConfidence.MeasuredNative)
        RichRenderHeightCache.put(key(id = "digest-1"), 200, RichRenderHeightConfidence.MeasuredNative)
        RichRenderHeightCache.put(key(id = "digest-2"), 300, RichRenderHeightConfidence.MeasuredNative)
        RichRenderHeightCache.put(key(id = "digest-3"), 400, RichRenderHeightConfidence.MeasuredNative)

        val stats = RichRenderHeightCache.stats()
        assertEquals(3, stats.size)
        assertTrue(stats.evictions >= 1)
        assertNull(RichRenderHeightCache.get(first))
    }

    @Test
    fun `serialized keys and values remain metadata only`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val rawText = "文字游龙 secret html <button>发送</button>"
        val key = key(id = renderTextCacheKey(rawText))

        RichRenderHeightCache.put(key, 360, RichRenderHeightConfidence.MeasuredNative)

        val serialized = RichRenderHeightCache.serializedSnapshotForTest()
            .flatMap { (key, value) -> listOf(key, value) }
            .joinToString("\n")
        assertFalse(serialized.contains(rawText))
        assertFalse(serialized.contains("发送"))
        assertFalse(serialized.contains("<button>"))
        assertTrue(serialized.contains("MeasuredNative"))
        assertTrue(serialized.contains("doc=$RichContentDocumentSchemaVersion"))
    }

    @Test
    fun `reset clears test store`() {
        RichRenderHeightCache.useInMemoryStoreForTest()
        val key = key()
        RichRenderHeightCache.put(key, 360, RichRenderHeightConfidence.MeasuredNative)

        RichRenderHeightCache.resetForTest()

        assertNull(RichRenderHeightCache.get(key))
        assertEquals(0, RichRenderHeightCache.stats().size)
    }

    private fun key(
        id: String = "digest",
        widthDp: Float = 360f,
        fontScale: Float = 1f,
        density: Float = 2f,
        themeBucket: String = "light",
        contentType: String = "RichHtmlCell",
        rendererVersion: Int = RichRenderHeightCache.RendererVersion,
        documentSchemaVersion: Int = RichContentDocumentSchemaVersion,
    ): RichRenderHeightCacheKey = RichRenderHeightCache.key(
        id = id,
        viewportWidthDp = widthDp,
        fontScale = fontScale,
        density = density,
        themeBucket = themeBucket,
        contentType = contentType,
        rendererVersion = rendererVersion,
        documentSchemaVersion = documentSchemaVersion,
    )
}
