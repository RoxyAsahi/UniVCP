package me.rerere.rikkahub.ui.components.render

import java.security.MessageDigest

internal class RenderLruCache<K, V>(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private var hitCount = 0L
    private var missCount = 0L
    private var evictionCount = 0L
    private val map = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            val remove = size > maxEntries
            if (remove) evictionCount += 1
            return remove
        }
    }

    fun get(key: K): V? = synchronized(lock) {
        map[key].also { value ->
            if (value == null) missCount += 1 else hitCount += 1
        }
    }

    fun getOrPut(key: K, factory: () -> V): V {
        get(key)?.let { return it }
        val value = factory()
        return synchronized(lock) {
            map[key] ?: value.also { map[key] = it }
        }
    }

    fun putIfAbsent(key: K, value: V): V = synchronized(lock) {
        map[key] ?: value.also { map[key] = it }
    }

    fun stats(): RenderLruCacheStats = synchronized(lock) {
        RenderLruCacheStats(
            size = map.size,
            maxEntries = maxEntries,
            hits = hitCount,
            misses = missCount,
            evictions = evictionCount,
        )
    }

    fun clear() = synchronized(lock) {
        map.clear()
        hitCount = 0
        missCount = 0
        evictionCount = 0
    }
}

internal data class RenderLruCacheStats(
    val size: Int,
    val maxEntries: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
)

internal fun renderTextCacheKey(text: String): String {
    val digest = MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .take(CACHE_KEY_DIGEST_BYTES)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "${text.length}:sha256:$digest"
}

private const val CACHE_KEY_DIGEST_BYTES = 16
