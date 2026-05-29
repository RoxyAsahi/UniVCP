package me.rerere.rikkahub.ui.components.message

import android.os.SystemClock
import android.util.Log
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.richtext.RichSubtreeRoutePlan
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
    private val heightCacheSamples = ArrayDeque<RichHeightCacheSample>()
    private val orchestratorSamples = ArrayDeque<RichRenderOrchestratorSample>()
    private val heightDeltaSamples = ArrayDeque<RichHeightDeltaSample>()
    private val snapshotIslandPlanSamples = ArrayDeque<RichSnapshotIslandPlanSample>()
    private val snapshotIslandRenderSamples = ArrayDeque<RichSnapshotIslandRenderSample>()
    private val sanitizerSamples = ArrayDeque<RichHtmlSanitizerSample>()
    private val compilePhaseSamples = ArrayDeque<RichHtmlCompilePhaseSample>()
    private val cssCascadeSamples = ArrayDeque<RichCssCascadeTelemetrySample>()
    private val mediaSamples = ArrayDeque<RichMediaTelemetrySample>()
    private val svgRouteSamples = ArrayDeque<RichSvgRouteTelemetrySample>()
    private val preparedDrawSamples = ArrayDeque<RichPreparedDrawTelemetrySample>()
    private val routeClosureSamples = ArrayDeque<RichRouteClosureTelemetrySample>()
    private val lastEventSignatures = linkedMapOf<RichTelemetrySignatureKey, Int>()

    fun recordFallback(stage: RichHtmlFallbackStage, reason: String) {
        if (!BuildConfig.DEBUG) return
        safeLog { Log.w(TAG, "fallback stage=$stage reason=$reason") }
        synchronized(lock) {
            val key = RichHtmlFallbackKey(stage = stage, reason = reason)
            fallbackCounters[key] = (fallbackCounters[key] ?: 0) + 1
        }
    }

    fun recordRenderThrowable(
        id: String,
        stage: RichHtmlFallbackStage,
        phase: String,
        throwable: Throwable,
    ) {
        if (!BuildConfig.DEBUG) return
        val reason = throwable::class.simpleName ?: "RenderFailure"
        safeLog {
            Log.w(
                TAG,
                "render throwable id=$id stage=$stage phase=$phase reason=$reason message=${throwable.message.orEmpty()}",
                throwable,
            )
        }
        recordFallback(stage = stage, reason = reason)
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
        heightCacheSamples.clear()
        orchestratorSamples.clear()
        heightDeltaSamples.clear()
        snapshotIslandPlanSamples.clear()
        snapshotIslandRenderSamples.clear()
        sanitizerSamples.clear()
        compilePhaseSamples.clear()
        cssCascadeSamples.clear()
        mediaSamples.clear()
        svgRouteSamples.clear()
        preparedDrawSamples.clear()
        routeClosureSamples.clear()
        lastEventSignatures.clear()
    }

    fun recordRichRenderPlan(plan: RichRenderPlan) {
        if (!BuildConfig.DEBUG) return
        val sample = RichRenderPlanSample.from(plan)
        if (!shouldRecordSignature("render-plan", sample.id, sample.hashCode())) return
        safeLog {
            Log.d(
                TAG,
                "render-plan id=${sample.id} planVersion=${sample.planVersion} " +
                    "route=${sample.route} confidence=${sample.nativeConfidence} " +
                    "reason=${sample.reason} sourceNodes=${sample.sourceNodeCount} " +
                    "estimatedRenderBlocks=${sample.estimatedRenderBlockCount} " +
                    "textFlowCandidates=${sample.textFlowCandidateCount} " +
                    "snapshotIslandCandidates=${sample.snapshotIslandCandidateCount} " +
                    "interactiveActions=${sample.interactiveActionCount} " +
                    "visualHints=${sample.visualHints.sorted()} unsupported=${sample.unsupported.sorted()} " +
                    "risk=${sample.riskScore} riskReasons=${sample.riskReasons.sorted()} " +
                    "heightCache=${sample.heightCache} astNodes=${sample.astNodeCount} " +
                    "textRuns=${sample.textRunCount} paragraphs=${sample.paragraphCount} " +
                    "documentId=${sample.documentId} documentSchema=${sample.documentSchemaVersion} " +
                    "documentSource=${sample.documentSourceKind} documentNodes=${sample.documentNodeCount} " +
                    "documentRoute=${sample.documentRoute} " +
                    "documentTextFlowEligible=${sample.documentTextFlowEligibleCount} " +
                    "documentSnapshotEligible=${sample.documentSnapshotEligibleCount} " +
                    "documentInlineRequired=${sample.documentInlineRequiredCount} " +
                    "transformPipeline=${sample.transformPipelineVersion} " +
                    "transformPasses=${sample.transformPassCount} " +
                    "transformPassDurationTotalMs=${sample.transformPassDurationTotalMs} " +
                    "transformRouteMismatch=${sample.transformRouteMismatchReason} " +
                    "transformTextFlowSource=${sample.transformTextFlowDecisionSource} " +
                    "transformTextFlowLowering=${sample.transformTextFlowLoweringMode} " +
                    "transformSubtreeRouteSource=${sample.transformSubtreeRouteDecisionSource} " +
                    "transformSubtreeRouteLowering=${sample.transformSubtreeRouteLoweringMode} " +
                    "transformSubtreeCacheHitRate=${sample.transformSubtreeCacheHitRate} " +
                    "blockedTextFlow=${sample.blockedTextFlowCount} textFlowApplied=${sample.textFlowAppliedCount} " +
                    "textFlowInlineFeatures=${sample.textFlowInlineFeaturePreservedCount} " +
                    "renderNodeReductionEstimate=${sample.renderNodeReductionEstimate} " +
                    "textFlowBlockedReasons=${sample.textFlowBlockedReasons.sorted()}",
            )
        }
        synchronized(lock) {
            richRenderPlanSamples.addLast(sample)
            while (richRenderPlanSamples.size > 96) {
                richRenderPlanSamples.removeFirst()
            }
        }
    }

    fun recordSnapshotIslandPlan(
        id: String,
        plan: RichSubtreeRoutePlan,
        appliedCount: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        val sample = RichSnapshotIslandPlanSample(
            id = id,
            route = plan.rootRoute.name,
            candidateCount = plan.candidates.size,
            appliedCount = appliedCount,
            rejectedCount = plan.rejected.size,
            reasons = plan.candidates.mapTo(linkedSetOf()) { it.reason.name },
            rejectReasons = plan.rejected
                .groupingBy { it.reason.name }
                .eachCount(),
            wholeSnapshotAvoided = plan.estimatedWholeSnapshotAvoided && appliedCount > 0,
            preservedActionCount = plan.nativePreservedActionCount,
            stablePaths = plan.candidates.mapTo(linkedSetOf()) { it.stablePath },
        )
        safeLog {
            Log.d(
                TAG,
                "snapshot-island-plan id=$id route=${sample.route} candidates=${sample.candidateCount} " +
                    "applied=${sample.appliedCount} rejected=${sample.rejectedCount} " +
                    "reasons=${sample.reasons.sorted()} rejectReasons=${sample.rejectReasons} " +
                    "wholeSnapshotAvoided=${sample.wholeSnapshotAvoided} " +
                    "preservedActions=${sample.preservedActionCount}",
            )
        }
        synchronized(lock) {
            snapshotIslandPlanSamples.addLast(sample)
            while (snapshotIslandPlanSamples.size > 96) {
                snapshotIslandPlanSamples.removeFirst()
            }
        }
    }

    fun recordSnapshotIslandRender(
        id: String,
        blockId: String,
        reason: String,
        widthPx: Int,
        heightPx: Int?,
        cacheHit: Boolean,
        renderTimeMs: Long?,
        heightCacheHit: Boolean? = null,
        fallbackReason: String? = null,
        styleBoundary: String? = null,
        queueWaitMs: Long? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "snapshot-island-render id=$id block=$blockId reason=$reason widthPx=$widthPx " +
                    "heightPx=${heightPx ?: -1} cacheHit=$cacheHit renderMs=${renderTimeMs ?: -1} " +
                    "heightCacheHit=${heightCacheHit ?: false} fallback=${fallbackReason.orEmpty()} " +
                    "styleBoundary=${styleBoundary.orEmpty()} queueWaitMs=${queueWaitMs ?: -1}",
            )
        }
        synchronized(lock) {
            snapshotIslandRenderSamples.addLast(
                RichSnapshotIslandRenderSample(
                    id = id,
                    blockId = blockId,
                    reason = reason,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    cacheHit = cacheHit,
                    renderTimeMs = renderTimeMs,
                    heightCacheHit = heightCacheHit,
                    fallbackReason = fallbackReason,
                    styleBoundary = styleBoundary,
                    queueWaitMs = queueWaitMs,
                )
            )
            while (snapshotIslandRenderSamples.size > 96) {
                snapshotIslandRenderSamples.removeFirst()
            }
        }
    }

    fun recordSanitizerReport(id: String, report: RichHtmlSanitizerReport) {
        if (!BuildConfig.DEBUG) return
        val sample = RichHtmlSanitizerSample(
            id = id,
            removedTagCount = report.removedTagCount,
            removedAttributeCount = report.removedAttributeCount,
            dangerousProtocolCount = report.dangerousProtocolCount,
            eventHandlerCount = report.eventHandlerCount,
            runtimeReason = report.runtimeReason,
            existingSafetyAgreed = report.existingSafetyAgreed,
        )
        safeLog {
            Log.d(
                TAG,
                "sanitizer-report id=$id removedTags=${sample.removedTagCount} " +
                    "removedAttrs=${sample.removedAttributeCount} dangerousProtocols=${sample.dangerousProtocolCount} " +
                    "eventHandlers=${sample.eventHandlerCount} runtimeReason=${sample.runtimeReason.orEmpty()} " +
                    "safetyAgreed=${sample.existingSafetyAgreed}",
            )
        }
        synchronized(lock) {
            sanitizerSamples.addLast(sample)
            while (sanitizerSamples.size > 96) sanitizerSamples.removeFirst()
        }
    }

    fun recordCompilePhases(
        id: String,
        viewportWidthDp: Float,
        parseMs: Long,
        cascadeMs: Long,
        domCompileMs: Long,
        renderModelMs: Long,
        optimizerMs: Long,
        totalMs: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "compile-phases id=$id widthDp=$viewportWidthDp parseMs=$parseMs cascadeMs=$cascadeMs " +
                    "domCompileMs=$domCompileMs renderModelMs=$renderModelMs optimizerMs=$optimizerMs totalMs=$totalMs",
            )
        }
        synchronized(lock) {
            compilePhaseSamples.addLast(
                RichHtmlCompilePhaseSample(
                    id = id,
                    viewportWidthDp = viewportWidthDp,
                    parseMs = parseMs,
                    cascadeMs = cascadeMs,
                    domCompileMs = domCompileMs,
                    renderModelMs = renderModelMs,
                    optimizerMs = optimizerMs,
                    totalMs = totalMs,
                )
            )
            while (compilePhaseSamples.size > 96) compilePhaseSamples.removeFirst()
        }
    }

    fun recordCssCascadeStats(
        id: String,
        ruleCount: Int,
        idRuleCount: Int,
        classRuleCount: Int,
        tagRuleCount: Int,
        attrRuleCount: Int,
        pseudoRuleCount: Int,
        universalRuleCount: Int,
        complexRuleCount: Int,
        unsupportedSelectorCount: Int,
        elementCount: Int,
        averageCandidateRules: Float,
        p95CandidateRules: Int,
        legacyScanCount: Int,
        legacyVerificationSkippedCount: Int,
        indexMismatchCount: Int,
        parserFallbackCount: Int,
        selectorMatchMs: Long,
        highCostSelectorCategories: Map<String, Int> = emptyMap(),
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "css-cascade id=$id rules=$ruleCount buckets=id:$idRuleCount class:$classRuleCount " +
                    "tag:$tagRuleCount attr:$attrRuleCount pseudo:$pseudoRuleCount " +
                    "universal:$universalRuleCount complex:$complexRuleCount " +
                    "unsupported=$unsupportedSelectorCount elements=$elementCount " +
                    "avgCandidates=${"%.1f".format(averageCandidateRules)} p95Candidates=$p95CandidateRules " +
                    "legacyScans=$legacyScanCount legacySkipped=$legacyVerificationSkippedCount " +
                    "indexMismatches=$indexMismatchCount parserFallbacks=$parserFallbackCount " +
                    "selectorMatchMs=$selectorMatchMs highCost=$highCostSelectorCategories",
            )
        }
        synchronized(lock) {
            cssCascadeSamples.addLast(
                RichCssCascadeTelemetrySample(
                    id = id,
                    ruleCount = ruleCount,
                    idRuleCount = idRuleCount,
                    classRuleCount = classRuleCount,
                    tagRuleCount = tagRuleCount,
                    attrRuleCount = attrRuleCount,
                    pseudoRuleCount = pseudoRuleCount,
                    universalRuleCount = universalRuleCount,
                    complexRuleCount = complexRuleCount,
                    unsupportedSelectorCount = unsupportedSelectorCount,
                    elementCount = elementCount,
                    averageCandidateRules = averageCandidateRules,
                    p95CandidateRules = p95CandidateRules,
                    legacyScanCount = legacyScanCount,
                    legacyVerificationSkippedCount = legacyVerificationSkippedCount,
                    indexMismatchCount = indexMismatchCount,
                    parserFallbackCount = parserFallbackCount,
                    selectorMatchMs = selectorMatchMs,
                    highCostSelectorCategories = highCostSelectorCategories,
                )
            )
            while (cssCascadeSamples.size > 96) cssCascadeSamples.removeFirst()
        }
    }

    fun recordMediaRequest(
        id: String,
        kind: String,
        safety: String,
        outcome: String,
        cacheState: String = "Unknown",
        oversizedRejected: Boolean = false,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "media-request id=$id kind=$kind safety=$safety outcome=$outcome " +
                    "cache=$cacheState oversizedRejected=$oversizedRejected",
            )
        }
        synchronized(lock) {
            mediaSamples.addLast(
                RichMediaTelemetrySample(
                    id = id,
                    kind = kind,
                    safety = safety,
                    outcome = outcome,
                    cacheState = cacheState,
                    oversizedRejected = oversizedRejected,
                )
            )
            while (mediaSamples.size > 96) mediaSamples.removeFirst()
        }
    }

    fun recordSvgRoute(
        id: String,
        route: String,
        reason: String,
        commandCount: Int,
        visualHintCount: Int,
        androidSvgAvailable: Boolean,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "svg-route id=$id route=$route reason=$reason commands=$commandCount " +
                    "visualHints=$visualHintCount androidSvgAvailable=$androidSvgAvailable",
            )
        }
        synchronized(lock) {
            svgRouteSamples.addLast(
                RichSvgRouteTelemetrySample(
                    id = id,
                    route = route,
                    reason = reason,
                    commandCount = commandCount,
                    visualHintCount = visualHintCount,
                    androidSvgAvailable = androidSvgAvailable,
                )
            )
            while (svgRouteSamples.size > 96) svgRouteSamples.removeFirst()
        }
    }

    fun recordPreparedDrawCache(
        cacheName: String,
        size: Int,
        maxEntries: Int,
        hits: Long,
        misses: Long,
        evictions: Long,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "prepared-draw-cache name=$cacheName size=$size max=$maxEntries " +
                    "hits=$hits misses=$misses evictions=$evictions",
            )
        }
        synchronized(lock) {
            preparedDrawSamples.addLast(
                RichPreparedDrawTelemetrySample(
                    cacheName = cacheName,
                    size = size,
                    maxEntries = maxEntries,
                    hits = hits,
                    misses = misses,
                    evictions = evictions,
                )
            )
            while (preparedDrawSamples.size > 96) preparedDrawSamples.removeFirst()
        }
    }

    fun recordRouteClosure(id: String, report: RichRouteClosureReport) {
        if (!BuildConfig.DEBUG) return
        val signature = report.hashCode()
        if (!shouldRecordSignature("route-closure", id, signature)) return
        safeLog {
            Log.d(
                TAG,
                "route-closure id=$id planned=${report.plannedRoute} actual=${report.actualRoute} " +
                    "mismatch=${report.mismatchReason.name} source=${report.actualDecisionSource.name}",
            )
        }
        synchronized(lock) {
            routeClosureSamples.addLast(
                RichRouteClosureTelemetrySample(
                    id = id,
                    plannedRoute = report.plannedRoute,
                    actualRoute = report.actualRoute,
                    mismatchReason = report.mismatchReason.name,
                    actualDecisionSource = report.actualDecisionSource.name,
                )
            )
            while (routeClosureSamples.size > 96) routeClosureSamples.removeFirst()
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
        confidence: String? = null,
        rendererVersion: Int? = null,
        documentSchemaVersion: Int? = null,
        persistent: Boolean? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        safeLog {
            Log.d(
                TAG,
                "height cache id=$id contentType=$contentType hit=$hit persistent=${persistent ?: false} " +
                    "confidence=${confidence.orEmpty()} rendererVersion=${rendererVersion ?: -1} " +
                    "documentSchema=${documentSchemaVersion ?: -1} heightPx=${heightPx ?: -1}",
            )
        }
        synchronized(lock) {
            heightCacheSamples.addLast(
                RichHeightCacheSample(
                    id = id,
                    contentType = contentType,
                    hit = hit,
                    heightPx = heightPx,
                    confidence = confidence,
                    rendererVersion = rendererVersion,
                    documentSchemaVersion = documentSchemaVersion,
                    persistent = persistent,
                )
            )
            while (heightCacheSamples.size > 96) {
                heightCacheSamples.removeFirst()
            }
        }
    }

    fun recordOrchestratorDecision(
        id: String,
        route: String,
        reason: String,
        placeholderHeightPx: Int?,
        placeholderSource: String,
        nativeAdmissionAllowed: Boolean,
        shouldPrewarm: Boolean,
        alreadyRendered: Boolean,
        cachedModelAvailable: Boolean,
        transient: Boolean,
        heightConfidence: String?,
    ) {
        if (!BuildConfig.DEBUG) return
        val signature = listOf(
            route,
            reason,
            placeholderHeightPx,
            placeholderSource,
            nativeAdmissionAllowed,
            shouldPrewarm,
            alreadyRendered,
            cachedModelAvailable,
            transient,
            heightConfidence,
        ).hashCode()
        if (!shouldRecordSignature("orchestrator", id, signature)) return
        safeLog {
            Log.d(
                TAG,
                "orchestrator id=$id route=$route reason=$reason " +
                    "placeholderHeightPx=${placeholderHeightPx ?: -1} placeholderSource=$placeholderSource " +
                    "nativeAllowed=$nativeAdmissionAllowed shouldPrewarm=$shouldPrewarm " +
                    "alreadyRendered=$alreadyRendered cachedModel=$cachedModelAvailable transient=$transient " +
                    "heightConfidence=${heightConfidence.orEmpty()}",
            )
        }
        synchronized(lock) {
            orchestratorSamples.addLast(
                RichRenderOrchestratorSample(
                    id = id,
                    route = route,
                    reason = reason,
                    placeholderHeightPx = placeholderHeightPx,
                    placeholderSource = placeholderSource,
                    nativeAdmissionAllowed = nativeAdmissionAllowed,
                    shouldPrewarm = shouldPrewarm,
                    alreadyRendered = alreadyRendered,
                    cachedModelAvailable = cachedModelAvailable,
                    transient = transient,
                    heightConfidence = heightConfidence,
                )
            )
            while (orchestratorSamples.size > 96) {
                orchestratorSamples.removeFirst()
            }
        }
    }

    fun recordHeightDelta(
        id: String,
        route: String,
        placeholderHeightPx: Int?,
        measuredHeightPx: Int,
        placeholderSource: String,
        heightConfidence: String?,
    ) {
        if (!BuildConfig.DEBUG) return
        val deltaPx = placeholderHeightPx
            ?.takeIf { it > 0 && measuredHeightPx > 0 }
            ?.let { kotlin.math.abs(measuredHeightPx - it) }
        val deltaPct = placeholderHeightPx
            ?.takeIf { it > 0 && measuredHeightPx > 0 }
            ?.let { deltaPx?.times(100f)?.div(it) }
        val severity = when {
            deltaPx == null || deltaPx < 24 -> "none"
            deltaPct != null && deltaPct > 50f -> "severe"
            deltaPct != null && deltaPct > 25f -> "warning"
            else -> "none"
        }
        val signature = listOf(
            route,
            placeholderHeightPx,
            measuredHeightPx,
            placeholderSource,
            heightConfidence,
            deltaPx,
            severity,
        ).hashCode()
        if (!shouldRecordSignature("height-delta", id, signature)) return
        safeLog {
            Log.d(
                TAG,
                "height delta id=$id route=$route placeholderHeightPx=${placeholderHeightPx ?: -1} " +
                    "measuredHeightPx=$measuredHeightPx deltaPx=${deltaPx ?: 0} " +
                    "deltaPct=${deltaPct?.let { "%.1f".format(it) }.orEmpty()} " +
                    "severity=$severity placeholderSource=$placeholderSource " +
                    "heightConfidence=${heightConfidence.orEmpty()}",
            )
        }
        synchronized(lock) {
            heightDeltaSamples.addLast(
                RichHeightDeltaSample(
                    id = id,
                    route = route,
                    placeholderHeightPx = placeholderHeightPx,
                    measuredHeightPx = measuredHeightPx,
                    deltaPx = deltaPx ?: 0,
                    deltaPct = deltaPct,
                    severity = severity,
                    placeholderSource = placeholderSource,
                    heightConfidence = heightConfidence,
                )
            )
            while (heightDeltaSamples.size > 96) {
                heightDeltaSamples.removeFirst()
            }
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

    fun heightCacheSnapshot(): List<RichHeightCacheSample> = synchronized(lock) {
        heightCacheSamples.toList()
    }

    fun orchestratorSnapshot(): List<RichRenderOrchestratorSample> = synchronized(lock) {
        orchestratorSamples.toList()
    }

    fun heightDeltaSnapshot(): List<RichHeightDeltaSample> = synchronized(lock) {
        heightDeltaSamples.toList()
    }

    fun snapshotIslandPlanSnapshot(): List<RichSnapshotIslandPlanSample> = synchronized(lock) {
        snapshotIslandPlanSamples.toList()
    }

    fun snapshotIslandRenderSnapshot(): List<RichSnapshotIslandRenderSample> = synchronized(lock) {
        snapshotIslandRenderSamples.toList()
    }

    fun sanitizerSnapshot(): List<RichHtmlSanitizerSample> = synchronized(lock) {
        sanitizerSamples.toList()
    }

    fun compilePhaseSnapshot(): List<RichHtmlCompilePhaseSample> = synchronized(lock) {
        compilePhaseSamples.toList()
    }

    fun cssCascadeSnapshot(): List<RichCssCascadeTelemetrySample> = synchronized(lock) {
        cssCascadeSamples.toList()
    }

    fun mediaSnapshot(): List<RichMediaTelemetrySample> = synchronized(lock) {
        mediaSamples.toList()
    }

    fun svgRouteSnapshot(): List<RichSvgRouteTelemetrySample> = synchronized(lock) {
        svgRouteSamples.toList()
    }

    fun preparedDrawSnapshot(): List<RichPreparedDrawTelemetrySample> = synchronized(lock) {
        preparedDrawSamples.toList()
    }

    fun routeClosureSnapshot(): List<RichRouteClosureTelemetrySample> = synchronized(lock) {
        routeClosureSamples.toList()
    }

    fun heightCacheSummary(): RichHeightCacheSummary = synchronized(lock) {
        fun Iterable<String>.countValues(): Map<String, Int> {
            val result = linkedMapOf<String, Int>()
            forEach { value -> result[value] = (result[value] ?: 0) + 1 }
            return result
        }
        RichHeightCacheSummary(
            total = heightCacheSamples.size,
            hits = heightCacheSamples.count { it.hit },
            misses = heightCacheSamples.count { !it.hit },
            persistentHits = heightCacheSamples.count { it.hit && it.persistent == true },
            persistentMisses = heightCacheSamples.count { !it.hit && it.persistent == true },
            confidence = heightCacheSamples.map { it.confidence ?: "Unknown" }.countValues(),
            rendererVersions = heightCacheSamples.map { (it.rendererVersion ?: -1).toString() }.countValues(),
            documentSchemaVersions = heightCacheSamples
                .map { (it.documentSchemaVersion ?: -1).toString() }
                .countValues(),
            maxHeightPx = heightCacheSamples.mapNotNull { it.heightPx }.maxOrNull() ?: 0,
        )
    }

    fun orchestratorSummary(): RichRenderOrchestratorSummary = synchronized(lock) {
        fun Iterable<String>.countValues(): Map<String, Int> {
            val result = linkedMapOf<String, Int>()
            forEach { value -> result[value] = (result[value] ?: 0) + 1 }
            return result
        }
        RichRenderOrchestratorSummary(
            total = orchestratorSamples.size,
            routes = orchestratorSamples.map { it.route }.countValues(),
            reasons = orchestratorSamples.map { it.reason }.countValues(),
            placeholderSources = orchestratorSamples.map { it.placeholderSource }.countValues(),
            nativeDeferredCount = orchestratorSamples.count { it.route == "NativeDeferred" },
            snapshotCount = orchestratorSamples.count { it.route == "Snapshot" },
            dynamicCount = orchestratorSamples.count { it.route == "DynamicPreview" },
            inlineWebViewCount = orchestratorSamples.count { it.route == "InlineWebView" },
            maxPlaceholderHeightPx = orchestratorSamples.mapNotNull { it.placeholderHeightPx }.maxOrNull() ?: 0,
        )
    }

    fun heightDeltaSummary(): RichHeightDeltaSummary = synchronized(lock) {
        RichHeightDeltaSummary(
            total = heightDeltaSamples.size,
            warnings = heightDeltaSamples.count { it.severity == "warning" },
            severeWarnings = heightDeltaSamples.count { it.severity == "severe" },
            maxDeltaPx = heightDeltaSamples.maxOfOrNull { it.deltaPx } ?: 0,
            maxDeltaPct = heightDeltaSamples.mapNotNull { it.deltaPct }.maxOrNull() ?: 0f,
        )
    }

    fun snapshotIslandSummary(): RichSnapshotIslandSummary = synchronized(lock) {
        fun Iterable<String>.countValues(): Map<String, Int> {
            val result = linkedMapOf<String, Int>()
            forEach { value -> result[value] = (result[value] ?: 0) + 1 }
            return result
        }
        RichSnapshotIslandSummary(
            planCount = snapshotIslandPlanSamples.size,
            renderCount = snapshotIslandRenderSamples.size,
            candidateCount = snapshotIslandPlanSamples.sumOf { it.candidateCount },
            appliedCount = snapshotIslandPlanSamples.sumOf { it.appliedCount },
            rejectedCount = snapshotIslandPlanSamples.sumOf { it.rejectedCount },
            wholeSnapshotAvoidedCount = snapshotIslandPlanSamples.count { it.wholeSnapshotAvoided },
            cacheHits = snapshotIslandRenderSamples.count { it.cacheHit },
            cacheMisses = snapshotIslandRenderSamples.count { !it.cacheHit },
            heightCacheHits = snapshotIslandRenderSamples.count { it.heightCacheHit == true },
            fallbackReasons = snapshotIslandRenderSamples.mapNotNull { it.fallbackReason }.countValues(),
            rejectReasons = snapshotIslandPlanSamples.flatMap { sample ->
                sample.rejectReasons.flatMap { (reason, count) -> List(count) { reason } }
            }.countValues(),
        )
    }

    fun sanitizerSummary(): RichHtmlSanitizerSummary = synchronized(lock) {
        RichHtmlSanitizerSummary(
            sampleCount = sanitizerSamples.size,
            removedTagCount = sanitizerSamples.sumOf { it.removedTagCount },
            removedAttributeCount = sanitizerSamples.sumOf { it.removedAttributeCount },
            dangerousProtocolCount = sanitizerSamples.sumOf { it.dangerousProtocolCount },
            eventHandlerCount = sanitizerSamples.sumOf { it.eventHandlerCount },
            runtimeReasons = sanitizerSamples.mapNotNull { it.runtimeReason }.groupingBy { it }.eachCount(),
        )
    }

    fun compilePhaseSummary(): RichHtmlCompilePhaseSummary = synchronized(lock) {
        RichHtmlCompilePhaseSummary(
            sampleCount = compilePhaseSamples.size,
            maxParseMs = compilePhaseSamples.maxOfOrNull { it.parseMs } ?: 0L,
            maxCascadeMs = compilePhaseSamples.maxOfOrNull { it.cascadeMs } ?: 0L,
            maxDomCompileMs = compilePhaseSamples.maxOfOrNull { it.domCompileMs } ?: 0L,
            maxRenderModelMs = compilePhaseSamples.maxOfOrNull { it.renderModelMs } ?: 0L,
            maxOptimizerMs = compilePhaseSamples.maxOfOrNull { it.optimizerMs } ?: 0L,
            maxTotalMs = compilePhaseSamples.maxOfOrNull { it.totalMs } ?: 0L,
        )
    }

    fun cssCascadeSummary(): RichCssCascadeTelemetrySummary = synchronized(lock) {
        RichCssCascadeTelemetrySummary(
            sampleCount = cssCascadeSamples.size,
            totalRules = cssCascadeSamples.sumOf { it.ruleCount },
            attrRules = cssCascadeSamples.sumOf { it.attrRuleCount },
            pseudoRules = cssCascadeSamples.sumOf { it.pseudoRuleCount },
            unsupportedSelectors = cssCascadeSamples.sumOf { it.unsupportedSelectorCount },
            parserFallbacks = cssCascadeSamples.sumOf { it.parserFallbackCount },
            indexMismatches = cssCascadeSamples.sumOf { it.indexMismatchCount },
            maxP95CandidateRules = cssCascadeSamples.maxOfOrNull { it.p95CandidateRules } ?: 0,
            totalLegacyScans = cssCascadeSamples.sumOf { it.legacyScanCount },
            legacyVerificationSkippedCount = cssCascadeSamples.sumOf { it.legacyVerificationSkippedCount },
            maxSelectorMatchMs = cssCascadeSamples.maxOfOrNull { it.selectorMatchMs } ?: 0L,
            highCostSelectorCategories = cssCascadeSamples
                .flatMap { sample ->
                    sample.highCostSelectorCategories.flatMap { (category, count) -> List(count) { category } }
                }
                .groupingBy { it }
                .eachCount(),
        )
    }

    fun mediaSummary(): RichMediaTelemetrySummary = synchronized(lock) {
        RichMediaTelemetrySummary(
            sampleCount = mediaSamples.size,
            byKind = mediaSamples.groupingBy { it.kind }.eachCount(),
            bySafety = mediaSamples.groupingBy { it.safety }.eachCount(),
            byOutcome = mediaSamples.groupingBy { it.outcome }.eachCount(),
            oversizedRejectedCount = mediaSamples.count { it.oversizedRejected },
        )
    }

    fun svgRouteSummary(): RichSvgRouteTelemetrySummary = synchronized(lock) {
        RichSvgRouteTelemetrySummary(
            sampleCount = svgRouteSamples.size,
            routes = svgRouteSamples.groupingBy { it.route }.eachCount(),
            reasons = svgRouteSamples.groupingBy { it.reason }.eachCount(),
            androidSvgAvailableCount = svgRouteSamples.count { it.androidSvgAvailable },
        )
    }

    fun preparedDrawSummary(): RichPreparedDrawTelemetrySummary = synchronized(lock) {
        RichPreparedDrawTelemetrySummary(
            sampleCount = preparedDrawSamples.size,
            maxSize = preparedDrawSamples.maxOfOrNull { it.size } ?: 0,
            totalHits = preparedDrawSamples.sumOf { it.hits },
            totalMisses = preparedDrawSamples.sumOf { it.misses },
            totalEvictions = preparedDrawSamples.sumOf { it.evictions },
        )
    }

    fun routeClosureSummary(): RichRouteClosureTelemetrySummary = synchronized(lock) {
        RichRouteClosureTelemetrySummary(
            sampleCount = routeClosureSamples.size,
            mismatches = routeClosureSamples.groupingBy { it.mismatchReason }.eachCount(),
            decisionSources = routeClosureSamples.groupingBy { it.actualDecisionSource }.eachCount(),
            plannedActualPairs = routeClosureSamples
                .map { "${it.plannedRoute}->${it.actualRoute}" }
                .groupingBy { it }
                .eachCount(),
        )
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
            maxAstNodeCount = richRenderPlanSamples.maxOfOrNull { it.astNodeCount } ?: 0,
            maxDocumentNodeCount = richRenderPlanSamples.maxOfOrNull { it.documentNodeCount } ?: 0,
            planVersions = richRenderPlanSamples.countBy { it.planVersion.toString() },
            documentSchemaVersions = richRenderPlanSamples.countBy { it.documentSchemaVersion.toString() },
            documentSourceKinds = richRenderPlanSamples.countBy { it.documentSourceKind },
            documentRoutes = richRenderPlanSamples.countBy { it.documentRoute },
            transformRouteMismatches = richRenderPlanSamples.countBy { it.transformRouteMismatchReason },
            transformTextFlowDecisionSources = richRenderPlanSamples.countBy {
                it.transformTextFlowDecisionSource
            },
            transformTextFlowLoweringModes = richRenderPlanSamples.countBy {
                it.transformTextFlowLoweringMode
            },
            transformSubtreeRouteDecisionSources = richRenderPlanSamples.countBy {
                it.transformSubtreeRouteDecisionSource
            },
            transformSubtreeRouteLoweringModes = richRenderPlanSamples.countBy {
                it.transformSubtreeRouteLoweringMode
            },
            maxTransformPassCount = richRenderPlanSamples.maxOfOrNull { it.transformPassCount } ?: 0,
            maxTransformPassDurationTotalMs = richRenderPlanSamples.maxOfOrNull {
                it.transformPassDurationTotalMs
            } ?: 0L,
            transformPassDurationsMs = richRenderPlanSamples
                .flatMap { sample -> sample.transformPassDurationsMs.entries }
                .groupingBy { it.key }
                .fold(0L) { acc, entry -> acc + entry.value },
            transformImportConversionLossCount = richRenderPlanSamples.sumOf {
                it.transformImportConversionLossCount
            },
            transformWarningCount = richRenderPlanSamples.sumOf { it.transformWarningCount },
            transformTextFlowHardStopCount = richRenderPlanSamples.sumOf { it.transformTextFlowHardStopCount },
            transformTextFlowHardStopReasons = richRenderPlanSamples
                .flatMap { it.transformTextFlowHardStopReasons }
                .countBy { it },
            transformSubtreeCandidateCount = richRenderPlanSamples.sumOf { it.transformSubtreeCandidateCount },
            transformSubtreeRejectedCount = richRenderPlanSamples.sumOf { it.transformSubtreeRejectedCount },
            transformSubtreeRejectReasons = richRenderPlanSamples
                .flatMap { it.transformSubtreeRejectReasons }
                .countBy { it },
            transformSubtreeNativePreservedActionCount = richRenderPlanSamples.sumOf {
                it.transformSubtreeNativePreservedActionCount
            },
            transformSubtreeInlineWebViewRequiredCount = richRenderPlanSamples.sumOf {
                it.transformSubtreeInlineWebViewRequiredCount
            },
            transformSubtreeWholeSnapshotLikelyCount = richRenderPlanSamples.count {
                it.transformSubtreeWholeSnapshotLikely
            },
            transformSubtreeCacheHitCount = richRenderPlanSamples.sumOf { it.transformSubtreeCacheHitCount },
            transformSubtreeCacheMissCount = richRenderPlanSamples.sumOf { it.transformSubtreeCacheMissCount },
            maxTransformSubtreeCacheHitRate = richRenderPlanSamples.maxOfOrNull {
                it.transformSubtreeCacheHitRate
            } ?: 0f,
            maxTextFlowAppliedCount = richRenderPlanSamples.maxOfOrNull { it.textFlowAppliedCount } ?: 0,
            maxTextFlowInlineFeaturePreservedCount = richRenderPlanSamples.maxOfOrNull {
                it.textFlowInlineFeaturePreservedCount
            } ?: 0,
            maxRenderNodeReductionEstimate = richRenderPlanSamples.maxOfOrNull {
                it.renderNodeReductionEstimate
            } ?: 0,
            textFlowBlockedReasons = richRenderPlanSamples
                .flatMap { it.textFlowBlockedReasons }
                .countBy { it },
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
            "maxRisk=${summary.maxRiskScore} maxRenderBlocks=${summary.maxEstimatedRenderBlockCount} " +
            "maxAstNodes=${summary.maxAstNodeCount} maxTextFlowApplied=${summary.maxTextFlowAppliedCount} " +
            "maxDocumentNodes=${summary.maxDocumentNodeCount} planVersions=${summary.planVersions} " +
            "documentSchemas=${summary.documentSchemaVersions} " +
            "documentSourceKinds=${summary.documentSourceKinds} documentRoutes=${summary.documentRoutes} " +
            "transformMismatches=${summary.transformRouteMismatches} " +
            "transformTextFlowSources=${summary.transformTextFlowDecisionSources} " +
            "transformTextFlowLowering=${summary.transformTextFlowLoweringModes} " +
            "transformSubtreeRouteSources=${summary.transformSubtreeRouteDecisionSources} " +
            "transformSubtreeRouteLowering=${summary.transformSubtreeRouteLoweringModes} " +
            "maxTransformPasses=${summary.maxTransformPassCount} " +
            "maxTransformPassDurationTotalMs=${summary.maxTransformPassDurationTotalMs} " +
            "transformPassDurationsMs=${summary.transformPassDurationsMs} " +
            "transformImportLoss=${summary.transformImportConversionLossCount} " +
            "transformWarnings=${summary.transformWarningCount} " +
            "transformTextFlowHardStops=${summary.transformTextFlowHardStopCount} " +
            "transformTextFlowHardStopReasons=${summary.transformTextFlowHardStopReasons} " +
            "transformSubtreeCandidates=${summary.transformSubtreeCandidateCount} " +
            "transformSubtreeRejected=${summary.transformSubtreeRejectedCount} " +
            "transformSubtreeRejectReasons=${summary.transformSubtreeRejectReasons} " +
            "transformSubtreeNativeActions=${summary.transformSubtreeNativePreservedActionCount} " +
            "transformSubtreeInlineRequired=${summary.transformSubtreeInlineWebViewRequiredCount} " +
            "transformSubtreeWholeSnapshotLikely=${summary.transformSubtreeWholeSnapshotLikelyCount} " +
            "transformSubtreeCacheHits=${summary.transformSubtreeCacheHitCount} " +
            "transformSubtreeCacheMisses=${summary.transformSubtreeCacheMissCount} " +
            "maxTransformSubtreeCacheHitRate=${summary.maxTransformSubtreeCacheHitRate} " +
            "maxTextFlowInlineFeatures=${summary.maxTextFlowInlineFeaturePreservedCount} " +
            "maxRenderNodeReduction=${summary.maxRenderNodeReductionEstimate} " +
            "textFlowBlockedReasons=${summary.textFlowBlockedReasons}"
    }

    fun heightCacheDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = heightCacheSummary()
        return "height cache summary total=${summary.total} hits=${summary.hits} misses=${summary.misses} " +
            "persistentHits=${summary.persistentHits} persistentMisses=${summary.persistentMisses} " +
            "confidence=${summary.confidence} rendererVersions=${summary.rendererVersions} " +
            "documentSchemas=${summary.documentSchemaVersions} " +
            "maxHeightPx=${summary.maxHeightPx}"
    }

    fun orchestratorDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = orchestratorSummary()
        return "orchestrator summary total=${summary.total} routes=${summary.routes} " +
            "reasons=${summary.reasons} placeholderSources=${summary.placeholderSources} " +
            "nativeDeferred=${summary.nativeDeferredCount} snapshot=${summary.snapshotCount} " +
            "dynamic=${summary.dynamicCount} inline=${summary.inlineWebViewCount} " +
            "maxPlaceholderHeightPx=${summary.maxPlaceholderHeightPx}"
    }

    fun heightDeltaDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = heightDeltaSummary()
        return "height delta summary total=${summary.total} warnings=${summary.warnings} " +
            "severe=${summary.severeWarnings} maxDeltaPx=${summary.maxDeltaPx} " +
            "maxDeltaPct=${"%.1f".format(summary.maxDeltaPct)}"
    }

    fun snapshotIslandDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = snapshotIslandSummary()
        return "snapshot island summary plans=${summary.planCount} renders=${summary.renderCount} " +
            "candidates=${summary.candidateCount} applied=${summary.appliedCount} " +
            "rejected=${summary.rejectedCount} wholeSnapshotAvoided=${summary.wholeSnapshotAvoidedCount} " +
            "cacheHit=${summary.cacheHits} cacheMiss=${summary.cacheMisses} " +
            "heightCacheHit=${summary.heightCacheHits} rejectReasons=${summary.rejectReasons} " +
            "fallbackReasons=${summary.fallbackReasons}"
    }

    fun sanitizerDebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val summary = sanitizerSummary()
        return "sanitizer summary samples=${summary.sampleCount} removedTags=${summary.removedTagCount} " +
            "removedAttrs=${summary.removedAttributeCount} dangerousProtocols=${summary.dangerousProtocolCount} " +
            "eventHandlers=${summary.eventHandlerCount} runtimeReasons=${summary.runtimeReasons}"
    }

    fun v5DebugSummary(): String {
        if (!BuildConfig.DEBUG) return ""
        val compile = compilePhaseSummary()
        val css = cssCascadeSummary()
        val media = mediaSummary()
        val svg = svgRouteSummary()
        val prepared = preparedDrawSummary()
        val routeClosure = routeClosureSummary()
        return "v5 summary compileSamples=${compile.sampleCount} maxTotalMs=${compile.maxTotalMs} " +
            "cssSamples=${css.sampleCount} cssRules=${css.totalRules} unsupportedSelectors=${css.unsupportedSelectors} " +
            "attrRules=${css.attrRules} pseudoRules=${css.pseudoRules} " +
            "parserFallbacks=${css.parserFallbacks} indexMismatches=${css.indexMismatches} " +
            "legacySkipped=${css.legacyVerificationSkippedCount} maxSelectorMatchMs=${css.maxSelectorMatchMs} " +
            "highCostSelectors=${css.highCostSelectorCategories} " +
            "mediaSamples=${media.sampleCount} mediaOutcomes=${media.byOutcome} " +
            "svgRoutes=${svg.routes} preparedHits=${prepared.totalHits} preparedMisses=${prepared.totalMisses} " +
            "routeMismatches=${routeClosure.mismatches} decisionSources=${routeClosure.decisionSources} " +
            "routePairs=${routeClosure.plannedActualPairs}"
    }

    private inline fun safeLog(block: () -> Unit) {
        runCatching(block)
    }

    private fun shouldRecordSignature(category: String, id: String, signature: Int): Boolean = synchronized(lock) {
        val key = RichTelemetrySignatureKey(category = category, id = id)
        if (lastEventSignatures[key] == signature) return@synchronized false
        lastEventSignatures[key] = signature
        if (lastEventSignatures.size > 384) {
            val iterator = lastEventSignatures.keys.iterator()
            repeat((lastEventSignatures.size - 384).coerceAtLeast(0)) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        true
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

private data class RichTelemetrySignatureKey(
    val category: String,
    val id: String,
)

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

internal data class RichHeightCacheSample(
    val id: String,
    val contentType: String,
    val hit: Boolean,
    val heightPx: Int?,
    val confidence: String?,
    val rendererVersion: Int?,
    val documentSchemaVersion: Int?,
    val persistent: Boolean?,
)

internal data class RichHeightCacheSummary(
    val total: Int,
    val hits: Int,
    val misses: Int,
    val persistentHits: Int,
    val persistentMisses: Int,
    val confidence: Map<String, Int>,
    val rendererVersions: Map<String, Int>,
    val documentSchemaVersions: Map<String, Int>,
    val maxHeightPx: Int,
)

internal data class RichRenderOrchestratorSample(
    val id: String,
    val route: String,
    val reason: String,
    val placeholderHeightPx: Int?,
    val placeholderSource: String,
    val nativeAdmissionAllowed: Boolean,
    val shouldPrewarm: Boolean,
    val alreadyRendered: Boolean,
    val cachedModelAvailable: Boolean,
    val transient: Boolean,
    val heightConfidence: String?,
)

internal data class RichRenderOrchestratorSummary(
    val total: Int,
    val routes: Map<String, Int>,
    val reasons: Map<String, Int>,
    val placeholderSources: Map<String, Int>,
    val nativeDeferredCount: Int,
    val snapshotCount: Int,
    val dynamicCount: Int,
    val inlineWebViewCount: Int,
    val maxPlaceholderHeightPx: Int,
)

internal data class RichHeightDeltaSample(
    val id: String,
    val route: String,
    val placeholderHeightPx: Int?,
    val measuredHeightPx: Int,
    val deltaPx: Int,
    val deltaPct: Float?,
    val severity: String,
    val placeholderSource: String,
    val heightConfidence: String?,
)

internal data class RichHeightDeltaSummary(
    val total: Int,
    val warnings: Int,
    val severeWarnings: Int,
    val maxDeltaPx: Int,
    val maxDeltaPct: Float,
)

internal data class RichSnapshotIslandPlanSample(
    val id: String,
    val route: String,
    val candidateCount: Int,
    val appliedCount: Int,
    val rejectedCount: Int,
    val reasons: Set<String>,
    val rejectReasons: Map<String, Int>,
    val wholeSnapshotAvoided: Boolean,
    val preservedActionCount: Int,
    val stablePaths: Set<String>,
)

internal data class RichSnapshotIslandRenderSample(
    val id: String,
    val blockId: String,
    val reason: String,
    val widthPx: Int,
    val heightPx: Int?,
    val cacheHit: Boolean,
    val renderTimeMs: Long?,
    val heightCacheHit: Boolean?,
    val fallbackReason: String?,
    val styleBoundary: String?,
    val queueWaitMs: Long?,
)

internal data class RichHtmlSanitizerSample(
    val id: String,
    val removedTagCount: Int,
    val removedAttributeCount: Int,
    val dangerousProtocolCount: Int,
    val eventHandlerCount: Int,
    val runtimeReason: String?,
    val existingSafetyAgreed: Boolean,
)

internal data class RichSnapshotIslandSummary(
    val planCount: Int,
    val renderCount: Int,
    val candidateCount: Int,
    val appliedCount: Int,
    val rejectedCount: Int,
    val wholeSnapshotAvoidedCount: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val heightCacheHits: Int,
    val fallbackReasons: Map<String, Int>,
    val rejectReasons: Map<String, Int>,
)

internal data class RichHtmlSanitizerSummary(
    val sampleCount: Int,
    val removedTagCount: Int,
    val removedAttributeCount: Int,
    val dangerousProtocolCount: Int,
    val eventHandlerCount: Int,
    val runtimeReasons: Map<String, Int>,
)

internal data class RichHtmlCompilePhaseSample(
    val id: String,
    val viewportWidthDp: Float,
    val parseMs: Long,
    val cascadeMs: Long,
    val domCompileMs: Long,
    val renderModelMs: Long,
    val optimizerMs: Long,
    val totalMs: Long,
)

internal data class RichHtmlCompilePhaseSummary(
    val sampleCount: Int,
    val maxParseMs: Long,
    val maxCascadeMs: Long,
    val maxDomCompileMs: Long,
    val maxRenderModelMs: Long,
    val maxOptimizerMs: Long,
    val maxTotalMs: Long,
)

internal data class RichCssCascadeTelemetrySample(
    val id: String,
    val ruleCount: Int,
    val idRuleCount: Int,
    val classRuleCount: Int,
    val tagRuleCount: Int,
    val attrRuleCount: Int,
    val pseudoRuleCount: Int,
    val universalRuleCount: Int,
    val complexRuleCount: Int,
    val unsupportedSelectorCount: Int,
    val elementCount: Int,
    val averageCandidateRules: Float,
    val p95CandidateRules: Int,
    val legacyScanCount: Int,
    val legacyVerificationSkippedCount: Int,
    val indexMismatchCount: Int,
    val parserFallbackCount: Int,
    val selectorMatchMs: Long,
    val highCostSelectorCategories: Map<String, Int> = emptyMap(),
)

internal data class RichCssCascadeTelemetrySummary(
    val sampleCount: Int,
    val totalRules: Int,
    val attrRules: Int,
    val pseudoRules: Int,
    val unsupportedSelectors: Int,
    val parserFallbacks: Int,
    val indexMismatches: Int,
    val maxP95CandidateRules: Int,
    val totalLegacyScans: Int,
    val legacyVerificationSkippedCount: Int,
    val maxSelectorMatchMs: Long,
    val highCostSelectorCategories: Map<String, Int>,
)

internal data class RichMediaTelemetrySample(
    val id: String,
    val kind: String,
    val safety: String,
    val outcome: String,
    val cacheState: String,
    val oversizedRejected: Boolean,
)

internal data class RichMediaTelemetrySummary(
    val sampleCount: Int,
    val byKind: Map<String, Int>,
    val bySafety: Map<String, Int>,
    val byOutcome: Map<String, Int>,
    val oversizedRejectedCount: Int,
)

internal data class RichSvgRouteTelemetrySample(
    val id: String,
    val route: String,
    val reason: String,
    val commandCount: Int,
    val visualHintCount: Int,
    val androidSvgAvailable: Boolean,
)

internal data class RichSvgRouteTelemetrySummary(
    val sampleCount: Int,
    val routes: Map<String, Int>,
    val reasons: Map<String, Int>,
    val androidSvgAvailableCount: Int,
)

internal data class RichPreparedDrawTelemetrySample(
    val cacheName: String,
    val size: Int,
    val maxEntries: Int,
    val hits: Long,
    val misses: Long,
    val evictions: Long,
)

internal data class RichPreparedDrawTelemetrySummary(
    val sampleCount: Int,
    val maxSize: Int,
    val totalHits: Long,
    val totalMisses: Long,
    val totalEvictions: Long,
)

internal data class RichRouteClosureTelemetrySample(
    val id: String,
    val plannedRoute: String,
    val actualRoute: String,
    val mismatchReason: String,
    val actualDecisionSource: String,
)

internal data class RichRouteClosureTelemetrySummary(
    val sampleCount: Int,
    val mismatches: Map<String, Int>,
    val decisionSources: Map<String, Int>,
    val plannedActualPairs: Map<String, Int>,
)

internal data class RichRenderPlanSample(
    val id: String,
    val planVersion: Int,
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
    val astNodeCount: Int,
    val textRunCount: Int,
    val paragraphCount: Int,
    val astTextFlowCandidateCount: Int,
    val blockedTextFlowCount: Int,
    val documentId: String,
    val documentSchemaVersion: Int,
    val documentSourceKind: String,
    val documentNodeCount: Int,
    val documentTextFlowEligibleCount: Int,
    val documentSnapshotEligibleCount: Int,
    val documentInlineRequiredCount: Int,
    val documentRoute: String,
    val transformPipelineVersion: Int,
    val transformPassCount: Int,
    val transformPassDurationTotalMs: Long,
    val transformPassDurationsMs: Map<String, Long>,
    val transformRouteMismatchReason: String,
    val transformTextFlowDecisionSource: String,
    val transformSubtreeRouteDecisionSource: String,
    val transformTextFlowLoweringMode: String,
    val transformSubtreeRouteLoweringMode: String,
    val transformImportConversionLossCount: Int,
    val transformWarningCount: Int,
    val transformTextFlowHardStopCount: Int,
    val transformTextFlowHardStopReasons: Set<String>,
    val transformSubtreeCandidateCount: Int,
    val transformSubtreeRejectedCount: Int,
    val transformSubtreeRejectReasons: Set<String>,
    val transformSubtreeNativePreservedActionCount: Int,
    val transformSubtreeInlineWebViewRequiredCount: Int,
    val transformSubtreeWholeSnapshotLikely: Boolean,
    val transformSubtreeCacheHitCount: Int,
    val transformSubtreeCacheMissCount: Int,
    val transformSubtreeCacheHitRate: Float,
    val textFlowAppliedCount: Int,
    val textFlowInlineFeaturePreservedCount: Int,
    val renderNodeReductionEstimate: Int,
    val textFlowBlockedReasons: Set<String>,
) {
    companion object {
        fun from(plan: RichRenderPlan): RichRenderPlanSample = RichRenderPlanSample(
            id = plan.id,
            planVersion = plan.version,
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
            astNodeCount = plan.astStats?.astNodeCount ?: 0,
            textRunCount = plan.astStats?.textRunCount ?: 0,
            paragraphCount = plan.astStats?.paragraphCount ?: 0,
            astTextFlowCandidateCount = plan.astStats?.textFlowCandidateCount ?: 0,
            blockedTextFlowCount = plan.astStats?.blockedTextFlowCount ?: 0,
            documentId = plan.documentId,
            documentSchemaVersion = plan.documentSchemaVersion,
            documentSourceKind = plan.documentSourceKind?.name.orEmpty(),
            documentNodeCount = plan.documentStats?.canonicalNodeCount ?: 0,
            documentTextFlowEligibleCount = plan.documentStats?.textFlowEligibleSubtreeCount ?: 0,
            documentSnapshotEligibleCount = plan.documentStats?.snapshotIslandEligibleSubtreeCount ?: 0,
            documentInlineRequiredCount = plan.documentStats?.inlineWebViewRequiredCount ?: 0,
            documentRoute = plan.documentRoute?.name.orEmpty(),
            transformPipelineVersion = plan.transformReport?.pipelineVersion ?: 0,
            transformPassCount = plan.transformReport?.passOrder?.size ?: 0,
            transformPassDurationTotalMs = plan.transformReport?.totalPassDurationMs ?: 0L,
            transformPassDurationsMs = plan.transformReport
                ?.passDurationsMs
                ?.mapKeys { it.key.name }
                .orEmpty(),
            transformRouteMismatchReason = plan.transformReport?.routeMismatchReason?.name.orEmpty(),
            transformTextFlowDecisionSource = plan.transformReport?.textFlowDecisionSource?.name.orEmpty(),
            transformSubtreeRouteDecisionSource = plan.transformReport?.subtreeRouteDecisionSource?.name.orEmpty(),
            transformTextFlowLoweringMode = plan.transformReport?.textFlowLoweringMode?.name.orEmpty(),
            transformSubtreeRouteLoweringMode = plan.transformReport?.subtreeRouteLoweringMode?.name.orEmpty(),
            transformImportConversionLossCount = plan.transformReport?.importConversionLossCount ?: 0,
            transformWarningCount = plan.transformReport?.normalizationWarnings?.values?.sum() ?: 0,
            transformTextFlowHardStopCount = plan.transformReport?.textFlowHardStopNodeCount ?: 0,
            transformTextFlowHardStopReasons = plan.transformReport?.textFlowHardStopReasons.orEmpty(),
            transformSubtreeCandidateCount = plan.transformReport?.subtreeRouteCandidateNodeCount ?: 0,
            transformSubtreeRejectedCount = plan.transformReport?.subtreeRouteRejectedNodeCount ?: 0,
            transformSubtreeRejectReasons = plan.transformReport?.subtreeRouteRejectReasons.orEmpty(),
            transformSubtreeNativePreservedActionCount = plan.transformReport?.subtreeRouteNativePreservedActionCount ?: 0,
            transformSubtreeInlineWebViewRequiredCount = plan.transformReport?.subtreeRouteInlineWebViewRequiredCount ?: 0,
            transformSubtreeWholeSnapshotLikely = plan.transformReport?.subtreeRouteWholeSnapshotLikely ?: false,
            transformSubtreeCacheHitCount = plan.transformReport?.subtreeCacheHitCount ?: 0,
            transformSubtreeCacheMissCount = plan.transformReport?.subtreeCacheMissCount ?: 0,
            transformSubtreeCacheHitRate = plan.transformReport?.subtreeCacheHitRate ?: 0f,
            textFlowAppliedCount = plan.textFlowAppliedCount,
            textFlowInlineFeaturePreservedCount = plan.textFlowInlineFeaturePreservedCount,
            renderNodeReductionEstimate = plan.renderNodeReductionEstimate,
            textFlowBlockedReasons = plan.textFlowBlockedReasons,
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
    val maxAstNodeCount: Int,
    val maxDocumentNodeCount: Int,
    val planVersions: Map<String, Int>,
    val documentSchemaVersions: Map<String, Int>,
    val documentSourceKinds: Map<String, Int>,
    val documentRoutes: Map<String, Int>,
    val transformRouteMismatches: Map<String, Int>,
    val transformTextFlowDecisionSources: Map<String, Int>,
    val transformSubtreeRouteDecisionSources: Map<String, Int>,
    val transformTextFlowLoweringModes: Map<String, Int>,
    val transformSubtreeRouteLoweringModes: Map<String, Int>,
    val maxTransformPassCount: Int,
    val maxTransformPassDurationTotalMs: Long,
    val transformPassDurationsMs: Map<String, Long>,
    val transformImportConversionLossCount: Int,
    val transformWarningCount: Int,
    val transformTextFlowHardStopCount: Int,
    val transformTextFlowHardStopReasons: Map<String, Int>,
    val transformSubtreeCandidateCount: Int,
    val transformSubtreeRejectedCount: Int,
    val transformSubtreeRejectReasons: Map<String, Int>,
    val transformSubtreeNativePreservedActionCount: Int,
    val transformSubtreeInlineWebViewRequiredCount: Int,
    val transformSubtreeWholeSnapshotLikelyCount: Int,
    val transformSubtreeCacheHitCount: Int,
    val transformSubtreeCacheMissCount: Int,
    val maxTransformSubtreeCacheHitRate: Float,
    val maxTextFlowAppliedCount: Int,
    val maxTextFlowInlineFeaturePreservedCount: Int,
    val maxRenderNodeReductionEstimate: Int,
    val textFlowBlockedReasons: Map<String, Int>,
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
