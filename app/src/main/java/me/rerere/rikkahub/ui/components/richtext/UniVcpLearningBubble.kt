package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.univcp.bubble.BubblePayload
import com.univcp.bubble.BubbleRenderMode
import com.univcp.bubble.BubbleTheme
import com.univcp.bubble.BubbleWebView
import me.rerere.rikkahub.BuildConfig

private const val DEBUG_RENDERER_SHELL_URL = "http://127.0.0.1:5179/renderer-shell.html"

@Composable
fun UniVcpLearningBubble(
    content: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    onSendInput: (String) -> Unit = {},
) {
    val colorScheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val renderMode = remember(content) { detectRenderMode(content) }
    val payloadId = remember {
        "rikkahub-${System.nanoTime().toString(16)}"
    }

    BubbleWebView(
        payload = BubblePayload(
            id = payloadId,
            rawContent = content,
            renderMode = renderMode,
            theme = BubbleTheme(
                dark = dark,
                background = colorScheme.background.toCssHex(),
                onBackground = colorScheme.onBackground.toCssHex(),
                surface = colorScheme.surfaceVariant.toCssHex(),
                onSurface = colorScheme.onSurface.toCssHex(),
                primary = colorScheme.primary.toCssHex(),
                outline = colorScheme.outlineVariant.toCssHex(),
            ),
            isStreaming = isStreaming,
            allowScript = false,
        ),
        rendererShellUrl = if (BuildConfig.DEBUG) DEBUG_RENDERER_SHELL_URL else null,
        modifier = modifier.fillMaxWidth(),
        onSendInput = onSendInput,
    )
}

private fun detectRenderMode(content: String): BubbleRenderMode {
    val trimmed = content.trim()
    return if (responseRootRegex.containsMatchIn(trimmed) || (trimmed.startsWith("<") && htmlTagRegex.containsMatchIn(trimmed))) {
        BubbleRenderMode.RICH_HTML
    } else {
        BubbleRenderMode.MARKDOWN
    }
}

private val responseRootRegex = Regex("""<div\b[^>]*\bid\s*=\s*["']response-root["']""", RegexOption.IGNORE_CASE)
private val htmlTagRegex = Regex("<[A-Za-z][\\s\\S]*>")

private fun Color.toCssHex(): String {
    val color = toArgb()
    return "#%02X%02X%02X".format(
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color),
    )
}
