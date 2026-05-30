package com.univcp.bubble

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.Collections

private const val TAG = "BubbleWebView"
private const val DEFAULT_HEIGHT_PX = 220
private const val HEIGHT_UPDATE_THRESHOLD_PX = 6
private const val FIRST_RENDER_MAX_HEIGHT_UPDATE_MS = 1_500L
private const val HEIGHT_CACHE_MAX_ENTRIES = 256
private const val MAX_ESTIMATED_INITIAL_HEIGHT_PX = 720

private val heightCache: MutableMap<String, Int> = Collections.synchronizedMap(
    object : LinkedHashMap<String, Int>(HEIGHT_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > HEIGHT_CACHE_MAX_ENTRIES
        }
    }
)

private fun estimateInitialHeightPx(payload: BubblePayload): Int {
    val raw = payload.rawContent
    val explicitHeight = HEIGHT_STYLE_REGEX.findAll(raw)
        .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
        .firstOrNull { it in 80..MAX_ESTIMATED_INITIAL_HEIGHT_PX }
    if (explicitHeight != null) {
        return explicitHeight.coerceAtLeast(DEFAULT_HEIGHT_PX)
    }

    val textLength = raw
        .replace(TAG_REGEX, " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .length
    val estimatedByText = DEFAULT_HEIGHT_PX + (textLength / 90) * 28
    return estimatedByText.coerceIn(DEFAULT_HEIGHT_PX, MAX_ESTIMATED_INITIAL_HEIGHT_PX)
}

private val HEIGHT_STYLE_REGEX = Regex("""(?:^|[;\s])(?:min-)?height\s*:\s*(\d{2,4})px\b""", RegexOption.IGNORE_CASE)
private val TAG_REGEX = Regex("""<[^>]+>""")
private val WHITESPACE_REGEX = Regex("""\s+""")

private data class BubbleBridgeHandlers(
    val onHeight: (Int) -> Unit,
    val onStatus: (String) -> Unit,
    val onError: (String) -> Unit,
    val onSendInput: (String) -> Unit
)

private object BubbleBridgeDispatcher {
    private val handlers: MutableMap<String, BubbleBridgeHandlers> = Collections.synchronizedMap(mutableMapOf())

    fun register(id: String, handlers: BubbleBridgeHandlers) {
        this.handlers[id] = handlers
    }

    fun unregister(id: String) {
        handlers.remove(id)
    }

    fun reportHeight(id: String, px: Int) {
        handlers[id]?.onHeight?.invoke(px)
    }

    fun reportStatus(id: String, status: String) {
        handlers[id]?.onStatus?.invoke(status)
    }

    fun reportError(id: String, message: String) {
        handlers[id]?.onError?.invoke(message)
    }

    fun sendInput(id: String, text: String) {
        handlers[id]?.onSendInput?.invoke(text)
    }
}

class BubbleBridge {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportHeight(id: String, px: Int) {
        main.post { BubbleBridgeDispatcher.reportHeight(id, px.coerceAtLeast(80)) }
    }

    @JavascriptInterface
    fun reportStatus(id: String, status: String) {
        main.post { BubbleBridgeDispatcher.reportStatus(id, status) }
    }

    @JavascriptInterface
    fun reportError(id: String, message: String) {
        main.post { BubbleBridgeDispatcher.reportError(id, message) }
    }

    @JavascriptInterface
    fun sendInput(id: String, text: String) {
        main.post { BubbleBridgeDispatcher.sendInput(id, text) }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun BubbleWebView(
    payload: BubblePayload,
    modifier: Modifier = Modifier,
    rendererShellUrl: String? = null,
    @Suppress("UNUSED_PARAMETER")
    deferRender: Boolean = false,
    onStateChanged: (BubbleRenderState) -> Unit = {},
    onSendInput: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val initialHeightPx = remember(payload.id) {
        heightCache[payload.id] ?: estimateInitialHeightPx(payload)
    }
    var renderState by remember(payload.id) { mutableStateOf(BubbleRenderState(heightPx = initialHeightPx)) }
    var heightPx by remember(payload.id) { mutableIntStateOf(initialHeightPx) }
    var lastRenderedPayloadJson by remember(payload.id) { mutableStateOf<String?>(null) }
    var lastRenderStartedAt by remember(payload.id) { mutableStateOf(0L) }
    val encodedPayload = remember(payload) { payload.toRendererJson() }
    val currentOnSendInput = rememberUpdatedState(onSendInput)
    val currentOnStateChanged = rememberUpdatedState(onStateChanged)
    val currentHeightPx = rememberUpdatedState(heightPx)
    val currentRenderState = rememberUpdatedState(renderState)
    val currentLastRenderStartedAt = rememberUpdatedState(lastRenderStartedAt)

    fun updateState(next: BubbleRenderState) {
        renderState = next
        currentOnStateChanged.value(next)
    }

    DisposableEffect(payload.id) {
        BubbleBridgeDispatcher.register(
            id = payload.id,
            handlers = BubbleBridgeHandlers(
                onHeight = { px ->
                    val lastRenderAt = currentLastRenderStartedAt.value
                    val previousHeight = currentHeightPx.value
                    val isFirstRenderSettling = lastRenderAt > 0L &&
                        System.currentTimeMillis() - lastRenderAt < FIRST_RENDER_MAX_HEIGHT_UPDATE_MS
                    val shouldUpdate = px > previousHeight ||
                        isFirstRenderSettling ||
                        kotlin.math.abs(previousHeight - px) >= HEIGHT_UPDATE_THRESHOLD_PX
                    if (shouldUpdate) {
                        heightPx = px
                        heightCache[payload.id] = px
                        updateState(currentRenderState.value.copy(heightPx = px))
                    }
                },
                onStatus = { status ->
                    updateState(currentRenderState.value.copy(status = status))
                },
                onError = { message ->
                    updateState(currentRenderState.value.copy(status = "error", error = message))
                },
                onSendInput = { text ->
                    currentOnSendInput.value(text)
                    updateState(currentRenderState.value.copy(status = "input"))
                }
            )
        )
        onDispose {
            BubbleBridgeDispatcher.unregister(payload.id)
        }
    }

    val bridge = remember {
        BubbleBridge()
    }

    fun render(webView: WebView?, encoded: String = encodedPayload) {
        if (webView == null || !loaded) return
        if (payload.isStreaming && deferRender && lastRenderedPayloadJson != null) return
        if (lastRenderedPayloadJson == encoded) return
        val script = bubbleRenderScript(encoded)
        lastRenderStartedAt = System.currentTimeMillis()
        webView.evaluateJavascript(script) { result ->
            if (result != "null") {
                lastRenderedPayloadJson = encoded
            }
        }
    }

    fun resetRenderer(webView: WebView) {
        webView.stopLoading()
        webView.evaluateJavascript("window.UniVCPRenderer && window.UniVCPRenderer.dispose();", null)
        lastRenderedPayloadJson = null
        progress = 1f
        loaded = true
    }

    val shellHtml = remember {
        context.loadBubbleRendererShellHtml()
    }
    val remoteShellUrl = remember(rendererShellUrl) {
        rendererShellUrl?.takeIf { it.isNotBlank() }
    }

    fun loadBundledShell(webView: WebView) {
        loaded = false
        lastRenderedPayloadJson = null
        webView.settings.blockNetworkLoads = true
        webView.loadDataWithBaseURL(
            RENDERER_BASE_URL,
            shellHtml,
            "text/html",
            "UTF-8",
            null
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = {
                WebView(it).apply {
                    if (remoteShellUrl != null) {
                        WebView.setWebContentsDebuggingEnabled(true)
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    addJavascriptInterface(bridge, "UniVCPAndroid")
                    configureBubbleRendererWebView(remoteShellUrl = remoteShellUrl)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress / 100f
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            val message = "${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                            if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                Log.e(TAG, message)
                            } else {
                                Log.d(TAG, message)
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        private var fellBackToBundled = false

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loaded = true
                            if (!payload.isStreaming) {
                                render(view)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (
                                remoteShellUrl != null &&
                                request?.isForMainFrame == true &&
                                view != null &&
                                !fellBackToBundled
                            ) {
                                fellBackToBundled = true
                                val message = error?.description?.toString() ?: "remote renderer unavailable"
                                Log.w(TAG, "Remote renderer failed, falling back to bundled asset: $message")
                                loadBundledShell(view)
                            }
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            return true
                        }
                    }
                    if (remoteShellUrl != null) {
                        loadUrl(remoteShellUrl)
                    } else {
                        loadBundledShell(this)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(heightPx.dp),
            onReset = { webView ->
                resetRenderer(webView)
            },
            update = { webView ->
                webViewRef = webView
                if (!payload.isStreaming) {
                    render(webView)
                }
            },
            onRelease = { webView ->
                webView.removeJavascriptInterface("UniVCPAndroid")
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
                if (webViewRef === webView) {
                    webViewRef = null
                }
            }
        )

        if (!loaded || progress in 0.01f..0.99f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    LaunchedEffect(encodedPayload, loaded, deferRender) {
        if (!loaded) return@LaunchedEffect
        if (payload.isStreaming) {
            delay(80)
        }
        render(webViewRef, encodedPayload)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.evaluateJavascript("window.UniVCPRenderer && window.UniVCPRenderer.dispose();", null)
        }
    }
}
