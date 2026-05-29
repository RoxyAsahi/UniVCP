package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    enforceRichMediaSafety: Boolean = false,
    richMediaKind: RichMediaKind = RichMediaKind.Image,
    zoomEnabled: Boolean = true,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val settings = LocalSettings.current
    val resolvedModel = remember(model, settings) {
        RichVcpMediaResolver.resolve(model, settings)
    }
    val placeholder = if(LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val richMediaRequest = remember(resolvedModel, richMediaKind) {
        RichMediaRequest.fromSource(resolvedModel, kind = richMediaKind)
    }
    val coilModel = if (enforceRichMediaSafety) {
        RichMediaLoader.imageRequest(
            context = context,
            request = richMediaRequest,
            placeholder = placeholder,
            allowHardware = !export,
        )
    } else {
        ImageRequest.Builder(context)
            .data(resolvedModel)
            .placeholder(placeholder)
            .crossfade(false)
            .allowHardware(!export)
            .build()
    }
    val canOpenPreview = zoomEnabled && resolvedModel != null && (!enforceRichMediaSafety || coilModel != null)
    var loading by remember { mutableStateOf(false) }
    AsyncImage(
        model = coilModel,
        contentDescription = contentDescription,
        modifier = modifier
            .shimmer(isLoading = loading)
            .then(
                if (canOpenPreview) {
                    Modifier.clickable { showImageViewer = true }
                } else {
                    Modifier
                }
            ),
        contentScale = contentScale,
        alpha = alpha,
        alignment = alignment,
        onLoading = {
            loading = true
        },
        onSuccess = {
            loading = false
        },
        onError = {
            loading = false
        },
    )
    if (showImageViewer && resolvedModel != null) {
        ImagePreviewDialog(images = listOf(resolvedModel)) {
            showImageViewer = false
        }
    }
}
