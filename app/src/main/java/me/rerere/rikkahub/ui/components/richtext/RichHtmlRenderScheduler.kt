package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichContentRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlAnalysis
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderKind
import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import kotlin.math.roundToInt

internal data class RichRenderScrollState(
    val scrolling: Boolean = false,
    val scrollInProgress: Boolean = false,
    val fastScrolling: Boolean = false,
    val visibleCellRange: IntRange = 0..-1,
    val nearViewportRange: IntRange = 0..-1,
    val scrollDirection: RichRenderScrollDirection = RichRenderScrollDirection.Idle,
    val viewportWidthDp: Float = 360f,
)

internal val LocalRichRenderScrollState = compositionLocalOf { RichRenderScrollState() }

internal enum class RichRenderScrollDirection {
    Up,
    Down,
    Idle,
}

internal data class RichHtmlRenderAdmission(
    val nativeAllowed: Boolean,
    val reason: String,
)

internal data class RichHtmlPrewarmTarget(
    val html: String,
    val cellIndex: Int,
    val viewportWidthDp: Float,
    val risk: RenderRiskScore,
    val contentType: String = "rich-html",
    val id: String = renderTextCacheKey(html),
) {
    val key: String = "$id:${viewportWidthDp.roundToInt()}:$contentType"
}

internal object RichHtmlRenderScheduler {
    private const val MaxNativeFirstRenders = 2
    private const val MaxEntries = 512
    private const val MaxPrewarmTargets = 6
    private val lock = Any()
    private val firstRendered = LinkedHashSet<String>()
    private val inFlightFirstRenders = LinkedHashSet<String>()
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prewarmJobs = linkedMapOf<String, Job>()

    fun admission(
        key: String,
        analysis: RichHtmlAnalysis,
        scrollState: RichRenderScrollState,
        cellIndex: Int? = null,
        risk: RenderRiskScore? = null,
    ): RichHtmlRenderAdmission = synchronized(lock) {
        fun decision(allowed: Boolean, reason: String): RichHtmlRenderAdmission {
            RichHtmlRenderTelemetry.recordNativeAdmission(
                id = key,
                allowed = allowed,
                reason = reason,
                cellIndex = cellIndex,
                riskScore = risk?.score,
                visibleCellRange = scrollState.visibleCellRange,
                fastScrolling = scrollState.fastScrolling,
            )
            return RichHtmlRenderAdmission(nativeAllowed = allowed, reason = reason)
        }

        if (firstRendered.contains(key)) {
            return@synchronized decision(allowed = true, reason = "already-rendered")
        }
        if (RichHtmlRenderCircuitBreaker.isOpen(key)) {
            return@synchronized decision(allowed = false, reason = "circuit-breaker")
        }
        if (risk?.route == RichContentRoute.Snapshot || risk?.route == RichContentRoute.DynamicPreview) {
            return@synchronized decision(allowed = false, reason = "risk-route:${risk.route.name}")
        }
        if (inFlightFirstRenders.contains(key)) {
            return@synchronized decision(allowed = true, reason = "already-admitted")
        }
        if (cellIndex != null && scrollState.visibleCellRange.isNotEmptyRange() &&
            cellIndex !in scrollState.visibleCellRange
        ) {
            return@synchronized if (cellIndex in scrollState.nearViewportRange) {
                decision(allowed = false, reason = "near-viewport-prewarm-only")
            } else {
                decision(allowed = false, reason = "far-from-viewport")
            }
        }
        val expensive = analysis.isExpensiveFirstRender()
        val risky = risk != null && risk.score >= 35
        if (expensive && scrollState.fastScrolling) {
            return@synchronized decision(allowed = false, reason = "fast-scroll")
        }
        if (risky && scrollState.fastScrolling) {
            return@synchronized decision(allowed = false, reason = "fast-scroll-risk")
        }
        if (expensive && scrollState.scrolling && inFlightFirstRenders.size >= MaxNativeFirstRenders) {
            return@synchronized decision(allowed = false, reason = "scroll-queue-full")
        }
        if (inFlightFirstRenders.size >= MaxNativeFirstRenders * 2) {
            return@synchronized decision(allowed = false, reason = "queue-full")
        }
        inFlightFirstRenders += key
        decision(allowed = true, reason = "admitted")
    }

    fun markRendered(key: String) = synchronized(lock) {
        inFlightFirstRenders.remove(key)
        firstRendered += key
        while (firstRendered.size > MaxEntries) {
            val first = firstRendered.firstOrNull() ?: break
            firstRendered.remove(first)
        }
    }

    fun hasRendered(key: String): Boolean = synchronized(lock) {
        firstRendered.contains(key)
    }

    fun release(key: String) = synchronized(lock) {
        inFlightFirstRenders.remove(key)
    }

    fun updatePrewarmTargets(
        targets: List<RichHtmlPrewarmTarget>,
        maxTargets: Int = MaxPrewarmTargets,
    ) {
        val activeBudget = maxTargets.coerceAtLeast(0)
        val admittedTargets = targets
            .asSequence()
            .filter { it.risk.route == RichContentRoute.NativeNow || it.risk.route == RichContentRoute.NativeDeferred }
            .filterNot { RichHtmlRenderCircuitBreaker.isOpen(it.id) }
            .filterNot { target ->
                val options = RichHtmlCompileOptions(viewportWidthDp = target.viewportWidthDp)
                RichHtmlCompiler.getCached(
                    html = target.html,
                    options = options,
                    cacheMode = RichHtmlCompileCacheMode.Persistent,
                ) != null
            }
            .take(activeBudget)
            .toList()
        synchronized(lock) {
            if (activeBudget == 0) return
            admittedTargets.forEach { target ->
                if (prewarmJobs.size < activeBudget && prewarmJobs[target.key] == null) {
                    prewarmJobs[target.key] = launchPrewarm(target)
                }
            }
        }
    }

