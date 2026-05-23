package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import me.rerere.rikkahub.ui.components.message.RichHtmlFallbackStage
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

@Composable
fun RichHtmlBubbleBlock(
    html: String,
    modifier: Modifier = Modifier,
    onSendInput: (String) -> Unit = {},
    renderFallback: (@Composable () -> Unit)? = null,
    transientCache: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier.testTag("rich-html-bubble")) {
        val rootModifier = Modifier.fillMaxWidth()
        val viewportWidthDp = remember(maxWidth) {
            if (maxWidth != Dp.Infinity && maxWidth > 0.dp) maxWidth.value else 360f
        }
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
            },
        )
        val fallbackContent = renderFallback ?: {
            Text(
                text = "富内容渲染失败，可打开动态预览。",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (failed || model?.unsupported?.contains(RichUnsupportedReason.UnsafeHtml) == true) {
            fallbackContent()
        } else if (model == null) {
            Text(
                text = "正在准备富内容...",
                modifier = rootModifier.heightIn(min = 120.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            GuardedRichHtmlRender(
                modifier = rootModifier,
                fallback = fallbackContent,
                telemetryId = model.id,
                viewportWidthDp = viewportWidthDp,
            ) {
                RichHtmlRenderer(
                    model = model,
                    onSendInput = onSendInput,
                )
            }
        }
    }
}

@Composable
private fun GuardedRichHtmlRender(
    modifier: Modifier,
    fallback: @Composable () -> Unit,
    telemetryId: String,
    viewportWidthDp: Float,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        var route = "native"
        val placeables = try {
            subcompose("content", content).map { it.measure(constraints) }
        } catch (throwable: Throwable) {
            if (throwable.isFatalRichHtmlThrowable()) throw throwable
            RichHtmlRenderTelemetry.recordFallback(
                stage = RichHtmlFallbackStage.Render,
                reason = throwable::class.simpleName ?: "RenderFailure",
            )
            route = "fallback"
            subcompose("fallback", fallback).map { it.measure(constraints) }
        }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.sumOf { it.height }.coerceAtLeast(constraints.minHeight)
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
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
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
        model = null
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
            onFailure(throwable)
        }
    }
    return model
}

internal fun validateRichHtmlBubbleBlock(html: String) {
    RichHtmlCompiler.validate(html)
}

private fun Throwable.isFatalRichHtmlThrowable(): Boolean {
    return this is VirtualMachineError || this is ThreadDeath || this is LinkageError
}
