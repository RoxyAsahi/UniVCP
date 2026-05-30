package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.render.RenderLruCache

internal data class PreparedDrawCacheStats(
    val size: Int,
    val maxEntries: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
)

internal data class PreparedDashRecipe(
    val intervals: List<Float>,
)

internal data class PreparedGradientRecipe(
    val colors: List<Int>,
    val stops: List<Float?>,
)

internal object PreparedDrawCache {
    private val cache = RenderLruCache<String, Any>(maxEntries = 128)

    fun dashRecipe(intervals: List<Float>): PreparedDashRecipe {
        val safeIntervals = intervals.filter { it.isFinite() && it >= 0f }
        val key = "dash:" + safeIntervals.joinToString(",")
        @Suppress("UNCHECKED_CAST")
        return (cache.getOrPut(key) { PreparedDashRecipe(safeIntervals) } as PreparedDashRecipe)
            .also { reportStats() }
    }

    fun gradientRecipe(colors: List<Int>, stops: List<Float?>): PreparedGradientRecipe {
        val key = "gradient:${colors.joinToString(",")}:${stops.joinToString(",")}"
        @Suppress("UNCHECKED_CAST")
        return (cache.getOrPut(key) { PreparedGradientRecipe(colors = colors, stops = stops) } as PreparedGradientRecipe)
            .also { reportStats() }
    }

    fun stats(): PreparedDrawCacheStats {
        val stats = cache.stats()
        return PreparedDrawCacheStats(
            size = stats.size,
            maxEntries = stats.maxEntries,
            hits = stats.hits,
            misses = stats.misses,
            evictions = stats.evictions,
        )
    }

    fun clearForTest() {
        cache.clear()
    }

    private fun reportStats() {
        val stats = stats()
        RichHtmlRenderTelemetry.recordPreparedDrawCache(
            cacheName = "rich-draw",
            size = stats.size,
            maxEntries = stats.maxEntries,
            hits = stats.hits,
            misses = stats.misses,
            evictions = stats.evictions,
        )
    }
}
