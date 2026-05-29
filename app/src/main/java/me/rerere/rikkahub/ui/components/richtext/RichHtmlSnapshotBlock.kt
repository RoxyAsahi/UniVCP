package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.univcp.bubble.BubblePayload
import com.univcp.bubble.BubbleRenderMode
import com.univcp.bubble.BubbleSnapshotRenderer
import com.univcp.bubble.BubbleSnapshotRequest
import com.univcp.bubble.BubbleSnapshotResult
import com.univcp.bubble.BubbleTheme
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry

@Composable
internal fun RichHtmlSnapshotBlock(
    html: String,
    previewText: String,
    reason: String,
    modifier: Modifier = Modifier,
    placeholderHeightPx: Int? = null,
    heightCacheKey: RichHtmlHeightKey? = null,
    onOpenPreview: (() -> Unit)? = null,
    fallback: @Composable () -> Unit,
) {
    val hostView = LocalView.current
    val density = LocalDensity.current
    val prefetchPx = remember(density.density) {
        with(density) { 720.dp.roundToPx() }
    }
    var shouldRenderSnapshot by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val viewportWidth = hostView.width.takeIf { it > 0 } ?: Int.MAX_VALUE
                val viewportHeight = hostView.height.takeIf { it > 0 } ?: Int.MAX_VALUE
                val nextShouldRenderSnapshot = bounds.right >= -prefetchPx &&
                    bounds.left <= viewportWidth + prefetchPx &&
                    bounds.bottom >= -prefetchPx &&
                    bounds.top <= viewportHeight + prefetchPx
                if (nextShouldRenderSnapshot != shouldRenderSnapshot) {
                    hostView.post {
                        shouldRenderSnapshot = nextShouldRenderSnapshot
                    }
                }
            },
    ) {
        val context = LocalContext.current
        val colorScheme = MaterialTheme.colorScheme
        val dark = isSystemInDarkTheme()
        val widthPx = remember(maxWidth, density.density) {
            val width = if (maxWidth != Dp.Infinity && maxWidth > 0.dp) maxWidth else 360.dp
            with(density) { width.roundToPx() }.coerceAtLeast(1)
        }
        val theme = remember(colorScheme, dark) {
            BubbleTheme(
                dark = dark,
                background = colorScheme.background.toCssHex(),
                onBackground = colorScheme.onBackground.toCssHex(),
                surface = colorScheme.surfaceVariant.toCssHex(),
                onSurface = colorScheme.onSurface.toCssHex(),
                primary = colorScheme.primary.toCssHex(),
                outline = colorScheme.outlineVariant.toCssHex(),
            )
        }
        val themeSignature = remember(theme) {
            listOf(
                theme.dark,
                theme.background,
                theme.onBackground,
                theme.surface,
                theme.onSurface,
                theme.primary,
                theme.outline,
            ).joinToString("|")
        }
        val cacheKey = remember(html, widthPx, density.density, density.fontScale, dark, themeSignature) {
            RichHtmlSnapshotCacheKey.create(
                html = html,
                widthPx = widthPx,
                density = density.density,
                fontScale = density.fontScale,
                dark = dark,
                themeSignature = themeSignature,
            )
        }
        var entry by remember(cacheKey) { mutableStateOf(RichHtmlSnapshotCache.get(cacheKey)) }
        var failed by remember(cacheKey) { mutableStateOf(false) }
        val telemetryId = remember(cacheKey) { cacheKey.htmlKey }
        val nativeHeightEstimatePx = remember(html, widthPx, density.density, previewText) {
            estimateNativeHeightPx(html = html, widthPx = widthPx, density = density.density, previewText = previewText)
        }
        val heightWarningThresholdPct = remember(reason) {
            if (reason.startsWith("VisualHint:") ||
                reason.startsWith("Unsupported:") ||
                reason == "NativeConfidenceWebViewFallback"
            ) {
                20f
            } else {
                15f
            }
        }

        LaunchedEffect(shouldRenderSnapshot) {
            if (shouldRenderSnapshot) {
                runCatching { BubbleSnapshotRenderer.warmUp(context) }
            }
        }

        LaunchedEffect(cacheKey, shouldRenderSnapshot) {
            RichHtmlSnapshotCache.get(cacheKey)?.let {
                entry = it
                heightCacheKey?.let { key ->
                    RichHtmlHeightCache.put(
                        key = key,
                        heightPx = it.heightPx,
                        confidence = RichRenderHeightConfidence.MeasuredSnapshot,
                    )
                }
                RichHtmlRenderTelemetry.recordSnapshotSuccess(
                    id = telemetryId,
                    widthPx = it.widthPx,
                    heightPx = it.heightPx,
                    renderTimeMs = it.renderTimeMs,
                    cacheHit = true,
                    reason = reason,
                    nativeEstimateHeightPx = nativeHeightEstimatePx,
                    heightWarningThresholdPct = heightWarningThresholdPct,
                )
                return@LaunchedEffect
            }
            if (!shouldRenderSnapshot) {
                return@LaunchedEffect
            }
            val joinedInFlight = RichHtmlSnapshotCache.isInFlight(cacheKey)
            RichHtmlRenderTelemetry.recordSnapshotStart(
                id = telemetryId,
                widthPx = widthPx,
                reason = reason,
                joinedInFlight = joinedInFlight,
            )
            val payload = BubblePayload(
                id = "snapshot-${telemetryId.replace(':', '-')}",
                rawContent = html,
                renderMode = BubbleRenderMode.RICH_HTML,
                theme = theme,
                isStreaming = false,
                allowScript = false,
            )
            val next = runCatching {
                RichHtmlSnapshotCache.getOrRenderResult(cacheKey) {
                    when (val result = BubbleSnapshotRenderer.render(context, BubbleSnapshotRequest(payload, widthPx))) {
                        is BubbleSnapshotResult.Success -> {
                            RichHtmlSnapshotEntry(
                                bitmap = result.bitmap,
                                widthPx = result.widthPx,
                                heightPx = result.heightPx,
                                renderTimeMs = result.renderTimeMs,
                                queueWaitMs = result.queueWaitMs,
                                sessionReused = result.sessionReused,
                            )
                        }

                        is BubbleSnapshotResult.Failure -> {
                            RichHtmlRenderTelemetry.recordSnapshotFailure(
                                id = telemetryId,
                                widthPx = widthPx,
                                reason = result.reason.name,
                                message = result.message,
                                queueWaitMs = result.queueWaitMs,
                            )
                            null
                        }
                    }
                }
            }.getOrElse { throwable ->
                RichHtmlRenderTelemetry.recordSnapshotFailure(
                    id = telemetryId,
                    widthPx = widthPx,
                    reason = throwable::class.simpleName ?: "SnapshotFailure",
                    message = throwable.message,
                )
                null
            }
            val nextEntry = next?.entry
            if (nextEntry == null) {
                failed = true
            } else {
                entry = nextEntry
                heightCacheKey?.let { key ->
                    RichHtmlHeightCache.put(
                        key = key,
                        heightPx = nextEntry.heightPx,
                        confidence = RichRenderHeightConfidence.MeasuredSnapshot,
                    )
                }
                RichHtmlRenderTelemetry.recordSnapshotSuccess(
                    id = telemetryId,
                    widthPx = nextEntry.widthPx,
                    heightPx = nextEntry.heightPx,
                    renderTimeMs = nextEntry.renderTimeMs,
                    cacheHit = next.cacheHit,
                    reason = reason,
                    queueWaitMs = nextEntry.queueWaitMs,
                    joinedInFlight = next.joinedInFlight,
                    nativeEstimateHeightPx = nativeHeightEstimatePx,
                    heightWarningThresholdPct = heightWarningThresholdPct,
                )
            }
        }

        when {
            failed -> fallback()
            entry == null -> SnapshotLoadingPreview(
                previewText = previewText,
                placeholderHeightPx = placeholderHeightPx,
                onOpenPreview = onOpenPreview,
            )
            else -> SnapshotImage(entry = entry!!, onOpenPreview = onOpenPreview)
        }
    }
}

