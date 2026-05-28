package me.rerere.rikkahub.ui.components.message

import android.os.SystemClock
import android.util.Log
import me.rerere.rikkahub.BuildConfig
import kotlin.math.abs

internal enum class RichHtmlFallbackStage {
    Safety,
    Classification,
    Compile,
    Render,
    Snapshot,
}

internal data class RichHtmlFallbackKey(
    val stage: RichHtmlFallbackStage,
    val reason: String,
)

internal object RichHtmlRenderTelemetry {
    private const val TAG = "RichHtmlRender"
    private const val FramePressureRetentionMs = 5_000L
    private val lock = Any()
    private val fallbackCounters = linkedMapOf<RichHtmlFallbackKey, Int>()
    private val paritySamples = ArrayDeque<RichHtmlParitySample>()
    private val snapshotSamples = ArrayDeque<RichHtmlSnapshotTelemetrySample>()
    private val compileQueueSamples = ArrayDeque<RichHtmlCompileQueueSample>()
    private val framePressureSamples = ArrayDeque<RichFramePressureSample>()
    private val richRenderPlanSamples = ArrayDeque<RichRenderPlanSample>()

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
        snapshotSamples.clear()
        compileQueueSamples.clear()
        framePressureSamples.clear()
        richRenderPlanSamples.clear()
    }

    fun recordRichRenderPlan(plan: RichRenderPlan) {
        if (!BuildConfig.DEBUG) return
        val sample = RichRenderPlanSample.from(plan)
        safeLog {
            Log.d(
                TAG,
                "render-plan id=${sample.id} route=${sample.route} confidence=${sample.nativeConfidence} " +
                    "reason=${sample.reason} sourceNodes=${sample.sourceNodeCount} " +
                    "estimatedRenderBlocks=${sample.estimatedRenderBlockCount} " +
                    "textFlowCandidates=${sample.textFlowCandidateCount} " +
                    "snapshotIslandCandidates=${sample.snapshotIslandCandidateCount} " +
                    "interactiveActions=${sample.interactiveActionCount} " +
                    "visualHints=${sample.visualHints.sorted()} unsupported=${sample.unsupported.sorted()} " +
                    "risk=${sample.riskScore} riskReasons=${sample.riskReasons.sorted()} " +
                    "heightCache=${sample.heightCache}",
            )
        }
        synchronized(lock) {
            richRenderPlanSamples.addLast(sample)
            while (richRenderPlanSamples.size > 96) {
                richRenderPlanSamples.removeFirst()
            }
        }
    }

    fun recordCompileStart(id: String, viewportWidthDp: Float, length: Int) {
        if (!BuildConfig.DEBUG) return
        safeLog { Log.d(TAG, "compile start id=$id widthDp=$viewportWidthDp length=$length") }
    }

    fun recordCompileQueueWait(
        id: String,
        viewportWidthDp: Float,
        queueWaitMs: Long,
        joinedInFlight: Boolean,
        cacheMode: String,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "compile queue id=$id widthDp=$viewportWidthDp queueWaitMs=$queueWaitMs " +
                    "joinedInFlight=$joinedInFlight cacheMode=$cacheMode",
            )
        }
        synchronized(lock) {
            compileQueueSamples.addLast(
                RichHtmlCompileQueueSample(
                    id = id,
                    viewportWidthDp = viewportWidthDp,
                    queueWaitMs = queueWaitMs,
                    joinedInFlight = joinedInFlight,
                    cacheMode = cacheMode,
                    stage = "compile",
                    outcome = null,
                    cellIndex = null,
                )
            )
            trimCompileQueueSamples()
        }
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

    fun recordSnapshotStart(
        id: String,
        widthPx: Int,
        reason: String,
        joinedInFlight: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "snapshot start id=$id widthPx=$widthPx reason=$reason joinedInFlight=$joinedInFlight",
            )
        }
        synchronized(lock) {
            addSnapshotSample(
                RichHtmlSnapshotTelemetrySample(
                    id = id,
                    outcome = RichHtmlSnapshotOutcome.Start,
                    widthPx = widthPx,
                    heightPx = null,
                    reason = reason,
                    cacheHit = false,
                    joinedInFlight = joinedInFlight,
                    queueWaitMs = 0L,
                    renderTimeMs = null,
                    nativeEstimateHeightPx = null,
                    heightDeltaPct = null,
                    heightWarning = false,
                )
            )
        }
    }

    fun recordSnapshotSuccess(
        id: String,
        widthPx: Int,
        heightPx: Int,
        renderTimeMs: Long,
        cacheHit: Boolean,
        reason: String,
        queueWaitMs: Long = 0L,
        joinedInFlight: Boolean = false,
        nativeEstimateHeightPx: Int? = null,
        heightWarningThresholdPct: Float = 20f,
    ) {
        if (!BuildConfig.DEBUG) return
        val heightDeltaPct = nativeEstimateHeightPx
            ?.takeIf { it > 0 }
            ?.let { estimate -> abs(heightPx - estimate) * 100f / estimate }
        val heightWarning = heightDeltaPct != null && heightDeltaPct > heightWarningThresholdPct
        safeLog {
            Log.d(
                TAG,
                "snapshot success id=$id size=${widthPx}x$heightPx timeMs=$renderTimeMs " +
                    "queueWaitMs=$queueWaitMs cacheHit=$cacheHit joinedInFlight=$joinedInFlight " +
                    "heightDeltaPct=${heightDeltaPct?.let { "%.1f".format(it) }.orEmpty()} " +
                    "heightWarning=$heightWarning reason=$reason",
            )
        }
        synchronized(lock) {
            addSnapshotSample(
                RichHtmlSnapshotTelemetrySample(
                    id = id,
                    outcome = RichHtmlSnapshotOutcome.Success,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    reason = reason,
                    cacheHit = cacheHit,
                    joinedInFlight = joinedInFlight,
                    queueWaitMs = queueWaitMs,
                    renderTimeMs = renderTimeMs,
                    nativeEstimateHeightPx = nativeEstimateHeightPx,
                    heightDeltaPct = heightDeltaPct,
                    heightWarning = heightWarning,
                )
            )
            addParitySample(
                RichHtmlParitySample(
                    id = id,
                    route = "snapshot",
                    viewportWidthDp = 0f,
                    renderWidthPx = widthPx,
                    renderHeightPx = heightPx,
                    compileTimeMs = renderTimeMs,
                    unsupported = reason,
                )
            )
        }
    }

    fun recordSnapshotFailure(
        id: String,
        widthPx: Int,
        reason: String,
        message: String?,
        queueWaitMs: Long = 0L,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.w(
                TAG,
                "snapshot failure id=$id widthPx=$widthPx queueWaitMs=$queueWaitMs reason=$reason message=${message.orEmpty()}",
            )
        }
        synchronized(lock) {
            addSnapshotSample(
                RichHtmlSnapshotTelemetrySample(
                    id = id,
                    outcome = RichHtmlSnapshotOutcome.Failure,
                    widthPx = widthPx,
                    heightPx = null,
                    reason = reason,
                    cacheHit = false,
                    joinedInFlight = false,
                    queueWaitMs = queueWaitMs,
                    renderTimeMs = null,
                    nativeEstimateHeightPx = null,
                    heightDeltaPct = null,
                    heightWarning = false,
                )
            )
        }
        recordFallback(RichHtmlFallbackStage.Snapshot, reason)
    }

    fun recordCellPipeline(
        totalCells: Int,
        visibleCellRange: IntRange,
        richCells: Int,
        highRiskCells: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "cell pipeline total=$totalCells visible=${visibleCellRange.first}..${visibleCellRange.last} " +
                    "rich=$richCells highRisk=$highRiskCells",
            )
        }
    }

    fun recordNativeAdmission(
        id: String,
        allowed: Boolean,
        reason: String,
        cellIndex: Int?,
        riskScore: Int?,
        visibleCellRange: IntRange,
        fastScrolling: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "native admission id=$id allowed=$allowed reason=$reason cell=$cellIndex " +
                    "risk=${riskScore ?: -1} visible=${visibleCellRange.first}..${visibleCellRange.last} " +
                    "fast=$fastScrolling",
            )
        }
    }

    fun recordHeightCache(
        id: String,
        contentType: String,
        hit: Boolean,
        heightPx: Int?,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "height cache id=$id contentType=$contentType hit=$hit heightPx=${heightPx ?: -1}",
            )
        }
    }

    fun recordInlineDynamicWebView(
        id: String,
        event: String,
        reason: String,
        cellIndex: Int?,
        heightCssPx: Int? = null,
        activeCount: Int? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "inline-webview id=$id event=$event reason=$reason cell=${cellIndex ?: -1} " +
                    "heightCssPx=${heightCssPx ?: -1} active=${activeCount ?: -1}",
            )
        }
    }

    fun recordInlineDynamicWebViewPhase(
        id: String,
        phase: String,
        reason: String,
        cellIndex: Int? = null,
        previous: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "inline-webview-phase id=$id phase=$phase previous=${previous.orEmpty()} " +
                    "reason=$reason cell=${cellIndex ?: -1}",
            )
        }
    }

    fun recordFramePressure(
        frameMs: Float,
        direction: String,
        visibleCellRange: IntRange,
        fastScrolling: Boolean,
        inlineActiveCount: Int? = null,
        sampleAtMs: Long = SystemClock.uptimeMillis(),
    ) {
        val severity = when {
            frameMs >= 50f -> "severe"
            frameMs >= 33f -> "jank"
            frameMs >= 24f -> "slow"
            else -> return
        }
        if (BuildConfig.DEBUG) {
            safeLog {
                Log.d(
                    TAG,
                    "frame-pressure severity=$severity frameMs=${"%.1f".format(frameMs)} " +
                        "direction=$direction visible=${visibleCellRange.first}..${visibleCellRange.last} " +
                        "fast=$fastScrolling inlineActive=${inlineActiveCount ?: -1}",
                )
            }
        }
        synchronized(lock) {
            framePressureSamples.addLast(
                RichFramePressureSample(
                    frameMs = frameMs,
                    severity = severity,
                    direction = direction,
                    visibleCellRange = visibleCellRange,
                    fastScrolling = fastScrolling,
                    inlineActiveCount = inlineActiveCount,
                    sampleAtMs = sampleAtMs,
                )
            )
            val oldestAllowedMs = sampleAtMs - FramePressureRetentionMs
            while (framePressureSamples.size > 96 ||
                framePressureSamples.firstOrNull()?.sampleAtMs?.let { it < oldestAllowedMs } == true
            ) {
                framePressureSamples.removeFirst()
            }
        }
    }

    fun framePressureSnapshot(): List<RichFramePressureSample> = synchronized(lock) {
        framePressureSamples.toList()
    }

    fun richRenderPlanSnapshot(): List<RichRenderPlanSample> = synchronized(lock) {
        richRenderPlanSamples.toList()
    }

    fun richRenderPlanSummary(): RichRenderPlanSummary = synchronized(lock) {
        fun <T> Iterable<T>.countBy(name: (T) -> String): Map<String, Int> {
            val result = linkedMapOf<String, Int>()
            forEach { value ->
                val key = name(value)
                result[key] = (result[key] ?: 0) + 1
            }
            return result
        }
        RichRenderPlanSummary(
            total = richRenderPlanSamples.size,
            routes = richRenderPlanSamples.countBy { it.route },
            nativeConfidence = richRenderPlanSamples.countBy { it.nativeConfidence },
            heightCache = richRenderPlanSamples.countBy { it.heightCache },
            maxRiskScore = richRenderPlanSamples.maxOfOrNull { it.riskScore } ?: 0,
            maxEstimatedRenderBlockCount = richRenderPlanSamples.maxOfOrNull { it.estimatedRenderBlockCount } ?: 0,
        )
    }

    fun recentFramePressureWindow(
        nowMs: Long = SystemClock.uptimeMillis(),
        windowMs: Long = 900L,
        direction: String? = null,
    ): RichFramePressureWindow {
        val samples = synchronized(lock) {
            framePressureSamples.filter { sample ->
                sample.sampleAtMs in (nowMs - windowMs)..nowMs &&
                    (direction == null || sample.direction == direction)
            }
        }
        if (samples.isEmpty()) return RichFramePressureWindow()
        return RichFramePressureWindow(
            slowFrames = samples.count { it.severity == "slow" },
            jankyFrames = samples.count { it.severity == "jank" },
            severeFrames = samples.count { it.severity == "severe" },
            maxFrameMs = samples.maxOf { it.frameMs },
            inlineActiveMax = samples.mapNotNull { it.inlineActiveCount }.maxOrNull() ?: 0,
        )
    }

    fun recordPrewarm(
        id: String,
        cellIndex: Int,
        viewportWidthDp: Float,
        queueWaitMs: Long = 0L,
        stage: String,
        outcome: String? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "prewarm id=$id cell=$cellIndex widthDp=$viewportWidthDp " +
                    "queueWaitMs=$queueWaitMs stage=$stage outcome=${outcome.orEmpty()}",
            )
        }
        synchronized(lock) {
            compileQueueSamples.addLast(
                RichHtmlCompileQueueSample(
                    id = id,
                    viewportWidthDp = viewportWidthDp,
                    queueWaitMs = queueWaitMs,
                    joinedInFlight = false,
                    cacheMode = "Persistent",
                    stage = stage,
                    outcome = outcome,
                    cellIndex = cellIndex,
                )
            )
            trimCompileQueueSamples()
        }
    }

    fun compileQueueSnapshot(): List<RichHtmlCompileQueueSample> = synchronized(lock) {
        compileQueueSamples.toList()
    }

    fun snapshotTelemetrySnapshot(): List<RichHtmlSnapshotTelemetrySample> = synchronized(lock) {
        snapshotSamples.toList()
    }

    fun snapshotSummary(): RichHtmlSnapshotSummary = synchronized(lock) {
        val failureReasons = linkedMapOf<String, Int>()
        snapshotSamples
            .filter { it.outcome == RichHtmlSnapshotOutcome.Failure }
            .forEach { sample ->
                failureReasons[sample.reason] = (failureReasons[sample.reason] ?: 0) + 1
            }
        RichHtmlSnapshotSummary(
            cacheHits = snapshotSamples.count { it.outcome == RichHtmlSnapshotOutcome.Success && it.cacheHit },
            cacheMisses = snapshotSamples.count { it.outcome == RichHtmlSnapshotOutcome.Start },
            joinedInFlight = snapshotSamples.count { it.outcome == RichHtmlSnapshotOutcome.Start && it.joinedInFlight },
            successes = snapshotSamples.count { it.outcome == RichHtmlSnapshotOutcome.Success },
            failures = snapshotSamples.count { it.outcome == RichHtmlSnapshotOutcome.Failure },
            warnings = snapshotSamples.count { it.heightWarning },
            maxQueueWaitMs = snapshotSamples.maxOfOrNull { it.queueWaitMs } ?: 0L,
            maxRenderTimeMs = snapshotSamples.mapNotNull { it.renderTimeMs }.maxOrNull() ?: 0L,
            failureReasons = failureReasons,
        )
    }

    fun snapshotDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = snapshotSummary()
        return "snapshot summary " +
            "success=${summary.successes} failure=${summary.failures} " +
            "cacheHit=${summary.cacheHits} cacheMiss=${summary.cacheMisses} " +
            "joinedInFlight=${summary.joinedInFlight} warnings=${summary.warnings} " +
            "maxQueueWaitMs=${summary.maxQueueWaitMs} maxRenderTimeMs=${summary.maxRenderTimeMs} " +
            "failureReasons=${summary.failureReasons}"
    }

    fun richRenderPlanDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = richRenderPlanSummary()
        return "render plan summary total=${summary.total} routes=${summary.routes} " +
            "confidence=${summary.nativeConfidence} heightCache=${summary.heightCache} " +
            "maxRisk=${summary.maxRiskScore} maxRenderBlocks=${summary.maxEstimatedRenderBlockCount}"
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

    private fun addSnapshotSample(sample: RichHtmlSnapshotTelemetrySample) {
        snapshotSamples.addLast(sample)
        while (snapshotSamples.size > 96) {
            snapshotSamples.removeFirst()
        }
    }

    private fun trimCompileQueueSamples() {
        while (compileQueueSamples.size > 96) {
            compileQueueSamples.removeFirst()
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

internal enum class RichHtmlSnapshotOutcome {
    Start,
    Success,
    Failure,
}

internal data class RichHtmlSnapshotTelemetrySample(
    val id: String,
    val outcome: RichHtmlSnapshotOutcome,
    val widthPx: Int,
    val heightPx: Int?,
    val reason: String,
    val cacheHit: Boolean,
    val joinedInFlight: Boolean,
    val queueWaitMs: Long,
    val renderTimeMs: Long?,
    val nativeEstimateHeightPx: Int?,
    val heightDeltaPct: Float?,
    val heightWarning: Boolean,
)

internal data class RichHtmlSnapshotSummary(
    val cacheHits: Int,
    val cacheMisses: Int,
    val joinedInFlight: Int,
    val successes: Int,
    val failures: Int,
    val warnings: Int,
    val maxQueueWaitMs: Long,
    val maxRenderTimeMs: Long,
    val failureReasons: Map<String, Int>,
)

internal data class RichHtmlCompileQueueSample(
    val id: String,
    val viewportWidthDp: Float,
    val queueWaitMs: Long,
    val joinedInFlight: Boolean,
    val cacheMode: String,
    val stage: String,
    val outcome: String?,
    val cellIndex: Int?,
)

internal data class RichRenderPlanSample(
    val id: String,
    val route: String,
    val nativeConfidence: String,
    val reason: String,
    val htmlLength: Int,
    val sourceNodeCount: Int,
    val estimatedRenderBlockCount: Int,
    val textFlowCandidateCount: Int,
    val snapshotIslandCandidateCount: Int,
    val interactiveActionCount: Int,
    val visualHints: Set<String>,
    val unsupported: Set<String>,
    val riskScore: Int,
    val riskReasons: Set<String>,
    val heightCache: String,
) {
    companion object {
        fun from(plan: RichRenderPlan): RichRenderPlanSample = RichRenderPlanSample(
            id = plan.id,
            route = plan.route.name,
            nativeConfidence = plan.nativeConfidence.name,
            reason = plan.reason,
            htmlLength = plan.htmlLength,
            sourceNodeCount = plan.sourceNodeCount,
            estimatedRenderBlockCount = plan.estimatedRenderBlockCount,
            textFlowCandidateCount = plan.textFlowCandidateCount,
            snapshotIslandCandidateCount = plan.snapshotIslandCandidateCount,
            interactiveActionCount = plan.interactiveActionCount,
            visualHints = plan.visualHints.mapTo(linkedSetOf()) { it.name },
            unsupported = plan.unsupported.mapTo(linkedSetOf()) { it.name },
            riskScore = plan.riskScore,
            riskReasons = plan.riskReasons,
            heightCache = plan.heightCache.name,
        )
    }
}

internal data class RichRenderPlanSummary(
    val total: Int,
    val routes: Map<String, Int>,
    val nativeConfidence: Map<String, Int>,
    val heightCache: Map<String, Int>,
    val maxRiskScore: Int,
    val maxEstimatedRenderBlockCount: Int,
)

internal data class RichFramePressureSample(
    val frameMs: Float,
    val severity: String,
    val direction: String,
    val visibleCellRange: IntRange,
    val fastScrolling: Boolean,
    val inlineActiveCount: Int?,
    val sampleAtMs: Long,
)

internal data class RichFramePressureWindow(
    val slowFrames: Int = 0,
    val jankyFrames: Int = 0,
    val severeFrames: Int = 0,
    val maxFrameMs: Float = 0f,
    val inlineActiveMax: Int = 0,
)
