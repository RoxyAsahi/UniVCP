package me.rerere.rikkahub.ui.components.richtext.fidelity

import me.rerere.rikkahub.ui.components.message.RichActualDecisionSource
import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.RichHtmlSanitizer
import me.rerere.rikkahub.ui.components.message.RichRenderPlanRoute
import me.rerere.rikkahub.ui.components.message.analyzeRichHtml
import me.rerere.rikkahub.ui.components.message.buildRichContentDocumentFromMarkdown
import me.rerere.rikkahub.ui.components.message.buildRichContentDocumentFromProtocolAction
import me.rerere.rikkahub.ui.components.message.buildRichContentDocumentFromHtml
import me.rerere.rikkahub.ui.components.message.buildRichRenderPlan
import me.rerere.rikkahub.ui.components.message.buildRichRouteClosureReport
import me.rerere.rikkahub.ui.components.message.countRichRenderBlocks
import me.rerere.rikkahub.ui.components.message.RichContentDocument
import me.rerere.rikkahub.ui.components.message.RichContentDocumentRoute
import me.rerere.rikkahub.ui.components.message.RichContentTransformPipeline
import me.rerere.rikkahub.ui.components.message.routeReport
import me.rerere.rikkahub.ui.components.richtext.PreparedDrawCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompileOptions
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichMediaKind
import me.rerere.rikkahub.ui.components.richtext.RichMediaLoader
import me.rerere.rikkahub.ui.components.richtext.RichMediaRequest
import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderAdmission
import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichRenderDecisionRoute
import me.rerere.rikkahub.ui.components.richtext.RichRenderDecisionInput
import me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestrator
import me.rerere.rikkahub.ui.components.richtext.RichRenderScrollState
import me.rerere.rikkahub.ui.components.richtext.RichSnapshotIslandBlock
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichButtonBlock
import me.rerere.rikkahub.ui.components.richtext.RichDetailsBlock
import me.rerere.rikkahub.ui.components.richtext.RichTextBlock
import me.rerere.rikkahub.ui.components.richtext.RichTextFlowAstLowerer
import me.rerere.rikkahub.ui.components.richtext.RichTextFlowBlock
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssDeclarationEquivalenceReport
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssParser
import org.jsoup.Jsoup

internal data class RichFixtureRenderMetadata(
    val fixtureId: String,
    val digest: String,
    val documentId: String = "",
    val documentSchemaVersion: Int = RichRenderPlatformVersions.RichContentDocument,
    val documentSourceKind: String = "",
    val sourceKind: String = "",
    val privacyMode: String = "",
    val category: RichFidelityCategory,
    val route: String,
    val plannedRoute: String = route,
    val actualRoute: String = route,
    val routeMismatchReason: String = "None",
    val actualDecisionSource: String = "Orchestrator",
    val renderPlanVersion: Int = RichRenderPlatformVersions.RichRenderPlan,
    val renderModelVersion: Int = RichRenderPlatformVersions.RichHtmlRenderModel,
    val heightCacheRendererVersion: Int = RichRenderPlatformVersions.heightCacheRendererVersion,
    val snapshotCacheRendererVersion: Int = RichRenderPlatformVersions.snapshotCacheRendererVersion,
    val planReason: String,
    val nativeConfidence: String,
    val visualHints: Map<String, Int>,
    val unsupportedReasons: Map<String, Int>,
    val snapshotIslandCount: Int,
    val textFlowCount: Int,
    val transformPipelineVersion: Int = 0,
    val transformPassCount: Int = 0,
    val transformPassDurationTotalMs: Long = 0L,
    val transformPassDurationsMs: Map<String, Long> = emptyMap(),
    val astRoute: String = "",
    val astRouteMismatchReason: String = "None",
    val transformImportConversionLossCount: Int = 0,
    val transformWarningCount: Int = 0,
    val astNodeCount: Int = 0,
    val canonicalAstNodeCount: Int = 0,
    val textFlowEligibleNodeCount: Int = 0,
    val textFlowAppliedBlockCount: Int = textFlowCount,
    val textFlowInlineFeaturePreservedCount: Int = 0,
    val textFlowDecisionSource: String = "",
    val textFlowLoweringMode: String = "",
    val renderBlockReductionRatio: Float = 0f,
    val textFlowBlockedReasons: Map<String, Int> = emptyMap(),
    val subtreeRouteCandidateNodeCount: Int = 0,
    val subtreeRouteDecisionSource: String = "",
    val subtreeRouteLoweringMode: String = "",
    val subtreeRouteRejectedNodeCount: Int = 0,
    val subtreeRouteRejectReasons: Map<String, Int> = emptyMap(),
    val subtreeNativePreservedActionCount: Int = 0,
    val subtreeDigestCacheHitCount: Int = 0,
    val subtreeDigestCacheMissCount: Int = 0,
    val subtreeDigestCacheHitRate: Float = 0f,
    val snapshotIslandAppliedCount: Int = snapshotIslandCount,
    val snapshotIslandRejectedCount: Int = 0,
    val wholeSnapshotAvoided: Boolean = false,
    val snapshotIslandRenderCount: Int = 0,
    val snapshotIslandBitmapCacheHitCount: Int = 0,
    val snapshotIslandBitmapCacheMissCount: Int = 0,
    val snapshotIslandHeightCacheHitCount: Int = 0,
    val snapshotIslandFallbackReasons: Map<String, Int> = emptyMap(),
    val snapshotIslandStyleBoundaries: Map<String, Int> = emptyMap(),
    val cssRuleCount: Int = 0,
    val cssSelectorCount: Int = 0,
    val cssIdRuleCount: Int = 0,
    val cssClassRuleCount: Int = 0,
    val cssTagRuleCount: Int = 0,
    val cssAttrRuleCount: Int = 0,
    val cssPseudoRuleCount: Int = 0,
    val cssUniversalRuleCount: Int = 0,
    val cssComplexRuleCount: Int = 0,
    val cssUnsupportedSelectorCount: Int = 0,
    val cssAverageCandidateRules: Float = 0f,
    val cssParserFallbackCount: Int = 0,
    val cssIndexMismatchCount: Int = 0,
    val cssLegacyVerificationSkippedCount: Int = 0,
    val cssP95CandidateRules: Int = 0,
    val cssSelectorMatchMs: Long = 0L,
    val cssHighCostSelectorCategories: Map<String, Int> = emptyMap(),
    val cssEquivalenceFixtureCount: Int = 0,
    val cssEquivalenceMismatchCount: Int = 0,
    val cssEquivalenceFallbackUsedCount: Int = 0,
    val cssEquivalenceMismatchProperties: Map<String, Int> = emptyMap(),
    val sanitizerRemovedTagCount: Int = 0,
    val sanitizerRemovedAttributeCount: Int = 0,
    val sanitizerDangerousProtocolCount: Int = 0,
    val sanitizerEventHandlerCount: Int = 0,
    val sanitizerRuntimeReason: String = "",
    val sanitizerExistingSafetyAgreed: Boolean = true,
    val mediaRequestCount: Int = 0,
    val mediaKinds: Map<String, Int> = emptyMap(),
    val mediaSafety: Map<String, Int> = emptyMap(),
    val svgRouteCount: Int = 0,
    val svgRoutes: Map<String, Int> = emptyMap(),
    val svgRouteReasons: Map<String, Int> = emptyMap(),
    val preparedDrawCacheHitCount: Long = 0L,
    val preparedDrawCacheMissCount: Long = 0L,
    val preparedDrawCacheHitRate: Float = 0f,
    val preparedDrawCacheEvictionCount: Long = 0L,
    val compileParseMs: Long = 0L,
    val compileCascadeMs: Long = 0L,
    val compileDomCompileMs: Long = 0L,
    val compileRenderModelMs: Long = 0L,
    val compileOptimizerMs: Long = 0L,
    val compileTotalMs: Long = 0L,
    val placeholderHeightPx: Int? = null,
    val heightCacheHit: Boolean = false,
    val heightCachePersistent: Boolean = false,
    val heightCacheConfidence: String = "",
    val heightCacheSourceRoute: String = "",
    val measuredNativeHeight: Int?,
    val measuredWebViewHeight: Int?,
    val measuredHeightPx: Int? = measuredNativeHeight ?: measuredWebViewHeight,
    val heightDeltaPx: Int = if (measuredNativeHeight != null && measuredWebViewHeight != null) {
        measuredNativeHeight - measuredWebViewHeight
    } else {
        0
    },
    val heightDeltaRatio: Float = if (measuredNativeHeight != null && measuredWebViewHeight != null && measuredWebViewHeight != 0) {
        (measuredNativeHeight - measuredWebViewHeight).toFloat() / measuredWebViewHeight.toFloat()
    } else {
        0f
    },
    val renderTimeMs: Long?,
    val fallbackReason: String? = null,
) {
    fun metadataLine(): String = listOf(
        "fixtureId=$fixtureId",
        "digest=$digest",
        "documentId=$documentId",
        "documentSchema=$documentSchemaVersion",
        "documentSourceKind=$documentSourceKind",
        "sourceKind=$sourceKind",
        "privacyMode=$privacyMode",
        "category=${category.name}",
        "route=$route",
        "plannedRoute=$plannedRoute",
        "actualRoute=$actualRoute",
        "routeMismatch=$routeMismatchReason",
        "actualDecisionSource=$actualDecisionSource",
        "renderPlanVersion=$renderPlanVersion",
        "renderModelVersion=$renderModelVersion",
        "heightCacheRendererVersion=$heightCacheRendererVersion",
        "snapshotCacheRendererVersion=$snapshotCacheRendererVersion",
        "planReason=$planReason",
        "nativeConfidence=$nativeConfidence",
        "visualHints=$visualHints",
        "unsupported=$unsupportedReasons",
        "snapshotIslandCount=$snapshotIslandCount",
        "textFlowCount=$textFlowCount",
        "transformPipeline=$transformPipelineVersion",
        "transformPassDurationTotalMs=$transformPassDurationTotalMs",
        "transformPassDurationsMs=$transformPassDurationsMs",
        "astRoute=$astRoute",
        "astRouteMismatch=$astRouteMismatchReason",
        "astNodes=$astNodeCount",
        "canonicalAstNodes=$canonicalAstNodeCount",
        "textFlowEligible=$textFlowEligibleNodeCount",
        "textFlowApplied=$textFlowAppliedBlockCount",
        "textFlowInlineFeaturesPreserved=$textFlowInlineFeaturePreservedCount",
        "textFlowDecisionSource=$textFlowDecisionSource",
        "textFlowLoweringMode=$textFlowLoweringMode",
        "renderBlockReductionRatio=$renderBlockReductionRatio",
        "textFlowBlockedReasons=$textFlowBlockedReasons",
        "subtreeCandidates=$subtreeRouteCandidateNodeCount",
        "subtreeRouteDecisionSource=$subtreeRouteDecisionSource",
        "subtreeRouteLoweringMode=$subtreeRouteLoweringMode",
        "subtreeRejected=$subtreeRouteRejectedNodeCount",
        "subtreeRejectReasons=$subtreeRouteRejectReasons",
        "subtreeNativeActions=$subtreeNativePreservedActionCount",
        "subtreeDigestCacheHits=$subtreeDigestCacheHitCount",
        "subtreeDigestCacheMisses=$subtreeDigestCacheMissCount",
        "subtreeDigestCacheHitRate=$subtreeDigestCacheHitRate",
        "snapshotIslandApplied=$snapshotIslandAppliedCount",
        "snapshotIslandRejected=$snapshotIslandRejectedCount",
        "wholeSnapshotAvoided=$wholeSnapshotAvoided",
        "snapshotIslandRenderCount=$snapshotIslandRenderCount",
        "snapshotIslandBitmapCacheHits=$snapshotIslandBitmapCacheHitCount",
        "snapshotIslandBitmapCacheMisses=$snapshotIslandBitmapCacheMissCount",
        "snapshotIslandHeightCacheHits=$snapshotIslandHeightCacheHitCount",
        "snapshotIslandFallbackReasons=$snapshotIslandFallbackReasons",
        "snapshotIslandStyleBoundaries=$snapshotIslandStyleBoundaries",
        "cssRules=$cssRuleCount",
        "cssSelectors=$cssSelectorCount",
        "cssIdRules=$cssIdRuleCount",
        "cssClassRules=$cssClassRuleCount",
        "cssTagRules=$cssTagRuleCount",
        "cssAttrRules=$cssAttrRuleCount",
        "cssPseudoRules=$cssPseudoRuleCount",
        "cssUniversalRules=$cssUniversalRuleCount",
        "cssComplexRules=$cssComplexRuleCount",
        "cssUnsupportedSelectors=$cssUnsupportedSelectorCount",
        "cssAvgCandidates=$cssAverageCandidateRules",
        "cssParserFallbacks=$cssParserFallbackCount",
        "cssIndexMismatches=$cssIndexMismatchCount",
        "cssLegacySkipped=$cssLegacyVerificationSkippedCount",
        "cssP95Candidates=$cssP95CandidateRules",
        "cssSelectorMatchMs=$cssSelectorMatchMs",
        "cssHighCostSelectors=$cssHighCostSelectorCategories",
        "cssEquivalenceFixtures=$cssEquivalenceFixtureCount",
        "cssEquivalenceMismatches=$cssEquivalenceMismatchCount",
        "cssEquivalenceFallbackUsed=$cssEquivalenceFallbackUsedCount",
        "cssEquivalenceMismatchProperties=$cssEquivalenceMismatchProperties",
        "sanitizerRemovedTags=$sanitizerRemovedTagCount",
        "sanitizerRemovedAttrs=$sanitizerRemovedAttributeCount",
        "sanitizerDangerousProtocols=$sanitizerDangerousProtocolCount",
        "sanitizerEventHandlers=$sanitizerEventHandlerCount",
        "sanitizerRuntimeReason=$sanitizerRuntimeReason",
        "sanitizerExistingSafetyAgreed=$sanitizerExistingSafetyAgreed",
        "mediaRequestCount=$mediaRequestCount",
        "mediaKinds=$mediaKinds",
        "mediaSafety=$mediaSafety",
        "svgRouteCount=$svgRouteCount",
        "svgRoutes=$svgRoutes",
        "svgRouteReasons=$svgRouteReasons",
        "preparedDrawCacheHits=$preparedDrawCacheHitCount",
        "preparedDrawCacheMisses=$preparedDrawCacheMissCount",
        "preparedDrawCacheHitRate=$preparedDrawCacheHitRate",
        "preparedDrawCacheEvictions=$preparedDrawCacheEvictionCount",
        "compileParseMs=$compileParseMs",
        "compileCascadeMs=$compileCascadeMs",
        "compileDomCompileMs=$compileDomCompileMs",
        "compileRenderModelMs=$compileRenderModelMs",
        "compileOptimizerMs=$compileOptimizerMs",
        "compileTotalMs=$compileTotalMs",
        "placeholderHeightPx=${placeholderHeightPx ?: 0}",
        "measuredHeightPx=${measuredHeightPx ?: 0}",
        "heightCacheHit=$heightCacheHit",
        "heightCachePersistent=$heightCachePersistent",
        "heightCacheConfidence=$heightCacheConfidence",
        "heightCacheSourceRoute=$heightCacheSourceRoute",
        "measuredNativeHeight=${measuredNativeHeight ?: 0}",
        "measuredWebViewHeight=${measuredWebViewHeight ?: 0}",
        "heightDeltaPx=$heightDeltaPx",
        "heightDeltaRatio=$heightDeltaRatio",
        "renderTimeMs=${renderTimeMs ?: 0}",
        "fallbackReason=${fallbackReason.orEmpty()}",
    ).joinToString(" ")
}

