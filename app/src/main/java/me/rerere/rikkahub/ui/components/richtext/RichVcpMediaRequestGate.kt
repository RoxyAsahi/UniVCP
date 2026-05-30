package me.rerere.rikkahub.ui.components.richtext

private const val VCP_MEDIA_FAILURE_TTL_MS = 60_000L
private const val VCP_MEDIA_FAILURE_CACHE_MAX_SIZE = 256

internal object RichVcpMediaRequestGate {
    private val failedUntil = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > VCP_MEDIA_FAILURE_CACHE_MAX_SIZE
        }
    }

    @Synchronized
    fun shouldSkip(request: RichMediaRequest): Boolean {
        val key = request.vcpCacheKey() ?: return false
        val until = failedUntil[key] ?: return false
        if (until <= now()) {
            failedUntil.remove(key)
            return false
        }
        return true
    }

    @Synchronized
    fun markFailure(request: RichMediaRequest) {
        val key = request.vcpCacheKey() ?: return
        failedUntil[key] = now() + VCP_MEDIA_FAILURE_TTL_MS
    }

    @Synchronized
    fun markSuccess(request: RichMediaRequest) {
        val key = request.vcpCacheKey() ?: return
        failedUntil.remove(key)
    }

    @Synchronized
    fun clearForTests() {
        failedUntil.clear()
    }

    private fun now(): Long = System.currentTimeMillis()
}

internal fun RichMediaRequest.vcpCacheKey(): String? {
    if (!RichVcpMediaResolver.isVcpMediaUrl(rawSource)) return null
    return "vcp-media:$key"
}
