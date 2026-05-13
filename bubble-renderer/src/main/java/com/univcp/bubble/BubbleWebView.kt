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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "BubbleWebView"
private const val RENDERER_BASE_URL = "file:///android_asset/renderer/"

private val payloadJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

class BubbleBridge(
    private val onHeight: (String, Int) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onError: (String, String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportHeight(id: String, px: Int) {
        main.post { onHeight(id, px.coerceAtLeast(80)) }
    }

    @JavascriptInterface
    fun reportStatus(id: String, status: String) {
        main.post { onStatus(id, status) }
    }

    @JavascriptInterface
    fun reportError(id: String, message: String) {
        main.post { onError(id, message) }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun BubbleWebView(
    payload: BubblePayload,
    modifier: Modifier = Modifier,
    rendererShellUrl: String? = null,
    onStateChanged: (BubbleRenderState) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var renderState by remember(payload.id) { mutableStateOf(BubbleRenderState()) }
    var heightPx by remember(payload.id) { mutableIntStateOf(renderState.heightPx) }

    fun updateState(next: BubbleRenderState) {
        renderState = next
        onStateChanged(next)
    }

    val bridge = remember(payload.id) {
        BubbleBridge(
            onHeight = { id, px ->
                if (id == payload.id) {
                    heightPx = px
                    updateState(renderState.copy(heightPx = px))
                }
            },
            onStatus = { id, status ->
                if (id == payload.id) {
                    updateState(renderState.copy(status = status))
                }
            },
            onError = { id, message ->
                if (id == payload.id) {
                    updateState(renderState.copy(status = "error", error = message))
                }
            }
        )
    }

    fun render(webView: WebView?) {
        if (webView == null || !loaded) return
        val encoded = payloadJson.encodeToString(payload)
        val script = "window.UniVCPRenderer && window.UniVCPRenderer.renderPayload($encoded);"
        webView.evaluateJavascript(script, null)
    }

    val shellHtml = remember {
        context.assets.open("renderer/renderer-shell.html").bufferedReader().use { it.readText() }
    }
    val remoteShellUrl = remember(rendererShellUrl) {
        rendererShellUrl?.takeIf { it.isNotBlank() }
    }

    fun loadBundledShell(webView: WebView) {
        loaded = false
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
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.allowContentAccess = false
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.blockNetworkLoads = remoteShellUrl == null
                    settings.cacheMode = if (remoteShellUrl != null) {
                        WebSettings.LOAD_NO_CACHE
                    } else {
                        WebSettings.LOAD_DEFAULT
                    }
                    settings.mediaPlaybackRequiresUserGesture = true
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
                            render(view)
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
                .animateContentSize()
                .height(with(density) { heightPx.toDp() }),
            update = { webView ->
                webViewRef = webView
                render(webView)
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

    LaunchedEffect(payload, loaded) {
        render(webViewRef)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.evaluateJavascript("window.UniVCPRenderer && window.UniVCPRenderer.dispose();", null)
        }
    }
}
