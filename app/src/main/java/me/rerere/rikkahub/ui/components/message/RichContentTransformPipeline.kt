package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModel

internal const val RichContentTransformPipelineVersion: Int = 2

internal enum class RichContentTransformPass {
    Import,
    Normalize,
    SanitizeReport,
    StyleResolve,
    TextFlowPlan,
    SubtreeRoutePlan,
    LowerToRenderModel,
    MeasureAndCache,
}

internal enum class RichContentDecisionSource {
    Document,
    RenderModel,
    Unavailable,
}

internal enum class RichContentLoweringMode {
    AstDirect,
    AstGatedRenderModel,
    RenderModelOnly,
    Unavailable,
}

internal enum class RichContentRouteMismatchReason {
    None,
    DocumentUnavailable,
    AstNativeCurrentSnapshot,
    AstNativeCurrentDynamic,
    AstSnapshotCurrentNative,
    AstSnapshotIslandCurrentNative,
    AstSnapshotIslandCurrentSnapshot,
    AstDynamicCurrentStatic,
    CurrentRouteUnknown,
}

internal data class RichContentTransformPipelineReport(
    val documentId: String,
    val sourceKind: RichContentSourceKind,
    val schemaVersion: Int,
    val pipelineVersion: Int,
    val passOrder: List<RichContentTransformPass>,
    val passDurationsMs: Map<RichContentTransformPass, Long>,
    val sourceNodeCount: Int,
    val canonicalNodeCount: Int,
    val normalizedNodeCount: Int,
    val textFlowEligibleNodeCount: Int,
    val textFlowPlannedNodeCount: Int,
    val textFlowDecisionSource: RichContentDecisionSource,
    val textFlowLoweringMode: RichContentLoweringMode,
    val textFlowHardStopNodeCount: Int,
    val textFlowHardStopReasons: Set<String>,
    val snapshotIslandPlannedNodeCount: Int,
    val subtreeRouteCandidateNodeCount: Int,
    val subtreeRouteDecisionSource: RichContentDecisionSource,
    val subtreeRouteLoweringMode: RichContentLoweringMode,
    val subtreeRouteRejectedNodeCount: Int,
    val subtreeRouteRejectReasons: Set<String>,
    val subtreeRouteNativePreservedActionCount: Int,
    val subtreeRouteInlineWebViewRequiredCount: Int,
    val subtreeRouteWholeSnapshotLikely: Boolean,
    val nativeBackendNodeCount: Int,
    val snapshotBackendNodeCount: Int,
    val inlineWebViewRequiredNodeCount: Int,
    val importConversionLossCount: Int,
    val normalizationWarnings: Map<String, Int>,
    val astRoute: RichContentDocumentRoute,
    val currentRoute: String,
    val routeMismatchReason: RichContentRouteMismatchReason,
    val subtreeDigestCount: Int,
    val subtreeCacheHitCount: Int,
    val subtreeCacheMissCount: Int,
    val subtreeCacheHitRate: Float,
) {
    val totalPassDurationMs: Long get() = passDurationsMs.values.sum()

    fun withCurrentRoute(route: RichRenderPlanRoute): RichContentTransformPipelineReport = copy(
        currentRoute = route.name,
        routeMismatchReason = routeMismatchReason(astRoute, route),
    )

    fun metadataLine(): String = listOf(
        "documentId=$documentId",
        "sourceKind=${sourceKind.name}",
        "schema=$schemaVersion",
        "pipeline=$pipelineVersion",
        "passes=${passOrder.map { it.name }}",
        "passDurationsMs=${passDurationsMs.mapKeys { it.key.name }}",
        "passDurationTotalMs=$totalPassDurationMs",
        "sourceNodes=$sourceNodeCount",
        "canonicalNodes=$canonicalNodeCount",
        "normalizedNodes=$normalizedNodeCount",
        "textFlowEligible=$textFlowEligibleNodeCount",
        "textFlowPlanned=$textFlowPlannedNodeCount",
        "textFlowDecisionSource=${textFlowDecisionSource.name}",
        "textFlowLoweringMode=${textFlowLoweringMode.name}",
        "textFlowHardStops=$textFlowHardStopNodeCount",
        "textFlowHardStopReasons=${textFlowHardStopReasons.sorted()}",
        "snapshotPlanned=$snapshotIslandPlannedNodeCount",
        "subtreeCandidates=$subtreeRouteCandidateNodeCount",
        "subtreeRouteDecisionSource=${subtreeRouteDecisionSource.name}",
        "subtreeRouteLoweringMode=${subtreeRouteLoweringMode.name}",
        "subtreeRejected=$subtreeRouteRejectedNodeCount",
        "subtreeRejectReasons=${subtreeRouteRejectReasons.sorted()}",
        "subtreeNativeActions=$subtreeRouteNativePreservedActionCount",
        "subtreeInlineRequired=$subtreeRouteInlineWebViewRequiredCount",
        "subtreeWholeSnapshotLikely=$subtreeRouteWholeSnapshotLikely",
        "nativeBackend=$nativeBackendNodeCount",
        "snapshotBackend=$snapshotBackendNodeCount",
        "inlineRequired=$inlineWebViewRequiredNodeCount",
        "importLoss=$importConversionLossCount",
        "normalizationWarnings=$normalizationWarnings",
        "astRoute=${astRoute.name}",
        "currentRoute=$currentRoute",
        "routeMismatch=${routeMismatchReason.name}",
        "subtreeDigests=$subtreeDigestCount",
        "subtreeCacheHits=$subtreeCacheHitCount",
        "subtreeCacheMisses=$subtreeCacheMissCount",
        "subtreeCacheHitRate=$subtreeCacheHitRate",
    ).joinToString(" ")
}

