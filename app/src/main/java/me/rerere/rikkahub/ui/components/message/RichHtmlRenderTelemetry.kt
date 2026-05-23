package me.rerere.rikkahub.ui.components.message

import android.util.Log
import me.rerere.rikkahub.BuildConfig

internal enum class RichHtmlFallbackStage {
    Safety,
    Classification,
    Compile,
    Render,
}

internal data class RichHtmlFallbackKey(
    val stage: RichHtmlFallbackStage,
    val reason: String,
)

internal object RichHtmlRenderTelemetry {
    private const val TAG = "RichHtmlRender"
    private val lock = Any()
    private val fallbackCounters = linkedMapOf<RichHtmlFallbackKey, Int>()
    private val paritySamples = ArrayDeque<RichHtmlParitySample>()

    fun recordFallback(stage: RichHtmlFallbackStage, reason: String) {
        if (!BuildConfig.DEBUG) return
        safeLog { Log.w(TAG, "fallback stage=$stage reason=$reason") }
        synchronized(lock) {
            val key = RichHtmlFallbackKey(stage = stage, reason = reason)
            fallbackCounters[key] = (fallbackCounters[key] ?: 0) + 1
        }
    }

    fun recordCompileParity(
        id: String,
        viewportWidthDp: Float,
        compileTimeMs: Long,
        unsupported: String?,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "compile success id=$id widthDp=$viewportWidthDp timeMs=$compileTimeMs unsupported=${unsupported.orEmpty()}"
            )
        }
        synchronized(lock) {
            addParitySample(
                RichHtmlParitySample(
                    id = id,
                    route = "compile",
                    viewportWidthDp = viewportWidthDp,
                    renderWidthPx = null,
                    renderHeightPx = null,
                    compileTimeMs = compileTimeMs,
                    unsupported = unsupported,
                )
            )
        }
    }

    fun recordRenderParity(
        id: String,
        viewportWidthDp: Float,
        renderWidthPx: Int,
        renderHeightPx: Int,
        route: String,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "render id=$id route=$route widthDp=$viewportWidthDp size=${renderWidthPx}x$renderHeightPx"
            )
        }
        synchronized(lock) {
            addParitySample(
                RichHtmlParitySample(
                    id = id,
                    route = route,
                    viewportWidthDp = viewportWidthDp,
                    renderWidthPx = renderWidthPx,
                    renderHeightPx = renderHeightPx,
                    compileTimeMs = null,
                    unsupported = null,
                )
            )
        }
    }

    fun fallbackSnapshot(): Map<RichHtmlFallbackKey, Int> = synchronized(lock) {
        fallbackCounters.toMap()
    }

    fun paritySnapshot(): List<RichHtmlParitySample> = synchronized(lock) {
        paritySamples.toList()
    }

    fun resetForTest() = synchronized(lock) {
        fallbackCounters.clear()
        paritySamples.clear()
    }

    fun recordCompileStart(id: String, viewportWidthDp: Float, length: Int) {
        if (!BuildConfig.DEBUG) return
        safeLog { Log.d(TAG, "compile start id=$id widthDp=$viewportWidthDp length=$length") }
    }

    fun recordCompileFailure(id: String, viewportWidthDp: Float, throwable: Throwable) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.w(
                TAG,
                "compile failure id=$id widthDp=$viewportWidthDp type=${throwable::class.simpleName} message=${throwable.message}",
                throwable,
            )
        }
    }

    private inline fun safeLog(block: () -> Unit) {
        runCatching(block)
    }

    private fun addParitySample(sample: RichHtmlParitySample) {
        paritySamples.addLast(sample)
        while (paritySamples.size > 48) {
            paritySamples.removeFirst()
        }
    }
}

internal data class RichHtmlParitySample(
    val id: String,
    val route: String,
    val viewportWidthDp: Float,
    val renderWidthPx: Int?,
    val renderHeightPx: Int?,
    val compileTimeMs: Long?,
    val unsupported: String?,
)