@Composable
private fun SnapshotImage(
    entry: RichHtmlSnapshotEntry,
    onOpenPreview: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val height = remember(entry.heightPx, density.density) {
        with(density) { entry.heightPx.toDp() }
    }
    Image(
        bitmap = entry.bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .testTag("rich-html-snapshot-image")
            .then(if (onOpenPreview != null) Modifier.clickable(onClick = onOpenPreview) else Modifier),
    )
}

@Composable
private fun SnapshotLoadingPreview(
    previewText: String,
    placeholderHeightPx: Int?,
    onOpenPreview: (() -> Unit)?,
) {
    val density = LocalDensity.current
    val minHeight = remember(placeholderHeightPx, density.density) {
        placeholderHeightPx
            ?.takeIf { it > 0 }
            ?.let { with(density) { it.toDp() } }
            ?.coerceIn(144.dp, 1_200.dp)
            ?: 144.dp
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .testTag("rich-html-snapshot-loading")
            .then(if (onOpenPreview != null) Modifier.clickable(onClick = onOpenPreview) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "正在生成静态预览",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = previewText.ifBlank { "这段富内容会用离屏 WebView 生成静态快照，聊天列表不会常驻运行 WebView。" },
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .height(22.dp),
                strokeWidth = 2.dp,
            )
            if (onOpenPreview != null) {
                TextButton(
                    modifier = Modifier.padding(top = 2.dp),
                    onClick = onOpenPreview,
                ) {
                    Text("打开动态预览")
                }
            }
        }
    }
}

private fun Color.toCssHex(): String {
    val color = toArgb()
    return "#%02X%02X%02X".format(
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color),
    )
}

private fun estimateNativeHeightPx(
    html: String,
    widthPx: Int,
    density: Float,
    previewText: String,
): Int {
    HEIGHT_STYLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { explicit ->
        if (explicit in 80..6_000) return explicit
    }
    val textLength = previewText.ifBlank {
        html.replace(TAG_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    }.length
    val pxPerCharacter = (8f * density).coerceAtLeast(8f)
    val charsPerLine = (widthPx / pxPerCharacter).toInt().coerceAtLeast(12)
    val lineCount = ((textLength + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
    val tagWeight = TAG_REGEX.findAll(html).count().coerceAtMost(80)
    val lineHeightPx = (22f * density).toInt().coerceAtLeast(22)
    val basePx = (72f * density).toInt().coerceAtLeast(72)
    return (basePx + lineCount * lineHeightPx + tagWeight * 3).coerceIn(80, 6_000)
}

private val HEIGHT_STYLE_REGEX = Regex("""(?:^|[;\s])(?:min-)?height\s*:\s*(\d{2,4})px\b""", RegexOption.IGNORE_CASE)
private val TAG_REGEX = Regex("""<[^>]+>""")
private val WHITESPACE_REGEX = Regex("""\s+""")