internal data class RichFixtureReport(
    val metadata: RichFixtureRenderMetadata,
    val diff: RichVisualDiffMetrics,
) {
    fun metadataLine(): String = listOf(
        metadata.metadataLine(),
        "visualDiffClass=${diff.classification.name}",
        "visualDiffWidth=${diff.width}",
        "visualDiffHeight=${diff.height}",
        "visualDiffHeightDeltaPx=${diff.heightDeltaPx}",
        "visualDiffMeanAbsolutePixelDifference=${diff.meanAbsolutePixelDifference}",
        "visualDiffMismatchRatio=${diff.thresholdedPixelMismatchRatio}",
    ).joinToString(" ")
}

internal object RichFixtureReportBuilder {
    fun fromFixture(
        fixture: RichFidelityFixture,
        viewportWidthDp: Float = 390f,
        placeholderHeightPx: Int? = null,
        heightCacheConfidence: String = "",
        heightCacheSourceRoute: String = "",
        heightCacheHit: Boolean = false,
        heightCachePersistent: Boolean = false,
        measuredNativeHeight: Int? = null,
        measuredWebViewHeight: Int? = null,
        renderTimeMs: Long? = null,
        referenceDiff: RichVisualDiffMetrics? = null,
    ): RichFixtureReport {
        fixture.markdown?.let { markdown ->
            return fromCanonicalDocumentFixture(
                fixture = fixture,
                document = buildRichContentDocumentFromMarkdown(markdown),
                measuredNativeHeight = measuredNativeHeight,
                measuredWebViewHeight = measuredWebViewHeight,
                renderTimeMs = renderTimeMs,
                referenceDiff = referenceDiff,
            )
        }
        fixture.protocolActionDigestSource?.let { actionDigestSource ->
            return fromCanonicalDocumentFixture(
                fixture = fixture,
                document = buildRichContentDocumentFromProtocolAction(actionDigestSource),
                measuredNativeHeight = measuredNativeHeight,
                measuredWebViewHeight = measuredWebViewHeight,
                renderTimeMs = renderTimeMs,
                referenceDiff = referenceDiff,
            )
        }
        val html = fixture.html
        if (html.isNullOrBlank()) {
            val route = fixture.expectedRoute.orEmpty()
            return RichFixtureReport(
                metadata = RichFixtureRenderMetadata(
                    fixtureId = fixture.id,
                    digest = fixture.contentDigest,
                    sourceKind = fixture.sourceKind.name,
                    privacyMode = fixture.privacyMode.name,
                    category = fixture.category,
                    route = route,
                    planReason = "LocalDeviceOnly",
                    nativeConfidence = "",
                    visualHints = emptyMap(),
                    unsupportedReasons = emptyMap(),
                    snapshotIslandCount = 0,
                    textFlowCount = 0,
                    placeholderHeightPx = placeholderHeightPx,
                    measuredHeightPx = measuredNativeHeight ?: measuredWebViewHeight,
                    heightCacheConfidence = heightCacheConfidence,
                    heightCacheSourceRoute = heightCacheSourceRoute,
                    heightCacheHit = heightCacheHit,
                    heightCachePersistent = heightCachePersistent,
                    measuredNativeHeight = measuredNativeHeight,
                    measuredWebViewHeight = measuredWebViewHeight,
                    renderTimeMs = renderTimeMs,
                ),
                diff = referenceDiff ?: RichVisualDiff.compare(null, null),
            )
        }

        val analysis = analyzeRichHtml(html)
        val risk = RenderRiskScore.fromHtml(html, analysis)
        val sanitizer = RichHtmlSanitizer.inspect(html)
        val mediaStats = extractMediaStats(html)
        val cssEquivalence = extractCssEquivalenceStats(html)
        val preparedDrawStats = PreparedDrawCache.stats()
        val preparedDrawTotal = preparedDrawStats.hits + preparedDrawStats.misses
        val basePlan = buildRichRenderPlan(html = html, analysis = analysis, risk = risk)
        val svgSampleStart = RichHtmlRenderTelemetry.svgRouteSnapshot().size
        val snapshotIslandRenderStart = RichHtmlRenderTelemetry.snapshotIslandRenderSnapshot().size
        val compilePhaseStart = RichHtmlRenderTelemetry.compilePhaseSnapshot().size
        val startNanos = System.nanoTime()
        val model = RichHtmlCompiler.compile(
            html = html,
            options = RichHtmlCompileOptions(viewportWidthDp = viewportWidthDp),
        )
        val compilePhase = RichHtmlRenderTelemetry.compilePhaseSnapshot()
            .drop(compilePhaseStart)
            .lastOrNull { it.id == model.id }
        val compiledPlan = buildRichRenderPlan(html = html, analysis = analysis, model = model)
        val measuredRenderTimeMs = renderTimeMs ?: (System.nanoTime() - startNanos) / 1_000_000
        val orchestratorDecision = RichRenderOrchestrator.decide(
            RichRenderDecisionInput(
                plan = compiledPlan,
                analysis = analysis,
                risk = risk,
                scrollState = RichRenderScrollState(visibleCellRange = 0..0),
                cellIndex = 0,
                cachedModelAvailable = true,
                heightEntry = null,
                alreadyRendered = false,
                transient = false,
                nativeAdmission = RichHtmlRenderAdmission(
                    nativeAllowed = true,
                    reason = "compiled-cache",
                ),
            )
        )
        val actualRoute = orchestratorDecision.route.toPlanRoute()
        val document = buildRichContentDocumentFromHtml(html, analysis)
        val transform = RichContentTransformPipeline.report(
            document = document,
            currentRoute = actualRoute,
            model = model,
            observeSubtreeCache = true,
        )
        val routeClosure = buildRichRouteClosureReport(
            plannedRoute = basePlan.route.name,
            actualRoute = actualRoute.name,
            actualDecisionSource = RichActualDecisionSource.Orchestrator,
            runtimeFallback = model.unsupported.isNotEmpty(),
        )
        val cssSummary = RichHtmlRenderTelemetry.cssCascadeSnapshot()
            .filter { it.id == model.id }
            .fold(CssFixtureStats.Empty) { acc, sample -> acc + sample }
        val svgSourceIds = extractSvgSourceIds(html) + model.sourceHtmlByBlockId.values
            .filter { it.contains("<svg", ignoreCase = true) }
            .mapTo(linkedSetOf()) { renderTextCacheKey(it) }
        val svgSamples = RichHtmlRenderTelemetry.svgRouteSnapshot()
        val svgSummary = svgSamples
            .filterIndexed { index, sample -> index >= svgSampleStart || sample.id in svgSourceIds }
            .fold(SvgFixtureStats.Empty) { acc, sample -> acc + sample }
        val islandSourceIds = model.blocks.flatMap(::snapshotIslandSourceDigests).toSet()
        val islandRenderSummary = RichHtmlRenderTelemetry.snapshotIslandRenderSnapshot()
            .filterIndexed { index, sample -> index >= snapshotIslandRenderStart || sample.id in islandSourceIds }
            .fold(SnapshotIslandRenderFixtureStats.Empty) { acc, sample -> acc + sample }
        val actualRenderBlocks = model.blocks.sumOf(::countRichRenderBlocks).coerceAtLeast(1)
        val estimatedRenderBlocks = basePlan.estimatedRenderBlockCount.coerceAtLeast(actualRenderBlocks)
        val reductionRatio = ((estimatedRenderBlocks - actualRenderBlocks).coerceAtLeast(0).toFloat() /
            estimatedRenderBlocks.coerceAtLeast(1))
        val heightSample = RichHtmlRenderTelemetry.heightCacheSnapshot()
            .lastOrNull { it.id == compiledPlan.id || it.id == basePlan.id }
        val orchestratorSample = RichHtmlRenderTelemetry.orchestratorSnapshot()
            .lastOrNull { it.id == compiledPlan.id || it.id == basePlan.id }
        val metadata = RichFixtureRenderMetadata(
            fixtureId = fixture.id,
            digest = fixture.contentDigest,
            documentId = compiledPlan.documentId,
            documentSchemaVersion = compiledPlan.documentSchemaVersion,
            documentSourceKind = compiledPlan.documentSourceKind?.name.orEmpty(),
            sourceKind = fixture.sourceKind.name,
            privacyMode = fixture.privacyMode.name,
            category = fixture.category,
            route = actualRoute.name,
            plannedRoute = routeClosure.plannedRoute,
            actualRoute = routeClosure.actualRoute,
            routeMismatchReason = routeClosure.mismatchReason.name,
            actualDecisionSource = routeClosure.actualDecisionSource.name,
            renderPlanVersion = compiledPlan.version,
            renderModelVersion = model.version,
            planReason = basePlan.reason,
            nativeConfidence = compiledPlan.nativeConfidence.name,
            visualHints = model.visualHints.groupingBy { it.name }.eachCount(),
            unsupportedReasons = model.unsupported.groupingBy { it.name }.eachCount(),
            snapshotIslandCount = model.snapshotIslandStats.appliedCount,
            textFlowCount = compiledPlan.textFlowAppliedCount,
            transformPipelineVersion = transform.pipelineVersion,
            transformPassCount = transform.passOrder.size,
            transformPassDurationTotalMs = transform.totalPassDurationMs,
            transformPassDurationsMs = transform.passDurationsMs.mapKeys { it.key.name },
            astRoute = transform.astRoute.name,
            astRouteMismatchReason = transform.routeMismatchReason.name,
            transformImportConversionLossCount = transform.importConversionLossCount,
            transformWarningCount = transform.normalizationWarnings.values.sum(),
            astNodeCount = compiledPlan.astStats?.astNodeCount ?: 0,
            canonicalAstNodeCount = compiledPlan.documentStats?.canonicalNodeCount ?: 0,
            textFlowEligibleNodeCount = compiledPlan.documentStats?.textFlowEligibleSubtreeCount ?: 0,
            textFlowAppliedBlockCount = compiledPlan.textFlowAppliedCount,
            textFlowInlineFeaturePreservedCount = compiledPlan.textFlowInlineFeaturePreservedCount,
            textFlowDecisionSource = transform.textFlowDecisionSource.name,
            textFlowLoweringMode = transform.textFlowLoweringMode.name,
            renderBlockReductionRatio = reductionRatio,
            textFlowBlockedReasons = compiledPlan.textFlowBlockedReasons.groupingBy { it }.eachCount(),
            subtreeRouteCandidateNodeCount = transform.subtreeRouteCandidateNodeCount,
            subtreeRouteDecisionSource = transform.subtreeRouteDecisionSource.name,
            subtreeRouteLoweringMode = transform.subtreeRouteLoweringMode.name,
            subtreeRouteRejectedNodeCount = transform.subtreeRouteRejectedNodeCount,
            subtreeRouteRejectReasons = transform.subtreeRouteRejectReasons.groupingBy { it }.eachCount(),
            subtreeNativePreservedActionCount = transform.subtreeRouteNativePreservedActionCount,
            subtreeDigestCacheHitCount = transform.subtreeCacheHitCount,
            subtreeDigestCacheMissCount = transform.subtreeCacheMissCount,
            subtreeDigestCacheHitRate = transform.subtreeCacheHitRate,
            snapshotIslandAppliedCount = model.snapshotIslandStats.appliedCount,
            snapshotIslandRejectedCount = model.snapshotIslandStats.rejectedCount,
            wholeSnapshotAvoided = model.snapshotIslandStats.wholeSnapshotAvoided,
            snapshotIslandRenderCount = islandRenderSummary.renderCount,
            snapshotIslandBitmapCacheHitCount = islandRenderSummary.bitmapCacheHitCount,
            snapshotIslandBitmapCacheMissCount = islandRenderSummary.bitmapCacheMissCount,
            snapshotIslandHeightCacheHitCount = islandRenderSummary.heightCacheHitCount,
            snapshotIslandFallbackReasons = islandRenderSummary.fallbackReasons,
            snapshotIslandStyleBoundaries = islandRenderSummary.styleBoundaries,
            cssRuleCount = cssSummary.ruleCount,
            cssSelectorCount = cssSummary.selectorCount,
            cssIdRuleCount = cssSummary.idRuleCount,
            cssClassRuleCount = cssSummary.classRuleCount,
            cssTagRuleCount = cssSummary.tagRuleCount,
            cssAttrRuleCount = cssSummary.attrRuleCount,
            cssPseudoRuleCount = cssSummary.pseudoRuleCount,
            cssUniversalRuleCount = cssSummary.universalRuleCount,
            cssComplexRuleCount = cssSummary.complexRuleCount,
            cssUnsupportedSelectorCount = cssSummary.unsupportedSelectorCount,
            cssAverageCandidateRules = cssSummary.averageCandidateRules,
            cssParserFallbackCount = cssSummary.parserFallbackCount,
            cssIndexMismatchCount = cssSummary.indexMismatchCount,
            cssLegacyVerificationSkippedCount = cssSummary.legacyVerificationSkippedCount,
            cssP95CandidateRules = cssSummary.p95CandidateRules,
            cssSelectorMatchMs = cssSummary.selectorMatchMs,
            cssHighCostSelectorCategories = cssSummary.highCostSelectorCategories,
            cssEquivalenceFixtureCount = cssEquivalence.fixtureCount,
            cssEquivalenceMismatchCount = cssEquivalence.mismatchCount,
            cssEquivalenceFallbackUsedCount = cssEquivalence.fallbackUsedCount,
            cssEquivalenceMismatchProperties = cssEquivalence.mismatchProperties,
            sanitizerRemovedTagCount = sanitizer.removedTagCount,
            sanitizerRemovedAttributeCount = sanitizer.removedAttributeCount,
            sanitizerDangerousProtocolCount = sanitizer.dangerousProtocolCount,
            sanitizerEventHandlerCount = sanitizer.eventHandlerCount,
            sanitizerRuntimeReason = sanitizer.runtimeReason.orEmpty(),
            sanitizerExistingSafetyAgreed = sanitizer.existingSafetyAgreed,
            mediaRequestCount = mediaStats.requestCount,
            mediaKinds = mediaStats.kinds,
            mediaSafety = mediaStats.safety,
            svgRouteCount = svgSummary.routeCount,
            svgRoutes = svgSummary.routes,
            svgRouteReasons = svgSummary.reasons,
            preparedDrawCacheHitCount = preparedDrawStats.hits,
            preparedDrawCacheMissCount = preparedDrawStats.misses,
            preparedDrawCacheHitRate = if (preparedDrawTotal > 0L) {
                preparedDrawStats.hits.toFloat() / preparedDrawTotal.toFloat()
            } else {
                0f
            },
            preparedDrawCacheEvictionCount = preparedDrawStats.evictions,
            compileParseMs = compilePhase?.parseMs ?: 0L,
            compileCascadeMs = compilePhase?.cascadeMs ?: 0L,
            compileDomCompileMs = compilePhase?.domCompileMs ?: 0L,
            compileRenderModelMs = compilePhase?.renderModelMs ?: 0L,
            compileOptimizerMs = compilePhase?.optimizerMs ?: 0L,
            compileTotalMs = compilePhase?.totalMs ?: 0L,
            placeholderHeightPx = orchestratorSample?.placeholderHeightPx ?: placeholderHeightPx,
            measuredHeightPx = heightSample?.heightPx ?: measuredNativeHeight ?: measuredWebViewHeight,
            heightCacheHit = heightSample?.hit ?: heightCacheHit,
            heightCachePersistent = heightSample?.persistent ?: heightCachePersistent,
            heightCacheConfidence = heightSample?.confidence ?: orchestratorSample?.heightConfidence ?: heightCacheConfidence,
            heightCacheSourceRoute = orchestratorSample?.placeholderSource ?: heightCacheSourceRoute,
            measuredNativeHeight = measuredNativeHeight,
            measuredWebViewHeight = measuredWebViewHeight,
            renderTimeMs = measuredRenderTimeMs,
            fallbackReason = orchestratorDecision.reason.ifBlank { null },
        )
        return RichFixtureReport(
            metadata = metadata,
            diff = referenceDiff ?: RichVisualDiff.compare(
                native = null,
                reference = null,
                dynamic = actualRoute == RichRenderPlanRoute.DynamicPreview,
                knownUnsupported = model.unsupported.isNotEmpty(),
            ),
        )
    }