internal data class RichContentSubtreeCacheStats(
    val hitCount: Int = 0,
    val missCount: Int = 0,
) {
    val hitRate: Float
        get() {
            val total = hitCount + missCount
            return if (total > 0) hitCount.toFloat() / total.toFloat() else 0f
        }
}

internal object RichContentTransformPipeline {
    private const val MaxSubtreeDigestCacheEntries = 512
    private val subtreeDigestCache = linkedMapOf<String, String>()

    val passOrder: List<RichContentTransformPass> = listOf(
        RichContentTransformPass.Import,
        RichContentTransformPass.Normalize,
        RichContentTransformPass.SanitizeReport,
        RichContentTransformPass.StyleResolve,
        RichContentTransformPass.TextFlowPlan,
        RichContentTransformPass.SubtreeRoutePlan,
        RichContentTransformPass.LowerToRenderModel,
        RichContentTransformPass.MeasureAndCache,
    )

    fun report(
        document: RichContentDocument,
        currentRoute: RichRenderPlanRoute? = null,
        model: RichHtmlRenderModel? = null,
        observeSubtreeCache: Boolean = false,
    ): RichContentTransformPipelineReport {
        val passDurations = linkedMapOf<RichContentTransformPass, Long>().apply {
            passOrder.forEach { pass -> put(pass, 0L) }
        }
        fun <T> timed(pass: RichContentTransformPass, block: () -> T): T {
            val startNanos = System.nanoTime()
            return block().also {
                passDurations[pass] = (passDurations[pass] ?: 0L) +
                    (System.nanoTime() - startNanos) / 1_000_000
            }
        }
        val nodes = timed(RichContentTransformPass.Normalize) {
            document.root.flattenDocumentNodes()
        }
        val subtreeCacheStats = timed(RichContentTransformPass.MeasureAndCache) {
            if (observeSubtreeCache) {
                observeSubtreeDigests(document, nodes)
            } else {
                RichContentSubtreeCacheStats()
            }
        }
        val routeReport = timed(RichContentTransformPass.StyleResolve) {
            document.routeReport()
        }
        val textFlowPlan = timed(RichContentTransformPass.TextFlowPlan) {
            document.textFlowPlan()
        }
        val subtreeRoutePlan = timed(RichContentTransformPass.SubtreeRoutePlan) {
            document.subtreeRoutePlan()
        }
        val route = currentRoute
        val lowering = timed(RichContentTransformPass.LowerToRenderModel) {
            val fallbackLoweringMode = if (model == null) {
                RichContentLoweringMode.Unavailable
            } else {
                RichContentLoweringMode.AstGatedRenderModel
            }
            TransformLowering(
                textFlowLoweringMode = model?.textFlowLoweringMode ?: fallbackLoweringMode,
                subtreeRouteLoweringMode = model?.subtreeRouteLoweringMode ?: fallbackLoweringMode,
                textFlowPlannedNodeCount = model?.let { countRichTextFlowBlocksInModel(it) }
                    ?: document.stats.textFlowEligibleSubtreeCount,
                snapshotIslandPlannedNodeCount = model?.snapshotIslandStats?.appliedCount
                    ?: document.stats.snapshotIslandEligibleSubtreeCount,
            )
        }
        return RichContentTransformPipelineReport(
            documentId = document.documentId,
            sourceKind = document.sourceKind,
            schemaVersion = document.schemaVersion,
            pipelineVersion = RichContentTransformPipelineVersion,
            passOrder = passOrder,
            passDurationsMs = passDurations.toMap(),
            sourceNodeCount = document.stats.sourceNodeCount,
            canonicalNodeCount = document.stats.canonicalNodeCount,
            normalizedNodeCount = document.stats.normalizedNodeCount,
            textFlowEligibleNodeCount = document.stats.textFlowEligibleSubtreeCount,
            textFlowPlannedNodeCount = lowering.textFlowPlannedNodeCount,
            textFlowDecisionSource = RichContentDecisionSource.Document,
            textFlowLoweringMode = lowering.textFlowLoweringMode,
            textFlowHardStopNodeCount = textFlowPlan.hardStopNodeCount,
            textFlowHardStopReasons = textFlowPlan.hardStopReasons,
            snapshotIslandPlannedNodeCount = lowering.snapshotIslandPlannedNodeCount,
            subtreeRouteCandidateNodeCount = subtreeRoutePlan.candidateNodeCount,
            subtreeRouteDecisionSource = RichContentDecisionSource.Document,
            subtreeRouteLoweringMode = lowering.subtreeRouteLoweringMode,
            subtreeRouteRejectedNodeCount = subtreeRoutePlan.rejectedNodeCount,
            subtreeRouteRejectReasons = subtreeRoutePlan.rejectReasons,
            subtreeRouteNativePreservedActionCount = subtreeRoutePlan.nativePreservedActionCount,
            subtreeRouteInlineWebViewRequiredCount = subtreeRoutePlan.inlineWebViewRequiredCount,
            subtreeRouteWholeSnapshotLikely = subtreeRoutePlan.wholeSnapshotLikely,
            nativeBackendNodeCount = document.stats.nativeBackendCount,
            snapshotBackendNodeCount = document.stats.snapshotBackendCount,
            inlineWebViewRequiredNodeCount = document.stats.inlineWebViewRequiredCount,
            importConversionLossCount = document.stats.importConversionLossCount,
            normalizationWarnings = document.imports.warnings,
            astRoute = routeReport.route,
            currentRoute = route?.name.orEmpty(),
            routeMismatchReason = route?.let { routeMismatchReason(routeReport.route, it) }
                ?: RichContentRouteMismatchReason.CurrentRouteUnknown,
            subtreeDigestCount = nodes.map { it.subtreeDigest }.toSet().size,
            subtreeCacheHitCount = subtreeCacheStats.hitCount,
            subtreeCacheMissCount = subtreeCacheStats.missCount,
            subtreeCacheHitRate = subtreeCacheStats.hitRate,
        )
    }