    suspend fun drainPrewarmForTest() {
        val jobs = synchronized(lock) { prewarmJobs.values.toList() }
        jobs.joinAll()
    }

    fun prewarmJobCountForTest(): Int = synchronized(lock) {
        prewarmJobs.size
    }

    fun resetForTest() = synchronized(lock) {
        firstRendered.clear()
        inFlightFirstRenders.clear()
        prewarmJobs.values.forEach { it.cancel() }
        prewarmJobs.clear()
        RichHtmlRenderCircuitBreaker.resetForTest()
    }

    private fun launchPrewarm(target: RichHtmlPrewarmTarget): Job {
        val job = prewarmScope.launch {
            val requestedAtMs = System.currentTimeMillis()
            RichHtmlRenderTelemetry.recordPrewarm(
                id = target.id,
                cellIndex = target.cellIndex,
                viewportWidthDp = target.viewportWidthDp,
                stage = "start",
            )
            val options = RichHtmlCompileOptions(viewportWidthDp = target.viewportWidthDp)
            val outcome = runCatching {
                RichHtmlCompiler.compileAsync(
                    html = target.html,
                    options = options,
                    cacheMode = RichHtmlCompileCacheMode.Persistent,
                )
            }
            outcome.onSuccess {
                RichHtmlRenderTelemetry.recordPrewarm(
                    id = target.id,
                    cellIndex = target.cellIndex,
                    viewportWidthDp = target.viewportWidthDp,
                    queueWaitMs = (System.currentTimeMillis() - requestedAtMs).coerceAtLeast(0L),
                    stage = "finish",
                    outcome = "success",
                )
            }.onFailure { throwable ->
                val reason = if (throwable is CancellationException) {
                    "cancelled"
                } else {
                    throwable::class.simpleName ?: "failure"
                }
                RichHtmlRenderTelemetry.recordPrewarm(
                    id = target.id,
                    cellIndex = target.cellIndex,
                    viewportWidthDp = target.viewportWidthDp,
                    queueWaitMs = (System.currentTimeMillis() - requestedAtMs).coerceAtLeast(0L),
                    stage = "finish",
                    outcome = reason,
                )
            }
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (prewarmJobs[target.key] === job) {
                    prewarmJobs.remove(target.key)
                }
            }
        }
        return job
    }
}

internal data class RichHtmlHeightKey(
    val id: String,
    val widthDp: Int,
    val fontScaleBucket: Int,
    val contentType: String = "rich-html",
)

internal object RichHtmlHeightCache {
    private val cache = RenderLruCache<RichHtmlHeightKey, Int>(maxEntries = 384)

    fun key(
        id: String,
        viewportWidthDp: Float,
        fontScale: Float,
        contentType: String = "rich-html",
    ): RichHtmlHeightKey {
        return RichHtmlHeightKey(
            id = id,
            widthDp = viewportWidthDp.roundToInt(),
            fontScaleBucket = (fontScale * 100).roundToInt(),
            contentType = contentType,
        )
    }

    fun get(key: RichHtmlHeightKey): Int? = cache.get(key)

    fun put(key: RichHtmlHeightKey, heightPx: Int) {
        if (heightPx > 0) cache.putIfAbsent(key, heightPx)
    }

    fun resetForTest() = cache.clear()
}

internal fun RichHtmlAnalysis.estimatedHeightDp(): Int {
    val textCost = (previewText.length / 28).coerceIn(2, 18) * 22
    val structuralCost = when (kind) {
        RichHtmlRenderKind.NativeStatic -> 80
        RichHtmlRenderKind.InteractiveStatic -> 120
        RichHtmlRenderKind.ComplexDynamic -> 160
    }
    val confidenceCost = if (nativeConfidence.name.contains("Fallback", ignoreCase = true)) 80 else 0
    return (structuralCost + textCost + confidenceCost).coerceIn(120, 520)
}

private fun RichHtmlAnalysis.isExpensiveFirstRender(): Boolean {
    return kind != RichHtmlRenderKind.NativeStatic ||
        htmlLength >= 2_400 ||
        previewText.length >= 320 ||
        nativeConfidence.name.contains("Fallback", ignoreCase = true)
}

internal object RichHtmlRenderCircuitBreaker {
    private const val MaxEntries = 256
    private const val FailureThreshold = 2
    private val lock = Any()
    private val failures = linkedMapOf<String, RichHtmlCircuitBreakerState>()

    fun isOpen(key: String): Boolean = synchronized(lock) {
        (failures[key]?.failureCount ?: 0) >= FailureThreshold
    }

    fun recordFailure(key: String, reason: String): Unit = synchronized(lock) {
        val current = failures[key]
        failures[key] = RichHtmlCircuitBreakerState(
            failureCount = (current?.failureCount ?: 0) + 1,
            lastReason = reason,
        )
        trimLocked()
    }

    fun recordSuccess(key: String): Unit = synchronized(lock) {
        failures.remove(key)
    }

    fun failureReason(key: String): String? = synchronized(lock) {
        failures[key]?.lastReason
    }

    fun resetForTest(): Unit = synchronized(lock) {
        failures.clear()
    }

    private fun trimLocked() {
        while (failures.size > MaxEntries) {
            val first = failures.keys.firstOrNull() ?: return
            failures.remove(first)
        }
    }
}

private data class RichHtmlCircuitBreakerState(
    val failureCount: Int,
    val lastReason: String,
)

private fun IntRange.isNotEmptyRange(): Boolean = first <= last