    private fun fromCanonicalDocumentFixture(
        fixture: RichFidelityFixture,
        document: RichContentDocument,
        measuredNativeHeight: Int?,
        measuredWebViewHeight: Int?,
        renderTimeMs: Long?,
        referenceDiff: RichVisualDiffMetrics?,
    ): RichFixtureReport {
        val routeReport = document.routeReport()
        val route = routeReport.route.toPlanRoute().name
        val documentOnlyModel = RichTextFlowAstLowerer.lowerDocumentOnly(document)
        val transform = RichContentTransformPipeline.report(
            document = document,
            currentRoute = routeReport.route.toPlanRoute(),
            model = documentOnlyModel,
            observeSubtreeCache = true,
        )
        val textFlowCount = documentOnlyModel?.blocks?.count { block -> block is RichTextFlowBlock } ?: 0
        val estimatedBlocks = document.stats.canonicalNodeCount.coerceAtLeast(1)
        val actualBlocks = documentOnlyModel?.blocks?.sumOf(::countRichRenderBlocks)
            ?: document.stats.nativeBackendCount.coerceAtLeast(1)
        val reductionRatio = ((estimatedBlocks - actualBlocks).coerceAtLeast(0).toFloat() /
            estimatedBlocks.coerceAtLeast(1))
        return RichFixtureReport(
            metadata = RichFixtureRenderMetadata(
                fixtureId = fixture.id,
                digest = fixture.contentDigest,
                documentId = document.documentId,
                documentSchemaVersion = document.schemaVersion,
                documentSourceKind = document.sourceKind.name,
                sourceKind = fixture.sourceKind.name,
                privacyMode = fixture.privacyMode.name,
                category = fixture.category,
                route = route,
                plannedRoute = route,
                actualRoute = route,
                routeMismatchReason = "None",
                actualDecisionSource = RichActualDecisionSource.Orchestrator.name,
                planReason = routeReport.reason,
                nativeConfidence = "",
                visualHints = emptyMap(),
                unsupportedReasons = emptyMap(),
                snapshotIslandCount = 0,
                textFlowCount = textFlowCount,
                transformPipelineVersion = transform.pipelineVersion,
                transformPassCount = transform.passOrder.size,
                transformPassDurationTotalMs = transform.totalPassDurationMs,
                transformPassDurationsMs = transform.passDurationsMs.mapKeys { it.key.name },
                astRoute = transform.astRoute.name,
                astRouteMismatchReason = transform.routeMismatchReason.name,
                transformImportConversionLossCount = transform.importConversionLossCount,
                transformWarningCount = transform.normalizationWarnings.values.sum(),
                astNodeCount = document.imports.sourceNodeCount,
                canonicalAstNodeCount = document.stats.canonicalNodeCount,
                textFlowEligibleNodeCount = document.stats.textFlowEligibleSubtreeCount,
                textFlowAppliedBlockCount = textFlowCount,
                textFlowInlineFeaturePreservedCount = document.stats.markRangeCount,
                textFlowDecisionSource = transform.textFlowDecisionSource.name,
                textFlowLoweringMode = transform.textFlowLoweringMode.name,
                renderBlockReductionRatio = reductionRatio,
                textFlowBlockedReasons = transform.textFlowHardStopReasons.groupingBy { it }.eachCount(),
                subtreeRouteCandidateNodeCount = transform.subtreeRouteCandidateNodeCount,
                subtreeRouteDecisionSource = transform.subtreeRouteDecisionSource.name,
                subtreeRouteLoweringMode = transform.subtreeRouteLoweringMode.name,
                subtreeRouteRejectedNodeCount = transform.subtreeRouteRejectedNodeCount,
                subtreeRouteRejectReasons = transform.subtreeRouteRejectReasons.groupingBy { it }.eachCount(),
                subtreeNativePreservedActionCount = transform.subtreeRouteNativePreservedActionCount,
                subtreeDigestCacheHitCount = transform.subtreeCacheHitCount,
                subtreeDigestCacheMissCount = transform.subtreeCacheMissCount,
                subtreeDigestCacheHitRate = transform.subtreeCacheHitRate,
                measuredNativeHeight = measuredNativeHeight,
                measuredWebViewHeight = measuredWebViewHeight,
                renderTimeMs = renderTimeMs,
            ),
            diff = referenceDiff ?: RichVisualDiff.compare(null, null),
        )
    }
}