    fun resetSubtreeDigestCacheForTest() = synchronized(subtreeDigestCache) {
        subtreeDigestCache.clear()
    }

    private fun observeSubtreeDigests(
        document: RichContentDocument,
        nodes: List<RichContentNodeV2>,
    ): RichContentSubtreeCacheStats = synchronized(subtreeDigestCache) {
        var hits = 0
        var misses = 0
        nodes.forEach { node ->
            val key = "${document.sourceKind.name}:${document.schemaVersion}:${node.kind.name}:${node.stablePath}"
            if (subtreeDigestCache[key] == node.subtreeDigest) {
                hits += 1
            } else {
                misses += 1
            }
            subtreeDigestCache[key] = node.subtreeDigest
        }
        while (subtreeDigestCache.size > MaxSubtreeDigestCacheEntries) {
            val first = subtreeDigestCache.keys.firstOrNull() ?: break
            subtreeDigestCache.remove(first)
        }
        RichContentSubtreeCacheStats(hitCount = hits, missCount = misses)
    }
}

private data class TransformLowering(
    val textFlowLoweringMode: RichContentLoweringMode,
    val subtreeRouteLoweringMode: RichContentLoweringMode,
    val textFlowPlannedNodeCount: Int,
    val snapshotIslandPlannedNodeCount: Int,
)

