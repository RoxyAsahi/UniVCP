package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.message.NativeConfidence
import me.rerere.rikkahub.ui.components.message.RichHtmlFallbackStage
import me.rerere.rikkahub.ui.components.message.RichHtmlAnalysis
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderKind
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.RichHtmlSanitizer
import me.rerere.rikkahub.ui.components.message.RichRenderHeightCacheState
import me.rerere.rikkahub.ui.components.message.RichRenderPlan
import me.rerere.rikkahub.ui.components.message.RichRenderPlanRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotDecision
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotPolicy
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlSafetyReason
import me.rerere.rikkahub.ui.components.message.RichActualDecisionSource
import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichContentRoute
import me.rerere.rikkahub.ui.components.message.analyzeRichHtml
import me.rerere.rikkahub.ui.components.message.buildRichRenderPlan
import me.rerere.rikkahub.ui.components.message.buildRichRouteClosureReport
import me.rerere.rikkahub.ui.components.message.inspectRichHtmlSafety
import me.rerere.rikkahub.ui.components.message.inspectRichRenderEstimatorMetrics
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun RichHtmlBubbleBlock(
    html: String,
    modifier: Modifier = Modifier,
    onSendInput: (String) -> Unit = {},
    renderFallback: (@Composable () -> Unit)? = null,
    transientCache: Boolean = false,
    enableSnapshot: Boolean = true,
    onOpenPreview: (() -> Unit)? = null,
    renderCellIndex: Int? = null,
    renderRisk: RenderRiskScore? = null,
    heightContentType: String = "rich-html",
    renderPlan: RichRenderPlan? = null,
) {
    BoxWithConstraints(modifier = modifier.testTag("rich-html-bubble")) {
        val rootModifier = Modifier.fillMaxWidth()
        val viewportWidthDp = remember(maxWidth) {
            if (maxWidth != Dp.Infinity && maxWidth > 0.dp) maxWidth.value else 360f
        }
        val fallbackContent = renderFallback ?: {
            Text(
                text = "富内容渲染失败，可打开动态预览。",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val analysis = remember(html) { analyzeRichHtml(html) }
        val renderId = remember(html) { renderTextCacheKey(html) }
        val effectiveRisk = remember(html, analysis, renderRisk) {
            renderRisk ?: RenderRiskScore.fromHtml(html, analysis)
        }
        val density = LocalDensity.current
        val context = LocalContext.current
        remember(context) {
            RichHtmlHeightCache.initialize(context)
            true
        }
        val themeBucket = if (isSystemInDarkTheme()) "dark" else "light"
        val heightKey = remember(
            renderId,
            viewportWidthDp,
            density.fontScale,
            density.density,
            themeBucket,
            heightContentType,
        ) {
            RichHtmlHeightCache.key(
                id = renderId,
                viewportWidthDp = viewportWidthDp,
                fontScale = density.fontScale,
                density = density.density,
                themeBucket = themeBucket,
                contentType = heightContentType,
            )
        }
        val cachedHeightEntry = remember(heightKey) { RichHtmlHeightCache.getEntry(heightKey) }
        val cachedHeightPx = cachedHeightEntry?.heightPx
        val estimatedHeightPx = remember(html, analysis, density.density, density.fontScale) {
            with(density) {
                estimateRichHtmlPlaceholderHeightDp(html, analysis).dp.roundToPx()
            }
        }
        val placeholderHeight = remember(cachedHeightEntry, estimatedHeightPx) {
            RichPlaceholderHeight.from(cachedHeightEntry, estimatedHeightPx)
        }
        val heightCacheState = remember(transientCache, cachedHeightPx) {
            when {
                transientCache -> RichRenderHeightCacheState.Unknown
                cachedHeightPx != null -> RichRenderHeightCacheState.Hit
                else -> RichRenderHeightCacheState.Miss
            }
        }
        val compileOptions = remember(viewportWidthDp) {
            RichHtmlCompileOptions(viewportWidthDp = viewportWidthDp)
        }
        val cachedCompiledModel = remember(renderId, compileOptions, transientCache) {
            if (transientCache) {
                null
            } else {
                RichHtmlCompiler.getCachedById(
                    id = renderId,
                    options = compileOptions,
                    cacheMode = RichHtmlCompileCacheMode.Persistent,
                )
            }
        }
        val cachedInitialSnapshotDecision = remember(analysis, cachedCompiledModel) {
            richInitialSnapshotDecision(analysis, cachedCompiledModel)
        }
        var initialSnapshotDecision by remember(renderId) {
            mutableStateOf(cachedInitialSnapshotDecision)
        }
        LaunchedEffect(renderId, cachedInitialSnapshotDecision) {
            initialSnapshotDecision = cachedInitialSnapshotDecision
        }
        val baseRenderPlan = remember(
            renderPlan,
            html,
            analysis,
            effectiveRisk,
            cachedCompiledModel,
            initialSnapshotDecision,
            heightCacheState,
        ) {
            val plan = (renderPlan ?: buildRichRenderPlan(
                html = html,
                analysis = analysis,
                risk = effectiveRisk,
                model = cachedCompiledModel,
                includeStructuralReport = false,
            )).copy(heightCache = heightCacheState)
            plan.withInitialSnapshotDecision(initialSnapshotDecision)
        }
        LaunchedEffect(renderId, heightContentType, cachedHeightEntry) {
            RichHtmlRenderTelemetry.recordHeightCache(
                id = renderId,
                contentType = heightContentType,
                hit = cachedHeightPx != null,
                heightPx = cachedHeightPx,
                confidence = cachedHeightEntry?.confidence?.name,
                rendererVersion = heightKey.rendererVersion,
                documentSchemaVersion = heightKey.documentSchemaVersion,
                persistent = !transientCache,
            )
        }
        LaunchedEffect(renderId, html, effectiveRisk) {
            val sanitizerReport = withContext(Dispatchers.Default) {
                RichHtmlSanitizer.inspect(html)
            }
            RichHtmlRenderTelemetry.recordSanitizerReport(
                id = renderId,
                report = sanitizerReport,
            )
        }
        val scrollState = LocalRichRenderScrollState.current
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        var cachedModelAvailable by remember(renderId, compileOptions, transientCache) {
            mutableStateOf(cachedCompiledModel != null)
        }
        var alreadyRendered by remember(renderId) {
            mutableStateOf(RichHtmlRenderScheduler.hasRendered(renderId))
        }
        var nativeAdmissionRetryTick by remember(renderId) { mutableIntStateOf(0) }
        val shouldRequestNativeAdmission = !transientCache &&
            effectiveRisk.route != RichContentRoute.Snapshot &&
            effectiveRisk.route != RichContentRoute.DynamicPreview &&
            analysis.kind != RichHtmlRenderKind.ComplexDynamic &&
            initialSnapshotDecision.route == RichHtmlSnapshotRoute.Native
        val nativeAdmission = remember(
            renderId,
            analysis,
            scrollState,
            transientCache,
            renderCellIndex,
            effectiveRisk,
            shouldRequestNativeAdmission,
            cachedModelAvailable,
            nativeAdmissionRetryTick,
        ) {
            if (transientCache) {
                RichHtmlRenderAdmission(nativeAllowed = true, reason = "transient")
            } else if (shouldRequestNativeAdmission) {
                RichHtmlRenderScheduler.admission(
                    key = renderId,
                    analysis = analysis,
                    scrollState = scrollState,
                    cellIndex = renderCellIndex,
                    risk = effectiveRisk,
                    cachedModelAvailable = cachedModelAvailable,
                    reserveFirstRenderSlot = true,
                )
            } else {
                null
            }
        }
        LaunchedEffect(renderId, nativeAdmission?.reason, nativeAdmission?.nativeAllowed) {
            if (nativeAdmission?.nativeAllowed == true && nativeAdmission.reason == "already-rendered") {
                alreadyRendered = true
            }
        }
        LaunchedEffect(renderId, compileOptions, transientCache, alreadyRendered) {
            cachedModelAvailable = when {
                transientCache -> false
                alreadyRendered -> true
                cachedModelAvailable -> true
                else -> RichHtmlCompiler.getCachedById(
                    id = renderId,
                    options = compileOptions,
                    cacheMode = RichHtmlCompileCacheMode.Persistent,
                ) != null
            }
        }
        val orchestratorDecision = remember(
            baseRenderPlan,
            analysis,
            effectiveRisk,
            scrollState,
            renderCellIndex,
            cachedModelAvailable,
            cachedHeightEntry,
            alreadyRendered,
            transientCache,
            estimatedHeightPx,
            nativeAdmission,
        ) {
            RichRenderOrchestrator.decide(
                RichRenderDecisionInput(
                    plan = baseRenderPlan,
                    analysis = analysis,
                    risk = effectiveRisk,
                    scrollState = scrollState,
                    cellIndex = renderCellIndex,
                    cachedModelAvailable = cachedModelAvailable,
                    heightEntry = cachedHeightEntry,
                    alreadyRendered = alreadyRendered,
                    transient = transientCache,
                    estimatedPlaceholderHeightPx = estimatedHeightPx,
                    nativeAdmission = nativeAdmission,
                )
            )
        }
        LaunchedEffect(renderId, baseRenderPlan.route, orchestratorDecision, placeholderHeight) {
            RichHtmlRenderTelemetry.recordOrchestratorDecision(
                id = renderId,
                route = orchestratorDecision.route.name,
                reason = orchestratorDecision.reason,
                placeholderHeightPx = orchestratorDecision.placeholderHeightPx,
                placeholderSource = placeholderHeight.source,
                nativeAdmissionAllowed = orchestratorDecision.nativeAdmissionAllowed,
                shouldPrewarm = orchestratorDecision.shouldPrewarm,
                alreadyRendered = alreadyRendered,
                cachedModelAvailable = cachedModelAvailable,
                transient = transientCache,
                heightConfidence = cachedHeightEntry?.confidence?.name,
            )
            RichHtmlRenderTelemetry.recordRouteClosure(
                id = renderId,
                report = buildRichRouteClosureReport(
                    plannedRoute = baseRenderPlan.route.name,
                    actualRoute = orchestratorDecision.route.toActualPlanRouteName(),
                    actualDecisionSource = RichActualDecisionSource.Orchestrator,
                ),
            )
        }
        LaunchedEffect(
            renderId,
            orchestratorDecision.route,
            orchestratorDecision.reason,
            scrollState.scrollInProgress,
            scrollState.fastScrolling,
            renderCellIndex,
        ) {
            if (shouldRetryNativeAdmission(orchestratorDecision, scrollState, renderCellIndex)) {
                delay(RICH_HTML_NATIVE_ADMISSION_RETRY_DELAY_MS)
                nativeAdmissionRetryTick += 1
            }
        }
        DisposableEffect(renderId, nativeAdmission?.nativeAllowed, transientCache) {
            onDispose {
                if (nativeAdmission?.nativeAllowed == true && !transientCache) {
                    RichHtmlRenderScheduler.release(renderId)
                }
            }
        }
        var lastRecordedMeasuredHeightPx by remember(renderId) { mutableIntStateOf(-1) }
        val measuredRootModifier = rootModifier.onSizeChanged { size ->
                if (!transientCache && size.height > 0 && size.height != lastRecordedMeasuredHeightPx) {
                    lastRecordedMeasuredHeightPx = size.height
                    val measuredHeightPx = size.height
                    mainHandler.post {
                        RichHtmlRenderTelemetry.recordHeightDelta(
                            id = renderId,
                            route = orchestratorDecision.route.name,
                            placeholderHeightPx = placeholderHeight.heightPx,
                            measuredHeightPx = measuredHeightPx,
                            placeholderSource = placeholderHeight.source,
                            heightConfidence = cachedHeightEntry?.confidence?.name,
                        )
                        RichHtmlHeightCache.put(
                            key = heightKey,
                            heightPx = measuredHeightPx,
                            confidence = RichRenderHeightConfidence.MeasuredNative,
                        )
                        RichHtmlRenderScheduler.markRendered(renderId)
                        if (!alreadyRendered) {
                            alreadyRendered = true
                        }
                        RichHtmlRenderCircuitBreaker.recordSuccess(renderId)
                    }
            }
        }
        val snapshotAllowed = enableSnapshot && !transientCache

        @Composable
        fun SnapshotOrFallback(reason: String) {
            val forcedSnapshot = effectiveRisk.route == RichContentRoute.Snapshot
            if (!snapshotAllowed || (analysis.kind == RichHtmlRenderKind.ComplexDynamic && !forcedSnapshot)) {
                fallbackContent()
                return
            }
            val safety = remember(html) { inspectRichHtmlSafety(html) }
            if (!safety.safeForNative && safety.reason.isDangerousForSnapshot()) {
                fallbackContent()
            } else {
                RichHtmlSnapshotBlock(
                    html = html,
                    previewText = analysis.previewText,
                    reason = reason,
                    placeholderHeightPx = placeholderHeight.heightPx,
                    heightCacheKey = heightKey,
                    onOpenPreview = onOpenPreview,
                    fallback = fallbackContent,
                )
            }
        }

        when {
            orchestratorDecision.route == RichRenderDecisionRoute.Snapshot -> {
                RecordRichRenderPlanEffect(
                    baseRenderPlan.withRoute(
                        route = RichRenderPlanRoute.Snapshot,
                        reason = orchestratorDecision.reason,
                    )
                )
                SnapshotOrFallback(
                    reason = orchestratorDecision.reason +
                        RichHtmlRenderCircuitBreaker.failureReason(renderId)?.let { ":$it" }.orEmpty()
                )
            }

            orchestratorDecision.route == RichRenderDecisionRoute.DynamicPreview ||
                orchestratorDecision.route == RichRenderDecisionRoute.InlineWebView -> {
                RecordRichRenderPlanEffect(
                    baseRenderPlan.withRoute(
                        route = RichRenderPlanRoute.DynamicPreview,
                        reason = orchestratorDecision.reason,
                    )
                )
                fallbackContent()
            }

            orchestratorDecision.route == RichRenderDecisionRoute.NativeDeferred -> {
                RecordRichRenderPlanEffect(
                    baseRenderPlan.withRoute(
                        route = RichRenderPlanRoute.Lightweight,
                        reason = orchestratorDecision.reason,
                    )
                )
                PreparingRichHtmlPlaceholder(
                    previewText = analysis.previewText,
                    placeholderHeightPx = placeholderHeight.heightPx,
                    placeholderSource = placeholderHeight.source,
                )
            }

            orchestratorDecision.route == RichRenderDecisionRoute.Lightweight -> {
                RecordRichRenderPlanEffect(
                    baseRenderPlan.withRoute(
                        route = RichRenderPlanRoute.Lightweight,
                        reason = orchestratorDecision.reason,
                    )
                )
                LightweightRichHtmlPlaceholder(
                    previewText = analysis.previewText,
                    reason = orchestratorDecision.reason,
                    placeholderHeightPx = placeholderHeight.heightPx,
                    placeholderSource = placeholderHeight.source,
                )
            }

            else -> {
                var failed by remember(html, viewportWidthDp) { mutableStateOf(false) }
                val model = rememberRichHtmlRenderModel(
                    html = html,
                    viewportWidthDp = viewportWidthDp,
                    transientCache = transientCache,
                    onFailure = {
                        failed = true
                        RichHtmlRenderTelemetry.recordFallback(
                            stage = RichHtmlFallbackStage.Compile,
                            reason = it::class.simpleName ?: "CompileFailure",
                        )
                        RichHtmlRenderCircuitBreaker.recordFailure(
                            key = renderId,
                            reason = it::class.simpleName ?: "CompileFailure",
                        )
                    },
                )
                when {
                    failed -> {
                        ReleaseNativeAdmissionEffect(
                            renderId = renderId,
                            nativeAdmission = nativeAdmission,
                            transientCache = transientCache,
                            reason = "compile-failed",
                        )
                        val failureDecision = RichHtmlSnapshotPolicy.nativeFailure(analysis)
                        RecordRichRenderPlanEffect(
                            baseRenderPlan.withRoute(
                                route = failureDecision.route.toRichRenderPlanRoute(),
                                reason = failureDecision.reason.ifBlank { "NativeFailure" },
                            )
                        )
                        SnapshotOrFallback(failureDecision.reason)
                    }
                    model?.unsupported?.contains(RichUnsupportedReason.UnsafeHtml) == true -> {
                        val unsafeModel = model
                        ReleaseNativeAdmissionEffect(
                            renderId = renderId,
                            nativeAdmission = nativeAdmission,
                            transientCache = transientCache,
                            reason = "unsafe-model",
                        )
                        val unsafePlan = remember(html, analysis, effectiveRisk, unsafeModel, heightCacheState) {
                            buildRichRenderPlan(
                                html = html,
                                analysis = analysis,
                                risk = effectiveRisk,
                                model = unsafeModel,
                                heightCacheState = heightCacheState,
                                includeStructuralReport = false,
                            )
                        }
                        RecordRichRenderPlanEffect(
                            unsafePlan.withRoute(
                                route = RichRenderPlanRoute.DynamicPreview,
                                reason = "UnsafeHtml",
                            )
                        )
                        fallbackContent()
                    }
                    model == null -> {
                        RecordRichRenderPlanEffect(
                            baseRenderPlan.withRoute(
                                route = RichRenderPlanRoute.Lightweight,
                                reason = "CompilePending",
                            )
                        )
                        PreparingRichHtmlPlaceholder(
                    previewText = analysis.previewText,
                    placeholderHeightPx = placeholderHeight.heightPx,
                    placeholderSource = placeholderHeight.source,
                )
            }

                    else -> {
                        val compiledInitialSnapshotDecision = remember(analysis, model) {
                            richInitialSnapshotDecision(analysis, model)
                        }
                        LaunchedEffect(model.id) {
                            cachedModelAvailable = true
                            if (compiledInitialSnapshotDecision.route != RichHtmlSnapshotRoute.Native) {
                                initialSnapshotDecision = compiledInitialSnapshotDecision
                            }
                        }
                        val compiledPlan = remember(html, analysis, effectiveRisk, model, heightCacheState) {
                            buildRichRenderPlan(
                                html = html,
                                analysis = analysis,
                                risk = effectiveRisk,
                                model = model,
                                heightCacheState = heightCacheState,
                                includeStructuralReport = false,
                            )
                        }
                        val compiledOrchestratorDecision = remember(
                            compiledPlan,
                            analysis,
                            effectiveRisk,
                            scrollState,
                            renderCellIndex,
                            cachedModelAvailable,
                            cachedHeightEntry,
                            alreadyRendered,
                            transientCache,
                            estimatedHeightPx,
                            nativeAdmission,
                        ) {
                            RichRenderOrchestrator.decide(
                                RichRenderDecisionInput(
                                    plan = compiledPlan,
                                    analysis = analysis,
                                    risk = effectiveRisk,
                                    scrollState = scrollState,
                                    cellIndex = renderCellIndex,
                                    cachedModelAvailable = cachedModelAvailable,
                                    heightEntry = cachedHeightEntry,
                                    alreadyRendered = alreadyRendered,
                                    transient = transientCache,
                                    estimatedPlaceholderHeightPx = estimatedHeightPx,
                                    nativeAdmission = nativeAdmission,
                                )
                            )
                        }
                        LaunchedEffect(renderId, compiledPlan.route, compiledOrchestratorDecision, placeholderHeight) {
                            RichHtmlRenderTelemetry.recordOrchestratorDecision(
                                id = renderId,
                                route = compiledOrchestratorDecision.route.name,
                                reason = "Compiled:${compiledOrchestratorDecision.reason}",
                                placeholderHeightPx = compiledOrchestratorDecision.placeholderHeightPx,
                                placeholderSource = placeholderHeight.source,
                                nativeAdmissionAllowed = compiledOrchestratorDecision.nativeAdmissionAllowed,
                                shouldPrewarm = compiledOrchestratorDecision.shouldPrewarm,
                                alreadyRendered = alreadyRendered,
                                cachedModelAvailable = cachedModelAvailable,
                                transient = transientCache,
                                heightConfidence = cachedHeightEntry?.confidence?.name,
                            )
                            RichHtmlRenderTelemetry.recordRouteClosure(
                                id = renderId,
                                report = buildRichRouteClosureReport(
                                    plannedRoute = compiledPlan.route.name,
                                    actualRoute = compiledOrchestratorDecision.route.toActualPlanRouteName(),
                                    actualDecisionSource = RichActualDecisionSource.Orchestrator,
                                    runtimeFallback = model.unsupported.isNotEmpty(),
                                ),
                            )
                        }
                        if (compiledOrchestratorDecision.route != RichRenderDecisionRoute.Native) {
                            ReleaseNativeAdmissionEffect(
                                renderId = renderId,
                                nativeAdmission = nativeAdmission,
                                transientCache = transientCache,
                                reason = "compiled-${compiledOrchestratorDecision.route.name}",
                            )
                        }
                        when (compiledOrchestratorDecision.route) {
                            RichRenderDecisionRoute.DynamicPreview,
                            RichRenderDecisionRoute.InlineWebView -> {
                                RecordCompiledRichRenderPlanEffect(
                                    html = html,
                                    analysis = analysis,
                                    risk = effectiveRisk,
                                    model = model,
                                    heightCacheState = heightCacheState,
                                    route = RichRenderPlanRoute.DynamicPreview,
                                    reason = compiledOrchestratorDecision.reason,
                                )
                                fallbackContent()
                            }
                            RichRenderDecisionRoute.Snapshot -> {
                                RecordCompiledRichRenderPlanEffect(
                                    html = html,
                                    analysis = analysis,
                                    risk = effectiveRisk,
                                    model = model,
                                    heightCacheState = heightCacheState,
                                    route = RichRenderPlanRoute.Snapshot,
                                    reason = compiledOrchestratorDecision.reason,
                                )
                                SnapshotOrFallback(compiledOrchestratorDecision.reason)
                            }
                            RichRenderDecisionRoute.NativeDeferred,
                            RichRenderDecisionRoute.Lightweight -> {
                                RecordCompiledRichRenderPlanEffect(
                                    html = html,
                                    analysis = analysis,
                                    risk = effectiveRisk,
                                    model = model,
                                    heightCacheState = heightCacheState,
                                    route = RichRenderPlanRoute.Lightweight,
                                    reason = compiledOrchestratorDecision.reason,
                                )
                                PreparingRichHtmlPlaceholder(
                                    previewText = analysis.previewText,
                                    placeholderHeightPx = placeholderHeight.heightPx,
                                    placeholderSource = placeholderHeight.source,
                                )
                            }
                            RichRenderDecisionRoute.Native -> {
                                val nativePresentationAllowed = rememberNativePresentationAllowed(
                                    renderId = renderId,
                                    viewportWidthDp = viewportWidthDp,
                                    model = model,
                                    transientCache = transientCache,
                                    cachedModelAvailable = cachedModelAvailable,
                                    scrollState = scrollState,
                                )
                                if (!nativePresentationAllowed) {
                                    ReleaseNativeAdmissionEffect(
                                        renderId = renderId,
                                        nativeAdmission = nativeAdmission,
                                        transientCache = transientCache,
                                        reason = "native-presentation-deferred",
                                    )
                                    RecordCompiledRichRenderPlanEffect(
                                        html = html,
                                        analysis = analysis,
                                        risk = effectiveRisk,
                                        model = model,
                                        heightCacheState = heightCacheState,
                                        route = RichRenderPlanRoute.Lightweight,
                                        reason = "NativePresentationDeferred",
                                    )
                                    PreparingRichHtmlPlaceholder(
                                        previewText = analysis.previewText,
                                        placeholderHeightPx = placeholderHeight.heightPx,
                                        placeholderSource = placeholderHeight.source,
                                    )
                                    return@BoxWithConstraints
                                }
                                RecordCompiledRichRenderPlanEffect(
                                    html = html,
                                    analysis = analysis,
                                    risk = effectiveRisk,
                                    model = model,
                                    heightCacheState = heightCacheState,
                                    route = compiledPlan.route,
                                    reason = compiledPlan.reason,
                                )
                                val nativeFailureDecision = remember(analysis) {
                                    RichHtmlSnapshotPolicy.nativeFailure(analysis)
                                }
                                GuardedRichHtmlRender(
                                    modifier = measuredRootModifier,
                                    fallbackMinHeightPx = placeholderHeight.heightPx,
                                    fallback = {
                                        if (nativeFailureDecision.route == RichHtmlSnapshotRoute.Snapshot) {
                                            SnapshotOrFallback(nativeFailureDecision.reason)
                                        } else {
                                            fallbackContent()
                                        }
                                    },
                                    telemetryId = model.id,
                                    viewportWidthDp = viewportWidthDp,
                                ) {
                                    RichHtmlRenderer(
                                        model = model,
                                        onSendInput = onSendInput,
                                        nativeAnimationsEnabled = !transientCache,
                                        animationHostId = model.id,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordRichRenderPlanEffect(plan: RichRenderPlan) {
    LaunchedEffect(plan) {
        RichHtmlRenderTelemetry.recordRichRenderPlan(plan)
    }
}

@Composable
private fun RecordCompiledRichRenderPlanEffect(
    html: String,
    analysis: RichHtmlAnalysis,
    risk: RenderRiskScore,
    model: RichHtmlRenderModel,
    heightCacheState: RichRenderHeightCacheState,
    route: RichRenderPlanRoute,
    reason: String,
) {
    LaunchedEffect(html, analysis, risk, model.id, heightCacheState, route, reason) {
        if (!BuildConfig.DEBUG) return@LaunchedEffect
        val enrichedPlan = withContext(Dispatchers.Default) {
            buildRichRenderPlan(
                html = html,
                analysis = analysis,
                risk = risk,
                model = model,
                heightCacheState = heightCacheState,
                includeStructuralReport = true,
            ).withRoute(route = route, reason = reason)
        }
        RichHtmlRenderTelemetry.recordRichRenderPlan(enrichedPlan)
    }
}

private fun RichRenderDecisionRoute.toActualPlanRouteName(): String = when (this) {
    RichRenderDecisionRoute.Native -> RichRenderPlanRoute.Native.name
    RichRenderDecisionRoute.NativeDeferred,
    RichRenderDecisionRoute.Lightweight -> RichRenderPlanRoute.Lightweight.name
    RichRenderDecisionRoute.Snapshot -> RichRenderPlanRoute.Snapshot.name
    RichRenderDecisionRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview.name
    RichRenderDecisionRoute.InlineWebView -> RichRenderPlanRoute.InlineWebView.name
}

@Composable
private fun ReleaseNativeAdmissionEffect(
    renderId: String,
    nativeAdmission: RichHtmlRenderAdmission?,
    transientCache: Boolean,
    reason: String,
) {
    LaunchedEffect(renderId, nativeAdmission?.nativeAllowed, transientCache, reason) {
        if (nativeAdmission?.nativeAllowed == true && !transientCache) {
            RichHtmlRenderScheduler.release(renderId)
            RichHtmlRenderTelemetry.recordNativeAdmission(
                id = renderId,
                allowed = false,
                reason = "release:$reason",
                cellIndex = null,
                riskScore = null,
                visibleCellRange = 0..-1,
                fastScrolling = false,
            )
        }
    }
}

@Composable
private fun rememberNativePresentationAllowed(
    renderId: String,
    viewportWidthDp: Float,
    model: RichHtmlRenderModel,
    transientCache: Boolean,
    cachedModelAvailable: Boolean,
    scrollState: RichRenderScrollState,
): Boolean {
    val alreadyRendered = RichHtmlRenderScheduler.hasRendered(renderId)
    val deferForScroll = remember(model) { shouldDeferNativePresentationDuringScroll(model) }
    var allowed by remember(renderId, viewportWidthDp, transientCache, cachedModelAvailable) {
        mutableStateOf(
            shouldAllowNativePresentationImmediately(
                transientCache = transientCache,
                alreadyRendered = alreadyRendered,
                cachedModelAvailable = cachedModelAvailable,
                deferForScroll = deferForScroll,
            )
        )
    }
    var scrollDeferralLogged by remember(renderId, viewportWidthDp, transientCache) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        renderId,
        viewportWidthDp,
        model.id,
        transientCache,
        cachedModelAvailable,
        alreadyRendered,
        scrollState.scrolling,
        scrollState.scrollInProgress,
        scrollState.fastScrolling,
    ) {
        when {
            shouldAllowNativePresentationImmediately(
                transientCache = transientCache,
                alreadyRendered = alreadyRendered,
                cachedModelAvailable = cachedModelAvailable,
                deferForScroll = deferForScroll,
            ) -> allowed = true
            !deferForScroll -> allowed = true
            scrollState.scrollInProgress || scrollState.fastScrolling -> {
                if (!scrollDeferralLogged) {
                    scrollDeferralLogged = true
                    RichHtmlRenderTelemetry.recordFallback(
                        stage = RichHtmlFallbackStage.Render,
                        reason = "NativePresentationDeferred:scroll",
                    )
                }
                delay(RICH_HTML_NATIVE_PRESENTATION_MAX_SCROLL_DEFER_MS)
                if (!scrollState.fastScrolling) {
                    RichHtmlRenderTelemetry.recordFallback(
                        stage = RichHtmlFallbackStage.Render,
                        reason = "NativePresentationDeferred:max-wait-release",
                    )
                    allowed = true
                }
            }
            else -> {
                delay(RICH_HTML_NATIVE_PRESENTATION_IDLE_DELAY_MS)
                scrollDeferralLogged = false
                allowed = true
            }
        }
    }
    return allowed
}

internal fun shouldAllowNativePresentationImmediately(
    transientCache: Boolean,
    alreadyRendered: Boolean,
    cachedModelAvailable: Boolean,
    deferForScroll: Boolean,
): Boolean = transientCache || alreadyRendered || cachedModelAvailable || !deferForScroll

internal fun shouldRetryNativeAdmission(
    decision: RichRenderDecision,
    scrollState: RichRenderScrollState,
    cellIndex: Int?,
): Boolean {
    if (decision.route != RichRenderDecisionRoute.NativeDeferred) return false
    if (scrollState.scrollInProgress || scrollState.fastScrolling) return false
    if (cellIndex != null &&
        scrollState.visibleCellRange.isNotEmptyRange() &&
        cellIndex !in scrollState.visibleCellRange
    ) {
        return false
    }
    return decision.reason == "NativeAdmission:queue-full" ||
        decision.reason == "NativeAdmission:scroll-queue-full"
}

private fun IntRange.isNotEmptyRange(): Boolean = first <= last

internal fun shouldDeferNativePresentationDuringScroll(model: RichHtmlRenderModel): Boolean {
    if (model.animationStats.nativeAnimatedCount > 0 || model.animationStats.layoutAnimationCount > 0) return true
    val staticVisualHintsIsolated = model.snapshotIslandStats.appliedCount > 0 &&
        model.snapshotIslandStats.wholeSnapshotAvoided
    val hintCost = model.visualHints.sumOf { hint ->
        when (hint) {
            RichVisualHint.CssFilter,
            RichVisualHint.CssBackdropFilter,
            RichVisualHint.CssMask,
            RichVisualHint.CssMixBlendMode,
            RichVisualHint.SvgFilter,
            RichVisualHint.SvgMask,
            RichVisualHint.SvgClipPath,
            RichVisualHint.SvgPattern,
            RichVisualHint.SvgSymbol,
            RichVisualHint.SvgUse,
            RichVisualHint.SvgForeignObject -> if (staticVisualHintsIsolated) 0 else 10

            RichVisualHint.CssAnimation,
            RichVisualHint.CssTransition,
            RichVisualHint.CssKeyframes,
            RichVisualHint.CssInfiniteAnimation,
            RichVisualHint.CssLayoutAnimation -> 8

            else -> 2
        }
    }
    val blockCost = model.blocks.sumOf(::nativePresentationCost)
    return blockCost + hintCost >= RICH_HTML_NATIVE_PRESENTATION_DEFER_COST
}

private fun nativePresentationCost(block: RichBlock): Int {
    return when (block) {
        is RichTextBlock -> 1 + block.inlinePaints.size + block.inlineMath.size * 3 +
            block.inlineBoxes.sumOf { 3 + nativePresentationCost(it.block) }

        is RichTextFlowBlock -> 1 + block.paragraphs.sumOf { paragraph ->
            paragraph.inlinePaints.size + paragraph.inlineMath.size * 3
        }

        is RichContainerBlock -> 2 + block.children.sumOf(::nativePresentationCost)
        is RichImageBlock -> 4
        is RichTableBlock -> 10 + block.rows.sumOf { row -> row.size } + block.headers.size
        is RichSvgBlock -> 18
        is RichSnapshotIslandBlock -> 3
        is RichMathBlock -> 6
        is RichButtonBlock -> 3 + block.inlinePaints.size + block.inlineMath.size * 3 +
            block.inlineBoxes.sumOf { 3 + nativePresentationCost(it.block) } +
            block.children.sumOf(::nativePresentationCost)

        is RichDetailsBlock -> 4 + block.children.sumOf(::nativePresentationCost)
        is RichUnsupportedBlock -> 1
    }
}

private data class RichPlaceholderHeight(
    val heightPx: Int,
    val source: String,
) {
    companion object {
        fun from(
            cachedHeightEntry: RichRenderHeightCacheEntry?,
            estimatedHeightPx: Int,
        ): RichPlaceholderHeight {
            if (cachedHeightEntry != null) {
                return RichPlaceholderHeight(
                    heightPx = cachedHeightEntry.heightPx,
                    source = "Cache:${cachedHeightEntry.confidence.name}",
                )
            }
            return RichPlaceholderHeight(
                heightPx = estimatedHeightPx.coerceAtLeast(1),
                source = "Estimate",
            )
        }
    }
}

@Composable
private fun PreparingRichHtmlPlaceholder(
    previewText: String,
    placeholderHeightPx: Int,
    placeholderSource: String,
) {
    val minHeight = richHtmlPlaceholderMinHeight(
        placeholderHeightPx = placeholderHeightPx,
        placeholderSource = placeholderSource,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
    ) {
        Text(
            text = previewText.ifBlank { "正在准备富内容..." },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LightweightRichHtmlPlaceholder(
    previewText: String,
    reason: String,
    placeholderHeightPx: Int,
    placeholderSource: String,
) {
    val minHeight = richHtmlPlaceholderMinHeight(
        placeholderHeightPx = placeholderHeightPx,
        placeholderSource = placeholderSource,
    )
    LaunchedEffect(reason) {
        RichHtmlRenderTelemetry.recordFallback(
            stage = RichHtmlFallbackStage.Render,
            reason = "Lightweight:$reason",
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Text(
            text = previewText.ifBlank { "富内容将在滚动稳定后渲染。" },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun richHtmlPlaceholderMinHeight(
    placeholderHeightPx: Int,
    placeholderSource: String,
): Dp {
    val density = LocalDensity.current
    val minHeight = with(density) { placeholderHeightPx.toDp() }
    val maxHeight = if (placeholderSource.startsWith("Cache:")) 2_400.dp else 2_200.dp
    return minHeight.coerceIn(120.dp, maxHeight)
}

private fun estimateRichHtmlPlaceholderHeightDp(
    html: String,
    analysis: RichHtmlAnalysis,
): Int {
    val metrics = inspectRichRenderEstimatorMetrics(html)
    val visibleTextLength = estimateVisibleTextLength(html, analysis.previewText)
    val textCost = (visibleTextLength / 16).coerceIn(2, 84) * 18
    val nodeCost = metrics.sourceNodeCount * 9
    val layoutCost = metrics.layoutStyleCount * 24
    val tableCost = metrics.tableCellCount * 28
    val mediaCost = metrics.imageCount * 180 +
        metrics.svgCommandCount.coerceAtMost(120) * 2 +
        metrics.detailsCount * 80
    val interactiveCost = metrics.buttonCount * 56 + metrics.interactiveActionCount * 36
    val longHtmlCost = ((analysis.htmlLength - 2_400).coerceAtLeast(0) / 8).coerceAtMost(980)
    val structuralCost = metrics.estimatedRenderBlockCount().coerceAtMost(180) * 8
    val confidenceCost = when (analysis.nativeConfidence) {
        NativeConfidence.High -> 0
        NativeConfidence.Medium -> 80
        NativeConfidence.WebViewFallback,
        NativeConfidence.DynamicPreview -> 160
    }
    val kindCost = when (analysis.kind) {
        RichHtmlRenderKind.NativeStatic -> 72
        RichHtmlRenderKind.InteractiveStatic -> 128
        RichHtmlRenderKind.ComplexDynamic -> 160
    }
    return (
        kindCost +
            textCost +
            nodeCost +
            layoutCost +
            tableCost +
            mediaCost +
            interactiveCost +
            longHtmlCost +
            structuralCost +
            confidenceCost
        ).coerceIn(120, 2_400)
}

private fun estimateVisibleTextLength(
    html: String,
    previewText: String,
): Int {
    val previewLength = previewText.length
    if (html.length < 4_000) return previewLength
    val strippedLength = HTML_PLACEHOLDER_TAG.replace(html, " ")
        .replace(HTML_PLACEHOLDER_ENTITY, " ")
        .count { !it.isWhitespace() }
    return strippedLength.coerceAtLeast(previewLength)
}

private val HTML_PLACEHOLDER_TAG = Regex("""<[^>]+>""")
private val HTML_PLACEHOLDER_ENTITY = Regex("""&[#a-zA-Z0-9]+;""")

@Composable
private fun GuardedRichHtmlRender(
    modifier: Modifier,
    fallbackMinHeightPx: Int,
    fallback: @Composable () -> Unit,
    telemetryId: String,
    viewportWidthDp: Float,
    content: @Composable () -> Unit,
) {
    var deferredFallback by remember(telemetryId, viewportWidthDp) { mutableStateOf(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val fallbackScheduled = remember(telemetryId, viewportWidthDp) { AtomicBoolean(false) }
    val telemetryGate = remember(telemetryId, viewportWidthDp) { NativeRenderMeasureTelemetryGate() }
    fun deferFallback(reason: String) {
        if (!fallbackScheduled.compareAndSet(false, true)) return
        RichHtmlRenderCircuitBreaker.recordFailure(
            key = telemetryId,
            reason = reason,
        )
        mainHandler.postDelayed({
            deferredFallback = true
        }, RICH_HTML_RENDER_FALLBACK_DELAY_MS)
    }
    if (deferredFallback) {
        fallback()
        return
    }
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        val route = "native"
        val placeables = try {
            measurables.map { it.measure(constraints) }
        } catch (throwable: Throwable) {
            if (throwable.isFatalRichHtmlThrowable()) throw throwable
            RichHtmlRenderTelemetry.recordRenderThrowable(
                id = telemetryId,
                stage = RichHtmlFallbackStage.Render,
                phase = "measure",
                throwable = throwable,
            )
            RichHtmlRenderCircuitBreaker.recordFailure(
                key = telemetryId,
                reason = throwable::class.simpleName ?: "RenderFailure",
            )
            throw throwable
        }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.sumOf { it.height }.coerceAtLeast(constraints.minHeight)
        val layoutWidth = width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = height.coerceIn(constraints.minHeight, constraints.maxHeight)
        if (telemetryGate.shouldRecord(route, layoutWidth, layoutHeight)) {
            RichHtmlRenderTelemetry.recordRenderParity(
                id = telemetryId,
                viewportWidthDp = viewportWidthDp,
                renderWidthPx = layoutWidth,
                renderHeightPx = layoutHeight,
                route = route,
            )
        }
        layout(layoutWidth, layoutHeight) {
            var y = 0
            try {
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height
                }
            } catch (throwable: Throwable) {
                if (throwable.isFatalRichHtmlThrowable()) throw throwable
                RichHtmlRenderTelemetry.recordRenderThrowable(
                    id = telemetryId,
                    stage = RichHtmlFallbackStage.Render,
                    phase = "place",
                    throwable = throwable,
                )
                RichHtmlRenderCircuitBreaker.recordFailure(
                    key = telemetryId,
                    reason = throwable::class.simpleName ?: "PlacementFailure",
                )
                throw throwable
            }
        }
    }
}

private class NativeRenderMeasureTelemetryGate {
    private var lastRoute: String? = null
    private var lastWidthPx: Int = -1
    private var lastHeightPx: Int = -1
    private var lastRecordAtMs: Long = 0L

    fun shouldRecord(route: String, widthPx: Int, heightPx: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val changed = route != lastRoute || widthPx != lastWidthPx || heightPx != lastHeightPx
        if (!changed && now - lastRecordAtMs < RICH_HTML_RENDER_MEASURE_TELEMETRY_INTERVAL_MS) {
            return false
        }
        lastRoute = route
        lastWidthPx = widthPx
        lastHeightPx = heightPx
        lastRecordAtMs = now
        return true
    }
}

@Composable
internal fun rememberRichHtmlRenderModel(
    html: String,
    viewportWidthDp: Float = 360f,
    transientCache: Boolean = false,
    onFailure: (Throwable) -> Unit = {},
): RichHtmlRenderModel? {
    val options = remember(viewportWidthDp) {
        RichHtmlCompileOptions(viewportWidthDp = viewportWidthDp)
    }
    val cacheMode = if (transientCache) {
        RichHtmlCompileCacheMode.Transient
    } else {
        RichHtmlCompileCacheMode.Persistent
    }
    var model by remember(html, viewportWidthDp, cacheMode) {
        mutableStateOf(RichHtmlCompiler.getCached(html, options, cacheMode))
    }
    LaunchedEffect(html, viewportWidthDp, cacheMode) {
        RichHtmlCompiler.getCached(html, options, cacheMode)?.let {
            model = it
            return@LaunchedEffect
        }
        if (!transientCache) {
            model = null
        }
        val telemetryId = renderTextCacheKey(html)
        RichHtmlRenderTelemetry.recordCompileStart(
            id = telemetryId,
            viewportWidthDp = viewportWidthDp,
            length = html.length,
        )
        runCatching {
            RichHtmlCompiler.compileAsync(html, options, cacheMode)
        }.onSuccess {
            model = it
        }.onFailure { throwable ->
            if (throwable is CancellationException && throwable !is TimeoutCancellationException) throw throwable
            if (throwable.isFatalRichHtmlThrowable()) throw throwable
            RichHtmlRenderTelemetry.recordCompileFailure(
                id = telemetryId,
                viewportWidthDp = viewportWidthDp,
                throwable = throwable,
            )
            if (!transientCache || model == null) {
                onFailure(throwable)
            }
        }
    }
    return model
}

internal fun validateRichHtmlBubbleBlock(html: String) {
    RichHtmlCompiler.validate(html)
}

private fun RichHtmlSafetyReason?.isDangerousForSnapshot(): Boolean {
    return this in setOf(
        RichHtmlSafetyReason.DangerousTag,
        RichHtmlSafetyReason.DangerousAttribute,
        RichHtmlSafetyReason.UnsafeUrl,
        RichHtmlSafetyReason.ParseFailure,
    )
}

internal fun richInitialSnapshotDecision(
    analysis: RichHtmlAnalysis,
    cachedModel: RichHtmlRenderModel?,
): RichHtmlSnapshotDecision {
    return cachedModel?.let { RichHtmlSnapshotPolicy.afterCompile(analysis, it) }
        ?: RichHtmlSnapshotPolicy.beforeCompile(analysis)
}

private fun RichRenderPlan.withInitialSnapshotDecision(
    decision: RichHtmlSnapshotDecision,
): RichRenderPlan = when (decision.route) {
    RichHtmlSnapshotRoute.Native -> this
    RichHtmlSnapshotRoute.Snapshot -> if (route == RichRenderPlanRoute.Snapshot) {
        this
    } else {
        withRoute(
            route = RichRenderPlanRoute.Snapshot,
            reason = decision.reason.toInitialSnapshotReason(decision.route),
        )
    }

    RichHtmlSnapshotRoute.DynamicPreview -> if (route == RichRenderPlanRoute.DynamicPreview) {
        this
    } else {
        withRoute(
            route = RichRenderPlanRoute.DynamicPreview,
            reason = decision.reason.toInitialSnapshotReason(decision.route),
        )
    }
}

private fun String.toInitialSnapshotReason(route: RichHtmlSnapshotRoute): String {
    return if (isBlank()) {
        "AfterCompile:${route.name}"
    } else {
        "AfterCompile:$this"
    }
}

private fun RichHtmlSnapshotRoute.toRichRenderPlanRoute(): RichRenderPlanRoute = when (this) {
    RichHtmlSnapshotRoute.Native -> RichRenderPlanRoute.Native
    RichHtmlSnapshotRoute.Snapshot -> RichRenderPlanRoute.Snapshot
    RichHtmlSnapshotRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview
}

private fun Throwable.isFatalRichHtmlThrowable(): Boolean {
    return this is VirtualMachineError || this is ThreadDeath || this is LinkageError
}

private const val RICH_HTML_NATIVE_PRESENTATION_IDLE_DELAY_MS = 48L
private const val RICH_HTML_NATIVE_PRESENTATION_MAX_SCROLL_DEFER_MS = 640L
private const val RICH_HTML_NATIVE_ADMISSION_RETRY_DELAY_MS = 220L
private const val RICH_HTML_NATIVE_PRESENTATION_DEFER_COST = 90
private const val RICH_HTML_RENDER_FALLBACK_DELAY_MS = 48L
private const val RICH_HTML_RENDER_MEASURE_TELEMETRY_INTERVAL_MS = 750L