private fun RichRenderDecisionRoute.toPlanRoute(): RichRenderPlanRoute = when (this) {
    RichRenderDecisionRoute.Native,
    RichRenderDecisionRoute.NativeDeferred -> RichRenderPlanRoute.Native
    RichRenderDecisionRoute.Snapshot -> RichRenderPlanRoute.Snapshot
    RichRenderDecisionRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview
    RichRenderDecisionRoute.Lightweight -> RichRenderPlanRoute.Lightweight
    RichRenderDecisionRoute.InlineWebView -> RichRenderPlanRoute.InlineWebView
}

private fun RichContentDocumentRoute.toPlanRoute(): RichRenderPlanRoute = when (this) {
    RichContentDocumentRoute.Native,
    RichContentDocumentRoute.NativeWithSnapshotIslands -> RichRenderPlanRoute.Native
    RichContentDocumentRoute.Snapshot -> RichRenderPlanRoute.Snapshot
    RichContentDocumentRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview
}

private fun extractSvgSourceIds(html: String): Set<String> {
    val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull() ?: return emptySet()
    return document.select("svg").mapTo(linkedSetOf()) { svg ->
        renderTextCacheKey(svg.outerHtml())
    }
}

private fun extractCssEquivalenceStats(html: String): RichCssDeclarationEquivalenceReport {
    val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull()
        ?: return RichCssDeclarationEquivalenceReport.Empty
    val declarationBodies = buildList {
        document.select("[style]").forEach { element ->
            add(element.attr("style"))
        }
        document.select("style").forEach { style ->
            CSS_RULE_BODY.findAll(style.data()).forEach { match ->
                add(match.groupValues.getOrNull(1).orEmpty())
            }
        }
    }
    return RichCssParser.declarationEquivalence(declarationBodies)
}