private fun countRichTextFlowBlocksInModel(model: RichHtmlRenderModel): Int =
    model.blocks.sumOf(::countRichTextFlowBlocks)

private fun routeMismatchReason(
    astRoute: RichContentDocumentRoute,
    currentRoute: RichRenderPlanRoute,
): RichContentRouteMismatchReason {
    return when (astRoute) {
        RichContentDocumentRoute.Native -> when (currentRoute) {
            RichRenderPlanRoute.Native,
            RichRenderPlanRoute.Lightweight -> RichContentRouteMismatchReason.None
            RichRenderPlanRoute.Snapshot -> RichContentRouteMismatchReason.AstNativeCurrentSnapshot
            RichRenderPlanRoute.DynamicPreview,
            RichRenderPlanRoute.InlineWebView -> RichContentRouteMismatchReason.AstNativeCurrentDynamic
        }
        RichContentDocumentRoute.NativeWithSnapshotIslands -> when (currentRoute) {
            RichRenderPlanRoute.Native -> RichContentRouteMismatchReason.AstSnapshotIslandCurrentNative
            RichRenderPlanRoute.Snapshot -> RichContentRouteMismatchReason.AstSnapshotIslandCurrentSnapshot
            RichRenderPlanRoute.Lightweight -> RichContentRouteMismatchReason.None
            RichRenderPlanRoute.DynamicPreview,
            RichRenderPlanRoute.InlineWebView -> RichContentRouteMismatchReason.AstDynamicCurrentStatic
        }
        RichContentDocumentRoute.Snapshot -> when (currentRoute) {
            RichRenderPlanRoute.Snapshot -> RichContentRouteMismatchReason.None
            RichRenderPlanRoute.Native,
            RichRenderPlanRoute.Lightweight -> RichContentRouteMismatchReason.AstSnapshotCurrentNative
            RichRenderPlanRoute.DynamicPreview,
            RichRenderPlanRoute.InlineWebView -> RichContentRouteMismatchReason.AstDynamicCurrentStatic
        }
        RichContentDocumentRoute.DynamicPreview -> when (currentRoute) {
            RichRenderPlanRoute.DynamicPreview,
            RichRenderPlanRoute.InlineWebView -> RichContentRouteMismatchReason.None
            RichRenderPlanRoute.Native,
            RichRenderPlanRoute.Lightweight,
            RichRenderPlanRoute.Snapshot -> RichContentRouteMismatchReason.AstDynamicCurrentStatic
        }
    }
}
