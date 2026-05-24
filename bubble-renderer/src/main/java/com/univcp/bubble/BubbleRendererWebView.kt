package com.univcp.bubble

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val RENDERER_BASE_URL = "file:///android_asset/renderer/"

internal val bubblePayloadJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun Context.loadBubbleRendererShellHtml(): String {
    return assets.open("renderer/renderer-shell.html").bufferedReader().use { it.readText() }
}

internal fun bubbleRenderScript(encodedPayload: String): String {
    return "window.UniVCPRenderer && window.UniVCPRenderer.renderPayload($encodedPayload);"
}

internal fun BubblePayload.toRendererJson(): String = bubblePayloadJson.encodeToString(this)

internal fun WebView.configureBubbleRendererWebView(
    remoteShellUrl: String? = null,
    blockNetworkLoads: Boolean = remoteShellUrl == null,
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = false
    settings.databaseEnabled = false
    settings.allowContentAccess = false
    settings.allowFileAccess = true
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.blockNetworkLoads = blockNetworkLoads
    settings.cacheMode = if (remoteShellUrl != null) {
        WebSettings.LOAD_NO_CACHE
    } else {
        WebSettings.LOAD_DEFAULT
    }
    settings.mediaPlaybackRequiresUserGesture = true
}
