package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.content.SharedPreferences
import me.rerere.rikkahub.ui.components.message.RichContentDocumentSchemaVersion
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class RichRenderHeightCacheKey(
    val id: String,
    val widthDp: Int,
    val fontScaleBucket: Int,
    val densityBucket: Int,
    val themeBucket: String,
    val contentType: String,
    val rendererVersion: Int,
    val documentSchemaVersion: Int = RichContentDocumentSchemaVersion,
)

internal data class RichRenderHeightCacheEntry(
    val heightPx: Int,
    val confidence: RichRenderHeightConfidence,
    val updatedAtMs: Long,
)

internal enum class RichRenderHeightConfidence {
    Estimated,
    MeasuredNative,
    MeasuredSnapshot,
    MeasuredInlineWebView,
}

internal data class RichRenderHeightCacheStats(
    val size: Int,
    val maxEntries: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val confidence: Map<RichRenderHeightConfidence, Int>,
)

internal object RichRenderHeightCache {
    const val RendererVersion = 1
    const val MinHeightPx = 48
    const val MaxHeightPx = 12_000

    private const val PreferencesName = "rich_render_height_cache_v3a"
    private const val DefaultMaxEntries = 512
    private const val MemoryMaxEntries = 128
    private const val MeaningfulDeltaPx = 24
    private const val MeaningfulDeltaRatio = 0.05f

    private val lock = Any()
    private val memory = object : LinkedHashMap<RichRenderHeightCacheKey, RichRenderHeightCacheEntry>(
        MemoryMaxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RichRenderHeightCacheKey, RichRenderHeightCacheEntry>?,
        ): Boolean = size > MemoryMaxEntries
    }
    private var store: RichRenderHeightCacheStore = InMemoryRichRenderHeightCacheStore(DefaultMaxEntries)
    private var hits = 0L
    private var misses = 0L

    fun initialize(context: Context, maxEntries: Int = DefaultMaxEntries) = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (store is SharedPreferencesRichRenderHeightCacheStore) return@synchronized
        store = SharedPreferencesRichRenderHeightCacheStore(preferences, maxEntries)
        memory.clear()
        hits = 0
        misses = 0
    }

    fun key(
        id: String,
        viewportWidthDp: Float,
        fontScale: Float,
        density: Float = 1f,
        themeBucket: String = "default",
        contentType: String = "rich-html",
        rendererVersion: Int = RendererVersion,
        documentSchemaVersion: Int = RichContentDocumentSchemaVersion,
    ): RichRenderHeightCacheKey = RichRenderHeightCacheKey(
        id = id,
        widthDp = viewportWidthDp.roundToInt(),
        fontScaleBucket = (fontScale * 100).roundToInt(),
        densityBucket = (density * 100).roundToInt(),
        themeBucket = themeBucket.ifBlank { "default" },
        contentType = contentType.ifBlank { "rich-html" },
        rendererVersion = rendererVersion,
        documentSchemaVersion = documentSchemaVersion,
    )

    fun get(key: RichRenderHeightCacheKey): Int? = getEntry(key)?.heightPx

    fun getEntry(key: RichRenderHeightCacheKey): RichRenderHeightCacheEntry? = synchronized(lock) {
        memory[key]?.also {
            hits += 1
            store.touch(key.serialize())
            return@synchronized it
        }
        val serializedKey = key.serialize()
        val entry = store.read(serializedKey)?.toRichRenderHeightCacheEntry()
        if (entry == null) {
            misses += 1
        } else {
            hits += 1
            memory[key] = entry
            store.touch(serializedKey)
        }
        entry
    }

    fun put(
        key: RichRenderHeightCacheKey,
        heightPx: Int,
        confidence: RichRenderHeightConfidence,
        updatedAtMs: Long = System.currentTimeMillis(),
    ): RichRenderHeightCacheEntry? = synchronized(lock) {
        if (heightPx <= 0) return@synchronized null
        val clampedHeight = heightPx.coerceIn(MinHeightPx, MaxHeightPx)
        val existing = memory[key] ?: store.read(key.serialize())?.toRichRenderHeightCacheEntry()
        if (!shouldReplace(existing, clampedHeight, confidence)) {
            existing?.let { memory[key] = it }
            return@synchronized existing
        }
        val entry = RichRenderHeightCacheEntry(
            heightPx = clampedHeight,
            confidence = confidence,
            updatedAtMs = updatedAtMs,
        )
        memory[key] = entry
        store.write(key.serialize(), entry.serialize())
        syncMemoryToStore()
        entry
    }

    fun stats(): RichRenderHeightCacheStats = synchronized(lock) {
        RichRenderHeightCacheStats(
            size = store.size(),
            maxEntries = store.maxEntries,
            hits = hits,
            misses = misses,
            evictions = store.evictions,
            confidence = store.values()
                .mapNotNull { it.toRichRenderHeightCacheEntry()?.confidence }
                .groupingBy { it }
                .eachCount(),
        )
    }

    fun resetForTest() = synchronized(lock) {
        memory.clear()
        store.clear()
        hits = 0
        misses = 0
    }

    fun useInMemoryStoreForTest(maxEntries: Int = DefaultMaxEntries) = synchronized(lock) {
        memory.clear()
        store = InMemoryRichRenderHeightCacheStore(maxEntries)
        hits = 0
        misses = 0
    }

    fun serializedSnapshotForTest(): Map<String, String> = synchronized(lock) {
        store.snapshot()
    }

    private fun shouldReplace(
        existing: RichRenderHeightCacheEntry?,
        newHeightPx: Int,
        newConfidence: RichRenderHeightConfidence,
    ): Boolean {
        if (existing == null) return true
        if (newConfidence.ordinal < existing.confidence.ordinal) return false
        if (newConfidence.ordinal > existing.confidence.ordinal) return true
        val deltaPx = abs(newHeightPx - existing.heightPx)
        if (deltaPx < MeaningfulDeltaPx) return false
        val ratio = deltaPx.toFloat() / existing.heightPx.coerceAtLeast(1)
        return ratio >= MeaningfulDeltaRatio
    }

    private fun syncMemoryToStore() {
        val persistedKeys = store.snapshot().keys
        memory.entries.removeAll { (key, _) -> key.serialize() !in persistedKeys }
    }
}