private fun extractMediaStats(html: String): MediaFixtureStats {
    val kinds = linkedMapOf<String, Int>()
    val safety = linkedMapOf<String, Int>()
    fun add(source: String?, kind: RichMediaKind) {
        val request = RichMediaRequest.fromSource(source, kind)
        val route = RichMediaLoader.safety(request)
        kinds[kind.name] = (kinds[kind.name] ?: 0) + 1
        safety[route.name] = (safety[route.name] ?: 0) + 1
    }
    val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull()
        ?: return MediaFixtureStats.Empty
    document.select("img[src]").forEach { element ->
        add(element.attr("src"), RichMediaKind.Image)
    }
    document.select("[style]").forEach { element ->
        addStyleMedia(element.attr("style"), ::add)
    }
    document.select("style").forEach { style ->
        CSS_RULE_BODY.findAll(style.data().ifBlank { style.html() }).forEach { match ->
            addStyleMedia(match.groupValues.getOrNull(1).orEmpty(), ::add)
        }
    }
    return MediaFixtureStats(
        requestCount = kinds.values.sum(),
        kinds = kinds,
        safety = safety,
    )
}

private fun addStyleMedia(
    declarations: String,
    add: (String?, RichMediaKind) -> Unit,
) {
    BACKGROUND_URL.findAll(declarations).forEach { match ->
        add(match.firstUrlGroup, RichMediaKind.BackgroundImage)
    }
    LIST_STYLE_URL.findAll(declarations).forEach { match ->
        add(match.firstUrlGroup, RichMediaKind.ListStyleImage)
    }
}

private val BACKGROUND_URL = Regex(
    """background(?:-image)?\s*:[^;]*url\((?:"([^"]+)"|'([^']+)'|([^)]+))\)""",
    setOf(RegexOption.IGNORE_CASE),
)
private val LIST_STYLE_URL = Regex(
    """list-style-image\s*:[^;]*url\((?:"([^"]+)"|'([^']+)'|([^)]+))\)""",
    setOf(RegexOption.IGNORE_CASE),
)
private val CSS_RULE_BODY = Regex("""\{([^{}]*)\}""")

private val MatchResult.firstUrlGroup: String?
    get() = groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim()

private data class MediaFixtureStats(
    val requestCount: Int,
    val kinds: Map<String, Int>,
    val safety: Map<String, Int>,
) {
    companion object {
        val Empty = MediaFixtureStats(
            requestCount = 0,
            kinds = emptyMap(),
            safety = emptyMap(),
        )
    }
}

private data class SvgFixtureStats(
    val routeCount: Int,
    val routes: Map<String, Int>,
    val reasons: Map<String, Int>,
) {
    operator fun plus(sample: me.rerere.rikkahub.ui.components.message.RichSvgRouteTelemetrySample): SvgFixtureStats =
        SvgFixtureStats(
            routeCount = routeCount + 1,
            routes = routes + (sample.route to ((routes[sample.route] ?: 0) + 1)),
            reasons = reasons + (sample.reason to ((reasons[sample.reason] ?: 0) + 1)),
        )

    companion object {
        val Empty = SvgFixtureStats(
            routeCount = 0,
            routes = emptyMap(),
            reasons = emptyMap(),
        )
    }
}

private data class SnapshotIslandRenderFixtureStats(
    val renderCount: Int,
    val bitmapCacheHitCount: Int,
    val bitmapCacheMissCount: Int,
    val heightCacheHitCount: Int,
    val fallbackReasons: Map<String, Int>,
    val styleBoundaries: Map<String, Int>,
) {
    operator fun plus(
        sample: me.rerere.rikkahub.ui.components.message.RichSnapshotIslandRenderSample,
    ): SnapshotIslandRenderFixtureStats =
        SnapshotIslandRenderFixtureStats(
            renderCount = renderCount + 1,
            bitmapCacheHitCount = bitmapCacheHitCount + if (sample.cacheHit) 1 else 0,
            bitmapCacheMissCount = bitmapCacheMissCount + if (sample.cacheHit) 0 else 1,
            heightCacheHitCount = heightCacheHitCount + if (sample.heightCacheHit == true) 1 else 0,
            fallbackReasons = sample.fallbackReason
                ?.takeIf { it.isNotBlank() }
                ?.let { fallbackReasons + (it to ((fallbackReasons[it] ?: 0) + 1)) }
                ?: fallbackReasons,
            styleBoundaries = sample.styleBoundary
                ?.takeIf { it.isNotBlank() }
                ?.let { styleBoundaries + (it to ((styleBoundaries[it] ?: 0) + 1)) }
                ?: styleBoundaries,
        )

    companion object {
        val Empty = SnapshotIslandRenderFixtureStats(
            renderCount = 0,
            bitmapCacheHitCount = 0,
            bitmapCacheMissCount = 0,
            heightCacheHitCount = 0,
            fallbackReasons = emptyMap(),
            styleBoundaries = emptyMap(),
        )
    }
}

private fun snapshotIslandSourceDigests(block: RichBlock): List<String> = when (block) {
    is RichSnapshotIslandBlock -> listOf(block.sourceDigest)
    is RichContainerBlock -> block.children.flatMap(::snapshotIslandSourceDigests)
    is RichButtonBlock -> block.children.flatMap(::snapshotIslandSourceDigests) +
        block.inlineBoxes.flatMap { snapshotIslandSourceDigests(it.block) }
    is RichDetailsBlock -> block.children.flatMap(::snapshotIslandSourceDigests)
    is RichTextBlock -> block.inlineBoxes.flatMap { snapshotIslandSourceDigests(it.block) }
    else -> emptyList()
}

private data class CssFixtureStats(
    val ruleCount: Int,
    val selectorCount: Int,
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
    val parserFallbackCount: Int,
    val indexMismatchCount: Int,
    val legacyVerificationSkippedCount: Int,
    val p95CandidateRules: Int,
    val selectorMatchMs: Long,
    val highCostSelectorCategories: Map<String, Int>,
) {
    operator fun plus(sample: me.rerere.rikkahub.ui.components.message.RichCssCascadeTelemetrySample): CssFixtureStats =
        merge(sample)

    private fun merge(sample: me.rerere.rikkahub.ui.components.message.RichCssCascadeTelemetrySample): CssFixtureStats {
        val nextElementCount = elementCount + sample.elementCount
        val nextAverageCandidates = if (nextElementCount > 0) {
            ((averageCandidateRules * elementCount.toFloat()) +
                (sample.averageCandidateRules * sample.elementCount.toFloat())) / nextElementCount.toFloat()
        } else {
            0f
        }
        return CssFixtureStats(
            ruleCount = ruleCount + sample.ruleCount,
            selectorCount = selectorCount + sample.ruleCount,
            idRuleCount = idRuleCount + sample.idRuleCount,
            classRuleCount = classRuleCount + sample.classRuleCount,
            tagRuleCount = tagRuleCount + sample.tagRuleCount,
            attrRuleCount = attrRuleCount + sample.attrRuleCount,
            pseudoRuleCount = pseudoRuleCount + sample.pseudoRuleCount,
            universalRuleCount = universalRuleCount + sample.universalRuleCount,
            complexRuleCount = complexRuleCount + sample.complexRuleCount,
            unsupportedSelectorCount = unsupportedSelectorCount + sample.unsupportedSelectorCount,
            elementCount = nextElementCount,
            averageCandidateRules = nextAverageCandidates,
            parserFallbackCount = parserFallbackCount + sample.parserFallbackCount,
            indexMismatchCount = indexMismatchCount + sample.indexMismatchCount,
            legacyVerificationSkippedCount = legacyVerificationSkippedCount + sample.legacyVerificationSkippedCount,
            p95CandidateRules = maxOf(p95CandidateRules, sample.p95CandidateRules),
            selectorMatchMs = maxOf(selectorMatchMs, sample.selectorMatchMs),
            highCostSelectorCategories = highCostSelectorCategories.mergeCounts(sample.highCostSelectorCategories),
        )
    }

    companion object {
        val Empty = CssFixtureStats(
            ruleCount = 0,
            selectorCount = 0,
            idRuleCount = 0,
            classRuleCount = 0,
            tagRuleCount = 0,
            attrRuleCount = 0,
            pseudoRuleCount = 0,
            universalRuleCount = 0,
            complexRuleCount = 0,
            unsupportedSelectorCount = 0,
            elementCount = 0,
            averageCandidateRules = 0f,
            parserFallbackCount = 0,
            indexMismatchCount = 0,
            legacyVerificationSkippedCount = 0,
            p95CandidateRules = 0,
            selectorMatchMs = 0L,
            highCostSelectorCategories = emptyMap(),
        )
    }
}

