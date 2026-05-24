package me.rerere.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import me.rerere.rikkahub.ui.components.message.RichHtmlFallbackStage
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderKind
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotPolicy
import me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotRoute
import me.rerere.rikkahub.ui.components.message.RichHtmlSafetyReason
import me.rerere.rikkahub.ui.components.message.RenderRiskScore
import me.rerere.rikkahub.ui.components.message.RichContentRoute
import me.rerere.rikkahub.ui.components.message.analyzeRichHtml
import me.rerere.rikkahub.ui.components.message.inspectRichHtmlSafety
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
        val density = LocalDensity.current
        val heightKey = remember(renderId, viewportWidthDp, density.fontScale, heightContentType) {
            RichHtmlHeightCache.key(renderId, viewportWidthDp, density.fontScale, heightContentType)
        }
        val cachedHeightPx = remember(heightKey) { RichHtmlHeightCache.get(heightKey) }
        LaunchedEffect(renderId, heightContentType, cachedHeightPx) {
            RichHtmlRenderTelemetry.recordHeightCache(
                id = renderId,
                contentType = heightContentType,
                hit = cachedHeightPx != null,
                heightPx = cachedHeightPx,
            )
        }
        val scrollState = LocalRichRenderScrollState.current
        val mainHandler = remember { Handler(Looper.getMainLooper()) }
        val renderAdmission = remember(renderId, analysis, scrollState, transientCache, renderCellIndex, renderRisk) {
            if (transientCache) {
                RichHtmlRenderAdmission(nativeAllowed = true, reason = "transient")
            } else {
                RichHtmlRenderScheduler.admission(
                    key = renderId,
                    analysis = analysis,
                    scrollState = scrollState,
                    cellIndex = renderCellIndex,
                    risk = renderRisk,
                )
            }
        }
        DisposableEffect(renderId, renderAdmission.nativeAllowed, transientCache) {
            onDispose {
                if (renderAdmission.nativeAllowed && !transientCache) {
                    RichHtmlRenderScheduler.release(renderId)
                }
            }
        }
        val measuredRootModifier = rootModifier.onSizeChanged { size ->
                if (!transientCache && size.height > 0) {
                    val measuredHeightPx = size.height
                    mainHandler.post {
                        RichHtmlHeightCache.put(heightKey, measuredHeightPx)
                        RichHtmlRenderScheduler.markRendered(renderId)
                        RichHtmlRenderCircuitBreaker.recordSuccess(renderId)
                    }
                }
            }
        val snapshotAllowed = enableSnapshot && !transientCache
        val beforeCompileDecision = remember(analysis) {
            RichHtmlSnapshotPolicy.beforeCompile(analysis)
        }

        @Composable
        fun SnapshotOrFallback(reason: String) {
            val forcedSnapshot = renderRisk?.route == RichContentRoute.Snapshot
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
                    onOpenPreview = onOpenPreview,
                    fallback = fallbackContent,
                )
            }
        }

        when {
            !renderAdmission.nativeAllowed &&
                (renderAdmission.reason == "circuit-breaker" ||
                    renderAdmission.reason == "risk-route:${RichContentRoute.Snapshot.name}") -> {
                SnapshotOrFallback(
                    reason = renderAdmission.reason +
                        RichHtmlRenderCircuitBreaker.failureReason(renderId)?.let { ":$it" }.orEmpty()
                )
            }

            !renderAdmission.nativeAllowed -> {
                LightweightRichHtmlPlaceholder(
                    previewText = analysis.previewText,
                    reason = renderAdmission.reason,
                    cachedHeightPx = cachedHeightPx,
                    estimatedHeightDp = analysis.estimatedHeightDp(),
                )
            }

            beforeCompileDecision.route == RichHtmlSnapshotRoute.DynamicPreview -> fallbackContent()
            snapshotAllowed && beforeCompileDecision.route == RichHtmlSnapshotRoute.Snapshot -> {
                SnapshotOrFallback(beforeCompileDecision.reason)
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
                    failed -> SnapshotOrFallback(RichHtmlSnapshotPolicy.nativeFailure(analysis).reason)
                    model?.unsupported?.contains(RichUnsupportedReason.UnsafeHtml) == true -> fallbackContent()
                    model == null -> {
                        PreparingRichHtmlPlaceholder(
                            previewText = analysis.previewText,
                            cachedHeightPx = cachedHeightPx,
                            estimatedHeightDp = analysis.estimatedHeightDp(),
                        )
                    }

                    else -> {
                        val snapshotDecision = remember(analysis, model) {
                            RichHtmlSnapshotPolicy.afterCompile(analysis, model)
                        }
                        when (snapshotDecision.route) {
                            RichHtmlSnapshotRoute.DynamicPreview -> fallbackContent()
                            RichHtmlSnapshotRoute.Snapshot -> SnapshotOrFallback(snapshotDecision.reason)
                            RichHtmlSnapshotRoute.Native -> {
                                val nativePresentationAllowed = rememberNativePresentationAllowed(
                                    renderId = renderId,
                                    viewportWidthDp = viewportWidthDp,
                                    model = model,
                                    transientCache = transientCache,
                                    scrollState = scrollState,
                                )
                                if (!nativePresentationAllowed) {
                                    PreparingRichHtmlPlaceholder(
                                        previewText = analysis.previewText,
                                        cachedHeightPx = cachedHeightPx,
                                        estimatedHeightDp = analysis.estimatedHeightDp(),
                                    )
                                    return@BoxWithConstraints
                                }
                                val nativeFailureDecision = remember(analysis) {
                                    RichHtmlSnapshotPolicy.nativeFailure(analysis)
                                }
                                GuardedRichHtmlRender(
                                    modifier = measuredRootModifier,
                                    fallbackMinHeightPx = cachedHeightPx ?: with(density) {
                                        analysis.estimatedHeightDp().dp.roundToPx()
                                    },
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
private fun rememberNativePresentationAllowed(
    renderId: String,
    viewportWidthDp: Float,
    model: RichHtmlRenderModel,
    transientCache: Boolean,
    scrollState: RichRenderScrollState,
): Boolean {
    val alreadyRendered = RichHtmlRenderScheduler.hasRendered(renderId)
    var allowed by remember(renderId, viewportWidthDp, transientCache) {
        mutableStateOf(transientCache || alreadyRendered)
    }
    var scrollDeferralLogged by remember(renderId, viewportWidthDp, transientCache) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        renderId,
        viewportWidthDp,
        model.id,
        transientCache,
        alreadyRendered,
        scrollState.scrolling,
        scrollState.scrollInProgress,
        scrollState.fastScrolling,
    ) {
        when {
            transientCache || alreadyRendered -> allowed = true
            scrollState.scrolling || scrollState.scrollInProgress || scrollState.fastScrolling -> {
                if (!scrollDeferralLogged) {
                    scrollDeferralLogged = true
                    RichHtmlRenderTelemetry.recordFallback(
                        stage = RichHtmlFallbackStage.Render,
                        reason = "NativePresentationDeferred:scroll",
                    )
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

@Composable
private fun PreparingRichHtmlPlaceholder(
    previewText: String,
    cachedHeightPx: Int?,
    estimatedHeightDp: Int,
) {
    val minHeight = richHtmlPlaceholderMinHeight(
        cachedHeightPx = cachedHeightPx,
        estimatedHeightDp = estimatedHeightDp,
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
    cachedHeightPx: Int?,
    estimatedHeightDp: Int,
) {
    val minHeight = richHtmlPlaceholderMinHeight(
        cachedHeightPx = cachedHeightPx,
        estimatedHeightDp = estimatedHeightDp,
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
    cachedHeightPx: Int?,
    estimatedHeightDp: Int,
): Dp {
    val density = LocalDensity.current
    val minHeight = cachedHeightPx
        ?.let { with(density) { it.toDp() } }
        ?: estimatedHeightDp.dp
    return minHeight.coerceIn(120.dp, 520.dp)
}

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
    fun deferFallback(reason: String) {
        if (!fallbackScheduled.compareAndSet(false, true)) return
        RichHtmlRenderTelemetry.recordFallback(
            stage = RichHtmlFallbackStage.Render,
            reason = reason,
        )
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
        var route = "native"
        var measureFailed = false
        val placeables = try {
            measurables.map { it.measure(constraints) }
        } catch (throwable: Throwable) {
            if (throwable.isFatalRichHtmlThrowable()) throw throwable
            route = "deferred-fallback"
            measureFailed = true
            deferFallback(throwable::class.simpleName ?: "RenderFailure")
            emptyList()
        }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = if (measureFailed) {
            fallbackMinHeightPx
        } else {
            placeables.sumOf { it.height }
        }.coerceAtLeast(constraints.minHeight)
        val layoutWidth = width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = height.coerceIn(constraints.minHeight, constraints.maxHeight)
        RichHtmlRenderTelemetry.recordRenderParity(
            id = telemetryId,
            viewportWidthDp = viewportWidthDp,
            renderWidthPx = layoutWidth,
            renderHeightPx = layoutHeight,
            route = route,
        )
        layout(layoutWidth, layoutHeight) {
            var y = 0
            try {
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height
                }
            } catch (throwable: Throwable) {
                if (throwable.isFatalRichHtmlThrowable()) throw throwable
                deferFallback(throwable::class.simpleName ?: "PlacementFailure")
            }
        }
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

private fun Throwable.isFatalRichHtmlThrowable(): Boolean {
    return this is VirtualMachineError || this is ThreadDeath || this is LinkageError
}

private const val RICH_HTML_NATIVE_PRESENTATION_IDLE_DELAY_MS = 120L
private const val RICH_HTML_RENDER_FALLBACK_DELAY_MS = 48L
