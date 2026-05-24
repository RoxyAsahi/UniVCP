package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import kotlin.math.roundToInt

internal const val RICH_HTML_SNAPSHOT_RENDERER_VERSION = 1

internal data class RichHtmlSnapshotCacheKey(
    val htmlKey: String,
    val widthPx: Int,
    val densityBucket: Int,
    val fontScaleBucket: Int,
    val dark: Boolean,
    val themeHash: Int,
    val rendererVersion: Int = RICH_HTML_SNAPSHOT_RENDERER_VERSION,
) {
    companion object {
        fun create(
            html: String,
            widthPx: Int,
            density: Float,
            fontScale: Float,
            dark: Boolean,
            themeSignature: String,
        ): RichHtmlSnapshotCacheKey {
            return RichHtmlSnapshotCacheKey(
                htmlKey = renderTextCacheKey(html),
                widthPx = widthPx,
                densityBucket = (density * 100).roundToInt(),
                fontScaleBucket = (fontScale * 100).roundToInt(),
                dark = dark,
                themeHash = themeSignature.hashCode(),
            )
        }
    }
}

internal data class RichHtmlSnapshotEntry(
    val bitmap: Bitmap,
    val widthPx: Int,
    val heightPx: Int,
    val renderTimeMs: Long,
    val queueWaitMs: Long = 0L,
    val sessionReused: Boolean = false,
) {
    val byteCount: Int = runCatching { bitmap.allocationByteCount }.getOrDefault(widthPx * heightPx * 4)
}

internal data class RichHtmlSnapshotCacheResult(
    val entry: RichHtmlSnapshotEntry?,
    val cacheHit: Boolean,
    val joinedInFlight: Boolean,
    val waitTimeMs: Long,
)

internal object RichHtmlSnapshotCache {
    private const val MAX_ENTRIES = 24
    private const val MAX_BYTES = 48 * 1024 * 1024

    private val lock = Any()
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = object : LinkedHashMap<RichHtmlSnapshotCacheKey, RichHtmlSnapshotEntry>(MAX_ENTRIES, 0.75f, true) {}
    private val inFlight = mutableMapOf<RichHtmlSnapshotCacheKey, InFlightRender>()
    private var totalBytes = 0

    fun get(key: RichHtmlSnapshotCacheKey): RichHtmlSnapshotEntry? = synchronized(lock) {
        entries[key]
    }

    suspend fun getOrRender(
        key: RichHtmlSnapshotCacheKey,
        render: suspend () -> RichHtmlSnapshotEntry?,
    ): RichHtmlSnapshotEntry? {
        return getOrRenderResult(key, render).entry
    }

    suspend fun getOrRenderResult(
        key: RichHtmlSnapshotCacheKey,
        render: suspend () -> RichHtmlSnapshotEntry?,
    ): RichHtmlSnapshotCacheResult {
        val requestedAtMs = System.currentTimeMillis()
        get(key)?.let {
            return RichHtmlSnapshotCacheResult(
                entry = it,
                cacheHit = true,
                joinedInFlight = false,
                waitTimeMs = 0L,
            )
        }

        val createdOrJoined = synchronized(lock) {
            val existing = inFlight[key]
            if (existing != null) {
                existing.waiters += 1
                existing to true
            } else {
                val deferred = renderScope.async {
                render()?.also { put(key, it) }
                }
                val created = InFlightRender(deferred = deferred, waiters = 1)
                inFlight[key] = created
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight[key] === created && created.waiters <= 0) {
                            inFlight.remove(key)
                        }
                    }
                }
                created to false
            }
        }
        val flight = createdOrJoined.first
        val joined = createdOrJoined.second
        return try {
            val entry = flight.deferred.await()
            RichHtmlSnapshotCacheResult(
                entry = entry,
                cacheHit = false,
                joinedInFlight = joined,
                waitTimeMs = (System.currentTimeMillis() - requestedAtMs).coerceAtLeast(0L),
            )
        } finally {
            synchronized(lock) {
                flight.waiters -= 1
                if (inFlight[key] === flight && flight.deferred.isCompleted && flight.waiters <= 0) {
                    inFlight.remove(key)
                }
            }
        }
    }

    fun clearForTest() = synchronized(lock) {
        entries.clear()
        inFlight.values.forEach { it.deferred.cancel() }
        inFlight.clear()
        totalBytes = 0
    }

    fun statsForTest(): Pair<Int, Int> = synchronized(lock) {
        entries.size to totalBytes
    }

    fun inFlightCountForTest(): Int = synchronized(lock) {
        inFlight.size
    }

    fun isInFlight(key: RichHtmlSnapshotCacheKey): Boolean = synchronized(lock) {
        inFlight.containsKey(key)
    }

    private fun put(key: RichHtmlSnapshotCacheKey, entry: RichHtmlSnapshotEntry) = synchronized(lock) {
        entries.remove(key)?.let { totalBytes -= it.byteCount }
        entries[key] = entry
        totalBytes += entry.byteCount
        trimToBudget()
    }

    private fun trimToBudget() {
        val iterator = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalBytes > MAX_BYTES) && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.byteCount
            iterator.remove()
        }
    }

    private data class InFlightRender(
        val deferred: Deferred<RichHtmlSnapshotEntry?>,
        var waiters: Int,
    )
}