private fun Map<String, Int>.mergeCounts(other: Map<String, Int>): Map<String, Int> {
    if (isEmpty()) return other
    if (other.isEmpty()) return this
    val merged = toMutableMap()
    other.forEach { (key, value) -> merged[key] = (merged[key] ?: 0) + value }
    return merged
}

internal enum class RichGapPriority {
    HighFrequencyHighImpact,
    HighFrequencyLowImpact,
    LowFrequencyHighImpact,
    LowFrequencyLowImpact,
}

internal data class RichGapReportItem(
    val key: String,
    val frequency: Int,
    val impact: Int,
    val priority: RichGapPriority,
)

internal object RichFrequencyWeightedGapReport {
    fun rank(reports: List<RichFixtureReport>): List<RichGapReportItem> {
        val grouped = reports.groupBy { report ->
            report.metadata.unsupportedReasons.keys.firstOrNull()
                ?: report.metadata.visualHints.keys.firstOrNull()
                ?: report.metadata.category.name
        }
        val highFrequencyCutoff = (reports.size / 3).coerceAtLeast(2)
        return grouped.map { (key, values) ->
            val impact = values.sumOf {
                when (it.diff.classification) {
                    RichVisualDiffClass.NeedsReview -> 3
                    RichVisualDiffClass.MinorDifference -> 1
                    RichVisualDiffClass.ReferenceFailed -> 2
                    RichVisualDiffClass.KnownUnsupported -> 2
                    RichVisualDiffClass.Pass,
                    RichVisualDiffClass.DynamicNotComparable -> 0
                }
            }
            val highFrequency = values.size >= highFrequencyCutoff
            val highImpact = impact >= values.size.coerceAtLeast(1) * 2
            RichGapReportItem(
                key = key,
                frequency = values.size,
                impact = impact,
                priority = when {
                    highFrequency && highImpact -> RichGapPriority.HighFrequencyHighImpact
                    highFrequency -> RichGapPriority.HighFrequencyLowImpact
                    highImpact -> RichGapPriority.LowFrequencyHighImpact
                    else -> RichGapPriority.LowFrequencyLowImpact
                },
            )
        }.sortedWith(compareByDescending<RichGapReportItem> { it.priority.ordinal == 0 }.thenByDescending { it.impact }.thenByDescending { it.frequency })
    }
}

