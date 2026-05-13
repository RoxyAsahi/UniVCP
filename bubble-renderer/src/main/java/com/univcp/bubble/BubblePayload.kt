package com.univcp.bubble

import kotlinx.serialization.Serializable

@Serializable
enum class BubbleRenderMode {
    MARKDOWN,
    RICH_HTML,
    CODE_PREVIEW,
    THREE
}

@Serializable
data class BubbleTheme(
    val dark: Boolean,
    val background: String,
    val onBackground: String,
    val surface: String,
    val onSurface: String,
    val primary: String,
    val outline: String
)

@Serializable
data class BubblePayload(
    val id: String,
    val rawContent: String,
    val renderMode: BubbleRenderMode = BubbleRenderMode.MARKDOWN,
    val language: String = "",
    val theme: BubbleTheme,
    val isStreaming: Boolean = false,
    val allowScript: Boolean = false
)

data class BubbleRenderState(
    val heightPx: Int = 120,
    val status: String = "idle",
    val error: String? = null
)
