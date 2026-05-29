package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

enum class RichMediaKind {
    Image,
    BackgroundImage,
    ListStyleImage,
    AstMedia,
}

internal enum class RichMediaSafety {
    Safe,
    Empty,
    UnsupportedProtocol,
    LocalFileRejected,
    DataUriRejected,
}

internal data class RichMediaRequest(
    val sourceDigest: String,
    val kind: RichMediaKind,
    val sizeBucket: String,
    val themeBucket: String,
    val rendererVersion: Int = RichRenderHeightCache.RendererVersion,
    val rawSource: String? = null,
) {
    val key: String = listOf(sourceDigest, kind.name, sizeBucket, themeBucket, rendererVersion).joinToString(":")

    companion object {
        fun fromSource(
            source: String?,
            kind: RichMediaKind,
            widthPx: Int? = null,
            heightPx: Int? = null,
            themeBucket: String = "default",
        ): RichMediaRequest {
            val normalized = source?.trim().orEmpty()
            val sizeBucket = "${widthPx?.coerceAtLeast(0) ?: 0}x${heightPx?.coerceAtLeast(0) ?: 0}"
            return RichMediaRequest(
                sourceDigest = renderTextCacheKey(normalized),
                kind = kind,
                sizeBucket = sizeBucket,
                themeBucket = themeBucket,
                rawSource = normalized,
            )
        }
    }
}

internal object RichMediaLoader {
    fun safety(request: RichMediaRequest): RichMediaSafety {
        val source = request.rawSource?.trim().orEmpty()
        if (source.isBlank()) return RichMediaSafety.Empty
        val lower = source.lowercase()
        return when {
            lower.startsWith("https://") || lower.startsWith("http://") -> RichMediaSafety.Safe
            lower.startsWith("content://") || lower.startsWith("android.resource://") -> RichMediaSafety.Safe
            lower.startsWith("data:image/") -> RichMediaSafety.Safe
            lower.startsWith("data:") -> RichMediaSafety.DataUriRejected
            lower.startsWith("file://") -> RichMediaSafety.LocalFileRejected
            ":" !in lower.substringBefore("/", missingDelimiterValue = lower) -> RichMediaSafety.Safe
            else -> RichMediaSafety.UnsupportedProtocol
        }
    }

    fun safeData(request: RichMediaRequest): String? {
        val safety = safety(request)
        val oversized = request.isOversized()
        val accepted = safety == RichMediaSafety.Safe && !oversized
        RichHtmlRenderTelemetry.recordMediaRequest(
            id = request.sourceDigest,
            kind = request.kind.name,
            safety = safety.name,
            outcome = if (accepted) "accepted" else "rejected",
            oversizedRejected = oversized,
        )
        return request.rawSource?.takeIf { accepted }
    }

    fun imageRequest(
        context: Context,
        request: RichMediaRequest,
        placeholder: Int,
        allowHardware: Boolean,
    ): ImageRequest? {
        val data = safeData(request) ?: return null
        return ImageRequest.Builder(context)
            .data(data)
            .placeholder(placeholder)
            .crossfade(false)
            .allowHardware(allowHardware)
            .build()
    }

    fun defaultPlaceholder(dark: Boolean): Int = if (dark) R.drawable.placeholder_dark else R.drawable.placeholder

    private fun RichMediaRequest.isOversized(): Boolean {
        val parts = sizeBucket.split("x", limit = 2)
        val width = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val height = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return width > 8_192 || height > 8_192 || (width > 0 && height > 0 && width.toLong() * height > 24_000_000L)
    }
}