private interface RichRenderHeightCacheStore {
    val maxEntries: Int
    val evictions: Long
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun touch(key: String)
    fun values(): List<String>
    fun size(): Int
    fun snapshot(): Map<String, String>
    fun clear()
}

private class InMemoryRichRenderHeightCacheStore(
    override val maxEntries: Int,
) : RichRenderHeightCacheStore {
    override var evictions: Long = 0L
        private set
    private val map = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            val remove = size > maxEntries
            if (remove) evictions += 1
            return remove
        }
    }

    override fun read(key: String): String? = map[key]

    override fun write(key: String, value: String) {
        map[key] = value
    }

    override fun touch(key: String) {
        map[key]
    }

    override fun values(): List<String> = map.values.toList()

    override fun size(): Int = map.size

    override fun snapshot(): Map<String, String> = map.toMap()

    override fun clear() {
        map.clear()
        evictions = 0
    }
}

private class SharedPreferencesRichRenderHeightCacheStore(
    private val preferences: SharedPreferences,
    override val maxEntries: Int,
) : RichRenderHeightCacheStore {
    override var evictions: Long = 0L
        private set

    override fun read(key: String): String? = preferences.getString(entryKey(key), null)

    override fun write(key: String, value: String) {
        val order = orderedKeys().filterNot { it == key }.toMutableList()
        order += key
        val editor = preferences.edit()
            .putString(entryKey(key), value)
            .putString(OrderKey, order.joinToString(OrderSeparator))
        trim(order, editor)
        editor.apply()
    }

    override fun touch(key: String) {
        if (!preferences.contains(entryKey(key))) return
        val order = orderedKeys().filterNot { it == key }.toMutableList()
        order += key
        preferences.edit()
            .putString(OrderKey, order.joinToString(OrderSeparator))
            .apply()
    }

    override fun values(): List<String> = orderedKeys().mapNotNull { read(it) }

    override fun size(): Int = orderedKeys().count { preferences.contains(entryKey(it)) }

    override fun snapshot(): Map<String, String> = orderedKeys()
        .mapNotNull { key -> read(key)?.let { value -> key to value } }
        .toMap()

    override fun clear() {
        val editor = preferences.edit()
        orderedKeys().forEach { key -> editor.remove(entryKey(key)) }
        editor.remove(OrderKey).apply()
        evictions = 0
    }

    private fun trim(order: MutableList<String>, editor: SharedPreferences.Editor) {
        while (order.size > maxEntries) {
            val removed = order.removeFirst()
            editor.remove(entryKey(removed))
            evictions += 1
        }
        editor.putString(OrderKey, order.joinToString(OrderSeparator))
    }

    private fun orderedKeys(): List<String> = preferences.getString(OrderKey, null)
        ?.split(OrderSeparator)
        ?.filter { it.isNotBlank() }
        .orEmpty()

    private fun entryKey(key: String): String = "$EntryPrefix$key"

    private companion object {
        const val EntryPrefix = "entry:"
        const val OrderKey = "order"
        const val OrderSeparator = "\n"
    }
}

private fun RichRenderHeightCacheKey.serialize(): String = buildString {
    append("v2")
    append("|id=").append(id.safeEncode())
    append("|w=").append(widthDp)
    append("|fs=").append(fontScaleBucket)
    append("|d=").append(densityBucket)
    append("|theme=").append(themeBucket.safeEncode())
    append("|type=").append(contentType.safeEncode())
    append("|rv=").append(rendererVersion)
    append("|doc=").append(documentSchemaVersion)
}

private fun RichRenderHeightCacheEntry.serialize(): String =
    "$heightPx|${confidence.name}|$updatedAtMs"

private fun String.toRichRenderHeightCacheEntry(): RichRenderHeightCacheEntry? {
    val parts = split('|')
    if (parts.size != 3) return null
    val heightPx = parts[0].toIntOrNull() ?: return null
    val confidence = runCatching { RichRenderHeightConfidence.valueOf(parts[1]) }.getOrNull() ?: return null
    val updatedAtMs = parts[2].toLongOrNull() ?: return null
    return RichRenderHeightCacheEntry(
        heightPx = heightPx.coerceIn(RichRenderHeightCache.MinHeightPx, RichRenderHeightCache.MaxHeightPx),
        confidence = confidence,
        updatedAtMs = updatedAtMs,
    )
}

private fun String.safeEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