internal data class RichAggregateFidelityReport(
    val fixtureCountByCategory: Map<String, Int>,
    val routeDistribution: Map<String, Int>,
    val routeMismatchCount: Int,
    val nonOrchestratorDecisionCount: Int,
    val routeMismatchFixtures: List<String>,
    val nonOrchestratorDecisionFixtures: List<String>,
    val routeMismatchReasonRanking: Map<String, Int>,
    val actualDecisionSourceRanking: Map<String, Int>,
    val plannedActualRoutePairs: Map<String, Int>,
    val visualDiffClassRanking: Map<String, Int>,
    val visualDiffReferenceFailedCount: Int,
    val visualHintRanking: Map<String, Int>,
    val unsupportedReasonRanking: Map<String, Int>,
    val snapshotIslandAppliedCount: Int,
    val wholeSnapshotAvoidedCount: Int,
    val snapshotIslandBenefitFixtures: List<String>,
    val totalSnapshotIslandRenderCount: Int,
    val totalSnapshotIslandBitmapCacheHits: Int,
    val totalSnapshotIslandBitmapCacheMisses: Int,
    val totalSnapshotIslandHeightCacheHits: Int,
    val snapshotIslandFallbackReasonRanking: Map<String, Int>,
    val snapshotIslandStyleBoundaryRanking: Map<String, Int>,
    val totalSubtreeDigestCacheHits: Int,
    val totalSubtreeDigestCacheMisses: Int,
    val averageSubtreeDigestCacheHitRate: Float,
    val averageHeightDelta: Float,
    val p95HeightDelta: Int,
    val heightCacheHitCount: Int,
    val persistentHeightCacheHitCount: Int,
    val heightCacheConfidenceRanking: Map<String, Int>,
    val heightCacheSourceRouteRanking: Map<String, Int>,
    val averagePlaceholderHeightPx: Float,
    val worstHeightDeltaFixtures: List<String>,
    val averageRenderTimeMs: Float,
    val p95RenderTimeMs: Long,
    val averageCompileTotalMs: Float,
    val p95CompileTotalMs: Long,
    val averageCompileParseMs: Float,
    val averageCompileCascadeMs: Float,
    val averageCompileDomCompileMs: Float,
    val averageCompileRenderModelMs: Float,
    val averageCompileOptimizerMs: Float,
    val slowCompileFixtures: List<String>,
    val slowCascadeFixtures: List<String>,
    val slowOptimizerFixtures: List<String>,
    val totalCssSelectorCount: Int,
    val totalCssIdRuleCount: Int,
    val totalCssClassRuleCount: Int,
    val totalCssTagRuleCount: Int,
    val totalCssAttrRuleCount: Int,
    val totalCssPseudoRuleCount: Int,
    val totalCssUniversalRuleCount: Int,
    val totalCssComplexRuleCount: Int,
    val averageCssCandidateRules: Float,
    val averageCssP95CandidateRules: Float,
    val totalCssParserFallbackCount: Int,
    val totalCssUnsupportedSelectorCount: Int,
    val totalCssIndexMismatchCount: Int,
    val totalCssLegacyVerificationSkippedCount: Int,
    val cssHighCostSelectorRanking: Map<String, Int>,
    val totalCssEquivalenceFixtureCount: Int,
    val totalCssEquivalenceMismatchCount: Int,
    val totalCssEquivalenceFallbackUsedCount: Int,
    val cssEquivalenceMismatchPropertyRanking: Map<String, Int>,
    val cssEquivalenceMismatchFixtures: List<String>,
    val sanitizerWarningCount: Int,
    val totalSanitizerRemovedTagCount: Int,
    val totalSanitizerRemovedAttributeCount: Int,
    val totalSanitizerDangerousProtocolCount: Int,
    val totalSanitizerEventHandlerCount: Int,
    val sanitizerRuntimeReasonRanking: Map<String, Int>,
    val sanitizerDisagreementCount: Int,
    val totalMediaRequestCount: Int,
    val mediaFailureCount: Int,
    val mediaKindRanking: Map<String, Int>,
    val mediaSafetyRanking: Map<String, Int>,
    val totalSvgRouteCount: Int,
    val svgRouteRanking: Map<String, Int>,
    val svgRouteReasonRanking: Map<String, Int>,
    val totalPreparedDrawCacheHits: Long,
    val totalPreparedDrawCacheMisses: Long,
    val averagePreparedDrawCacheHitRate: Float,
    val worstSelectorCandidateFixtures: List<String>,
    val selectorIndexMismatchFixtures: List<String>,
    val slowSelectorFixtures: List<String>,
    val topGaps: List<RichGapReportItem>,
    val benchmarkJourneyReport: RichBenchmarkJourneyReport = RichBenchmarkJourneyManifest.deferredNoDeviceReport(),
) {
    fun summaryLine(): String = listOf(
        "fixtureCount=${fixtureCountByCategory.values.sum()}",
        "routes=$routeDistribution",
        "routeMismatches=$routeMismatchCount",
        "nonOrchestratorDecisions=$nonOrchestratorDecisionCount",
        "snapshotIslandApplied=$snapshotIslandAppliedCount",
        "wholeSnapshotAvoided=$wholeSnapshotAvoidedCount",
        "snapshotIslandRenders=$totalSnapshotIslandRenderCount",
        "snapshotIslandBitmapCacheHits=$totalSnapshotIslandBitmapCacheHits",
        "snapshotIslandBitmapCacheMisses=$totalSnapshotIslandBitmapCacheMisses",
        "snapshotIslandHeightCacheHits=$totalSnapshotIslandHeightCacheHits",
        "snapshotIslandFallbackReasons=$snapshotIslandFallbackReasonRanking",
        "snapshotIslandStyleBoundaries=$snapshotIslandStyleBoundaryRanking",
        "avgHeightDelta=$averageHeightDelta",
        "p95HeightDelta=$p95HeightDelta",
        "heightCacheHits=$heightCacheHitCount",
        "persistentHeightCacheHits=$persistentHeightCacheHitCount",
        "heightCacheConfidence=$heightCacheConfidenceRanking",
        "heightCacheSourceRoutes=$heightCacheSourceRouteRanking",
        "avgPlaceholderHeightPx=$averagePlaceholderHeightPx",
        "avgRenderTimeMs=$averageRenderTimeMs",
        "p95RenderTimeMs=$p95RenderTimeMs",
        "avgCompileTotalMs=$averageCompileTotalMs",
        "p95CompileTotalMs=$p95CompileTotalMs",
        "avgCompileParseMs=$averageCompileParseMs",
        "avgCompileCascadeMs=$averageCompileCascadeMs",
        "avgCompileDomCompileMs=$averageCompileDomCompileMs",
        "avgCompileRenderModelMs=$averageCompileRenderModelMs",
        "avgCompileOptimizerMs=$averageCompileOptimizerMs",
        "slowCompileFixtures=$slowCompileFixtures",
        "cssSelectors=$totalCssSelectorCount",
        "cssBuckets=id:$totalCssIdRuleCount class:$totalCssClassRuleCount tag:$totalCssTagRuleCount " +
            "attr:$totalCssAttrRuleCount pseudo:$totalCssPseudoRuleCount universal:$totalCssUniversalRuleCount " +
            "complex:$totalCssComplexRuleCount",
        "avgCssCandidates=$averageCssCandidateRules",
        "worstHeightDeltaFixtures=$worstHeightDeltaFixtures",
        "routeMismatchFixtures=$routeMismatchFixtures",
        "nonOrchestratorDecisionFixtures=$nonOrchestratorDecisionFixtures",
        "routeMismatchReasons=$routeMismatchReasonRanking",
        "actualDecisionSources=$actualDecisionSourceRanking",
        "plannedActualRoutePairs=$plannedActualRoutePairs",
        "visualDiffClasses=$visualDiffClassRanking",
        "visualDiffReferenceFailed=$visualDiffReferenceFailedCount",
        "snapshotIslandBenefitFixtures=$snapshotIslandBenefitFixtures",
        "cssIndexMismatches=$totalCssIndexMismatchCount",
        "cssLegacySkipped=$totalCssLegacyVerificationSkippedCount",
        "cssHighCostSelectors=$cssHighCostSelectorRanking",
        "cssEquivalenceFixtures=$totalCssEquivalenceFixtureCount",
        "cssEquivalenceMismatches=$totalCssEquivalenceMismatchCount",
        "cssEquivalenceFallbackUsed=$totalCssEquivalenceFallbackUsedCount",
        "cssEquivalenceMismatchProperties=$cssEquivalenceMismatchPropertyRanking",
        "cssEquivalenceMismatchFixtures=$cssEquivalenceMismatchFixtures",
        "avgCssP95Candidates=$averageCssP95CandidateRules",
        "cssParserFallbacks=$totalCssParserFallbackCount",
        "cssUnsupportedSelectors=$totalCssUnsupportedSelectorCount",
        "sanitizerWarnings=$sanitizerWarningCount",
        "sanitizerRemovedTags=$totalSanitizerRemovedTagCount",
        "sanitizerRemovedAttrs=$totalSanitizerRemovedAttributeCount",
        "sanitizerDangerousProtocols=$totalSanitizerDangerousProtocolCount",
        "sanitizerEventHandlers=$totalSanitizerEventHandlerCount",
        "sanitizerRuntimeReasons=$sanitizerRuntimeReasonRanking",
        "sanitizerDisagreements=$sanitizerDisagreementCount",
        "mediaRequests=$totalMediaRequestCount",
        "mediaFailures=$mediaFailureCount",
        "mediaKinds=$mediaKindRanking",
        "mediaSafety=$mediaSafetyRanking",
        "svgRoutes=$svgRouteRanking",
        "svgRouteReasons=$svgRouteReasonRanking",
        "preparedDrawHits=$totalPreparedDrawCacheHits",
        "preparedDrawMisses=$totalPreparedDrawCacheMisses",
        benchmarkJourneyReport.summaryLine(),
    ).joinToString(" ")

    companion object {
        fun from(reports: List<RichFixtureReport>): RichAggregateFidelityReport {
            fun Iterable<String>.counts(): Map<String, Int> = groupingBy { it }.eachCount()
            fun averageLong(selector: (RichFixtureRenderMetadata) -> Long): Float =
                reports.map { selector(it.metadata) }
                    .filter { it > 0L }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toFloat() ?: 0f
            val heightDeltas = reports.map { kotlin.math.abs(it.heightDeltaForAggregate()) }.sorted()
            val renderTimes = reports.mapNotNull { it.metadata.renderTimeMs }.sorted()
            val compileTotals = reports.map { it.metadata.compileTotalMs }.filter { it > 0L }.sorted()
            return RichAggregateFidelityReport(
                fixtureCountByCategory = reports.map { it.metadata.category.name }.counts(),
                routeDistribution = reports.map { it.metadata.route }.counts(),
                routeMismatchCount = reports.count { it.metadata.routeMismatchReason != "None" },
                nonOrchestratorDecisionCount = reports.count { it.metadata.actualDecisionSource != "Orchestrator" },
                routeMismatchFixtures = reports
                    .filter { it.metadata.routeMismatchReason != "None" }
                    .map { it.metadata.fixtureId },
                nonOrchestratorDecisionFixtures = reports
                    .filter { it.metadata.actualDecisionSource != "Orchestrator" }
                    .map { it.metadata.fixtureId },
                routeMismatchReasonRanking = reports
                    .map { it.metadata.routeMismatchReason }
                    .filter { it != "None" }
                    .counts(),
                actualDecisionSourceRanking = reports
                    .map { it.metadata.actualDecisionSource }
                    .counts(),
                plannedActualRoutePairs = reports
                    .map { "${it.metadata.plannedRoute}->${it.metadata.actualRoute}" }
                    .counts(),
                visualDiffClassRanking = reports
                    .map { it.diff.classification.name }
                    .counts(),
                visualDiffReferenceFailedCount = reports.count {
                    it.diff.classification == RichVisualDiffClass.ReferenceFailed
                },
                visualHintRanking = reports.flatMap { report ->
                    report.metadata.visualHints.flatMap { (key, count) -> List(count) { key } }
                }.counts(),
                unsupportedReasonRanking = reports.flatMap { report ->
                    report.metadata.unsupportedReasons.flatMap { (key, count) -> List(count) { key } }
                }.counts(),
                snapshotIslandAppliedCount = reports.sumOf { it.metadata.snapshotIslandAppliedCount },
                wholeSnapshotAvoidedCount = reports.count { it.metadata.wholeSnapshotAvoided },
                snapshotIslandBenefitFixtures = reports
                    .filter { it.metadata.wholeSnapshotAvoided || it.metadata.snapshotIslandAppliedCount > 0 }
                    .sortedByDescending { it.metadata.snapshotIslandAppliedCount }
                    .take(8)
                    .map { it.metadata.fixtureId },
                totalSnapshotIslandRenderCount = reports.sumOf { it.metadata.snapshotIslandRenderCount },
                totalSnapshotIslandBitmapCacheHits = reports.sumOf { it.metadata.snapshotIslandBitmapCacheHitCount },
                totalSnapshotIslandBitmapCacheMisses = reports.sumOf { it.metadata.snapshotIslandBitmapCacheMissCount },
                totalSnapshotIslandHeightCacheHits = reports.sumOf { it.metadata.snapshotIslandHeightCacheHitCount },
                snapshotIslandFallbackReasonRanking = reports
                    .flatMap { report ->
                        report.metadata.snapshotIslandFallbackReasons.flatMap { (key, count) -> List(count) { key } }
                    }
                    .counts(),
                snapshotIslandStyleBoundaryRanking = reports
                    .flatMap { report ->
                        report.metadata.snapshotIslandStyleBoundaries.flatMap { (key, count) -> List(count) { key } }
                    }
                    .counts(),
                totalSubtreeDigestCacheHits = reports.sumOf { it.metadata.subtreeDigestCacheHitCount },
                totalSubtreeDigestCacheMisses = reports.sumOf { it.metadata.subtreeDigestCacheMissCount },
                averageSubtreeDigestCacheHitRate = reports.map { it.metadata.subtreeDigestCacheHitRate }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toFloat() ?: 0f,
                averageHeightDelta = heightDeltas.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
                p95HeightDelta = heightDeltas.percentile95(),
                heightCacheHitCount = reports.count { it.metadata.heightCacheHit },
                persistentHeightCacheHitCount = reports.count {
                    it.metadata.heightCacheHit && it.metadata.heightCachePersistent
                },
                heightCacheConfidenceRanking = reports
                    .map { it.metadata.heightCacheConfidence }
                    .filter { it.isNotBlank() }
                    .counts(),
                heightCacheSourceRouteRanking = reports
                    .map { it.metadata.heightCacheSourceRoute }
                    .filter { it.isNotBlank() }
                    .counts(),
                averagePlaceholderHeightPx = reports.mapNotNull { it.metadata.placeholderHeightPx }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toFloat() ?: 0f,
                worstHeightDeltaFixtures = reports
                    .filter { it.heightDeltaForAggregate() != 0 }
                    .sortedByDescending { kotlin.math.abs(it.heightDeltaForAggregate()) }
                    .take(8)
                    .map { it.metadata.fixtureId },
                averageRenderTimeMs = renderTimes.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
                p95RenderTimeMs = renderTimes.percentile95(),
                averageCompileTotalMs = averageLong { it.compileTotalMs },
                p95CompileTotalMs = compileTotals.percentile95(),
                averageCompileParseMs = averageLong { it.compileParseMs },
                averageCompileCascadeMs = averageLong { it.compileCascadeMs },
                averageCompileDomCompileMs = averageLong { it.compileDomCompileMs },
                averageCompileRenderModelMs = averageLong { it.compileRenderModelMs },
                averageCompileOptimizerMs = averageLong { it.compileOptimizerMs },
                slowCompileFixtures = reports
                    .filter { it.metadata.compileTotalMs > 0L }
                    .sortedByDescending { it.metadata.compileTotalMs }
                    .take(8)
                    .map { it.metadata.fixtureId },
                slowCascadeFixtures = reports
                    .filter { it.metadata.compileCascadeMs > 0L }
                    .sortedByDescending { it.metadata.compileCascadeMs }
                    .take(8)
                    .map { it.metadata.fixtureId },
                slowOptimizerFixtures = reports
                    .filter { it.metadata.compileOptimizerMs > 0L }
                    .sortedByDescending { it.metadata.compileOptimizerMs }
                    .take(8)
                    .map { it.metadata.fixtureId },
                totalCssSelectorCount = reports.sumOf { it.metadata.cssSelectorCount },
                totalCssIdRuleCount = reports.sumOf { it.metadata.cssIdRuleCount },
                totalCssClassRuleCount = reports.sumOf { it.metadata.cssClassRuleCount },
                totalCssTagRuleCount = reports.sumOf { it.metadata.cssTagRuleCount },
                totalCssAttrRuleCount = reports.sumOf { it.metadata.cssAttrRuleCount },
                totalCssPseudoRuleCount = reports.sumOf { it.metadata.cssPseudoRuleCount },
                totalCssUniversalRuleCount = reports.sumOf { it.metadata.cssUniversalRuleCount },
                totalCssComplexRuleCount = reports.sumOf { it.metadata.cssComplexRuleCount },
                averageCssCandidateRules = reports.map { it.metadata.cssAverageCandidateRules }
                    .filter { it > 0f }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toFloat() ?: 0f,
                averageCssP95CandidateRules = reports.map { it.metadata.cssP95CandidateRules }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toFloat() ?: 0f,
                totalCssParserFallbackCount = reports.sumOf { it.metadata.cssParserFallbackCount },
                totalCssUnsupportedSelectorCount = reports.sumOf { it.metadata.cssUnsupportedSelectorCount },
                totalCssIndexMismatchCount = reports.sumOf { it.metadata.cssIndexMismatchCount },
                totalCssLegacyVerificationSkippedCount = reports.sumOf {
                    it.metadata.cssLegacyVerificationSkippedCount
                },
                cssHighCostSelectorRanking = reports
                    .flatMap { report ->
                        report.metadata.cssHighCostSelectorCategories.flatMap { (category, count) ->
                            List(count) { category }
                        }
                    }
                    .counts(),
                totalCssEquivalenceFixtureCount = reports.sumOf { it.metadata.cssEquivalenceFixtureCount },
                totalCssEquivalenceMismatchCount = reports.sumOf { it.metadata.cssEquivalenceMismatchCount },
                totalCssEquivalenceFallbackUsedCount = reports.sumOf { it.metadata.cssEquivalenceFallbackUsedCount },
                cssEquivalenceMismatchPropertyRanking = reports
                    .flatMap { report ->
                        report.metadata.cssEquivalenceMismatchProperties.flatMap { (property, count) ->
                            List(count) { property }
                        }
                    }
                    .counts(),
                cssEquivalenceMismatchFixtures = reports
                    .filter { it.metadata.cssEquivalenceMismatchCount > 0 }
                    .sortedByDescending { it.metadata.cssEquivalenceMismatchCount }
                    .take(8)
                    .map { it.metadata.fixtureId },
                sanitizerWarningCount = reports.sumOf { it.metadata.sanitizerWarningCount() },
                totalSanitizerRemovedTagCount = reports.sumOf { it.metadata.sanitizerRemovedTagCount },
                totalSanitizerRemovedAttributeCount = reports.sumOf { it.metadata.sanitizerRemovedAttributeCount },
                totalSanitizerDangerousProtocolCount = reports.sumOf {
                    it.metadata.sanitizerDangerousProtocolCount
                },
                totalSanitizerEventHandlerCount = reports.sumOf { it.metadata.sanitizerEventHandlerCount },
                sanitizerRuntimeReasonRanking = reports
                    .map { it.metadata.sanitizerRuntimeReason }
                    .filter { it.isNotBlank() }
                    .counts(),
                sanitizerDisagreementCount = reports.count { !it.metadata.sanitizerExistingSafetyAgreed },
                totalMediaRequestCount = reports.sumOf { it.metadata.mediaRequestCount },
                mediaFailureCount = reports.sumOf { it.metadata.mediaFailureCount() },
                mediaKindRanking = reports
                    .flatMap { report ->
                        report.metadata.mediaKinds.flatMap { (kind, count) -> List(count) { kind } }
                    }
                    .counts(),
                mediaSafetyRanking = reports
                    .flatMap { report ->
                        report.metadata.mediaSafety.flatMap { (safety, count) -> List(count) { safety } }
                    }
                    .counts(),
                totalSvgRouteCount = reports.sumOf { it.metadata.svgRouteCount },
                svgRouteRanking = reports
                    .flatMap { report ->
                        report.metadata.svgRoutes.flatMap { (route, count) -> List(count) { route } }
                    }
                    .counts(),
                svgRouteReasonRanking = reports
                    .flatMap { report ->
                        report.metadata.svgRouteReasons.flatMap { (reason, count) -> List(count) { reason } }
                    }
                    .counts(),
                totalPreparedDrawCacheHits = reports.sumOf { it.metadata.preparedDrawCacheHitCount },
                totalPreparedDrawCacheMisses = reports.sumOf { it.metadata.preparedDrawCacheMissCount },
                averagePreparedDrawCacheHitRate = reports.map { it.metadata.preparedDrawCacheHitRate }
                    .average()
                    .takeIf { !it.isNaN() }
                    ?.toFloat() ?: 0f,
                worstSelectorCandidateFixtures = reports
                    .filter { it.metadata.cssP95CandidateRules > 0 }
                    .sortedByDescending { it.metadata.cssP95CandidateRules }
                    .take(8)
                    .map { it.metadata.fixtureId },
                selectorIndexMismatchFixtures = reports
                    .filter { it.metadata.cssIndexMismatchCount > 0 }
                    .sortedByDescending { it.metadata.cssIndexMismatchCount }
                    .take(8)
                    .map { it.metadata.fixtureId },
                slowSelectorFixtures = reports
                    .filter { it.metadata.cssSelectorMatchMs > 0 }
                    .sortedByDescending { it.metadata.cssSelectorMatchMs }
                    .take(8)
                    .map { it.metadata.fixtureId },
                topGaps = RichFrequencyWeightedGapReport.rank(reports).take(12),
            )
        }
    }
}

private fun RichFixtureReport.heightDeltaForAggregate(): Int {
    val nativeHeight = metadata.measuredNativeHeight
    val webViewHeight = metadata.measuredWebViewHeight
    if (nativeHeight != null && webViewHeight != null) return nativeHeight - webViewHeight
    return metadata.heightDeltaPx.takeIf { it != 0 } ?: diff.heightDeltaPx
}

private fun RichFixtureRenderMetadata.sanitizerWarningCount(): Int =
    sanitizerRemovedTagCount +
        sanitizerRemovedAttributeCount +
        sanitizerDangerousProtocolCount +
        sanitizerEventHandlerCount +
        (if (sanitizerRuntimeReason.isNotBlank()) 1 else 0) +
        (if (!sanitizerExistingSafetyAgreed) 1 else 0)

private fun RichFixtureRenderMetadata.mediaFailureCount(): Int =
    mediaSafety.filterKeys { it != "Safe" }.values.sum()

private fun List<Int>.percentile95(): Int = if (isEmpty()) 0 else this[((size - 1) * 0.95f).toInt().coerceIn(indices)]
private fun List<Long>.percentile95(): Long = if (isEmpty()) 0 else this[((size - 1) * 0.95f).toInt().coerceIn(indices)]
