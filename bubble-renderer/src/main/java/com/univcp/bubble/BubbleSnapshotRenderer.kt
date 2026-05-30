package com.univcp.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

data class BubbleSnapshotRequest(
    val payload: BubblePayload,
    val widthPx: Int,
    val timeoutMs: Long = 2_500L,
    val maxHeightPx: Int = 6_000,
    val maxPixels: Int = 6_000_000,
)

sealed interface BubbleSnapshotResult {
    data class Success(
        val bitmap: Bitmap,
        val widthPx: Int,
        val heightPx: Int,
        val renderTimeMs: Long,
        val queueWaitMs: Long = 0L,
        val sessionReused: Boolean = false,
    ) : BubbleSnapshotResult

    data class Failure(
        val reason: BubbleSnapshotFailureReason,
        val message: String? = null,
        val queueWaitMs: Long = 0L,
    ) : BubbleSnapshotResult
}

enum class BubbleSnapshotFailureReason {
    InvalidSize,
    RendererError,
    SnapshotTooLarge,
    Timeout,
}

data class BubbleSnapshotRendererStats(
    val sessionCreateCount: Int = 0,
    val shellLoadCount: Int = 0,
    val renderRequestCount: Int = 0,
    val reusedRenderCount: Int = 0,
)

object BubbleSnapshotRenderer {
    private val renderMutex = Mutex()
    private val statsLock = Any()
    private var session: SnapshotSession? = null
    private var stats = BubbleSnapshotRendererStats()

    suspend fun warmUp(context: Context) {
        renderMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                ensureSession(context.applicationContext).warmUp(timeoutMs = 2_500L)
            }
        }
    }

    suspend fun render(
        context: Context,
        request: BubbleSnapshotRequest,
    ): BubbleSnapshotResult {
        val widthPx = request.widthPx
        if (widthPx <= 0 || request.maxHeightPx <= 0 || request.maxPixels <= 0) {
            return BubbleSnapshotResult.Failure(BubbleSnapshotFailureReason.InvalidSize)
        }

        val enqueuedAtMs = SystemClock.uptimeMillis()
        return renderMutex.withLock {
            val queueWaitMs = (SystemClock.uptimeMillis() - enqueuedAtMs).coerceAtLeast(0L)
            withContext(Dispatchers.Main.immediate) {
                val retainedSession = ensureSession(context.applicationContext)
                incrementRenderRequest(retainedSession.hasRenderedPayload)
                retainedSession.render(request, queueWaitMs)
            }
        }
    }

    fun statsForTest(): BubbleSnapshotRendererStats = synchronized(statsLock) {
        stats
    }

    suspend fun resetForTest() {
        renderMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                session?.destroy()
                session = null
                synchronized(statsLock) {
                    stats = BubbleSnapshotRendererStats()
                }
            }
        }
    }

    private fun ensureSession(context: Context): SnapshotSession {
        val current = session
        if (current != null && !current.destroyed) {
            return current
        }
        return SnapshotSession(
            context = context,
            onShellLoaded = {
                synchronized(statsLock) {
                    stats = stats.copy(shellLoadCount = stats.shellLoadCount + 1)
                }
            },
        ).also {
            session = it
            synchronized(statsLock) {
                stats = stats.copy(sessionCreateCount = stats.sessionCreateCount + 1)
            }
        }
    }

    private fun incrementRenderRequest(sessionReused: Boolean) = synchronized(statsLock) {
        stats = stats.copy(
            renderRequestCount = stats.renderRequestCount + 1,
            reusedRenderCount = stats.reusedRenderCount + if (sessionReused) 1 else 0,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private class SnapshotSession(
    private val context: Context,
    private val onShellLoaded: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val bridge = SnapshotBridge(
        onHeight = ::handleHeight,
        onStatus = ::handleStatus,
        onError = ::handleError,
    )
    private val webView: WebView = WebView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        configureBubbleRendererWebView(blockNetworkLoads = true)
        addJavascriptInterface(bridge, "UniVCPAndroid")
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                shellLoaded = true
                shellLoading = false
                shellTimeoutRunnable?.let(main::removeCallbacks)
                shellTimeoutRunnable = null
                shellContinuation?.let { continuation ->
                    shellContinuation = null
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
                onShellLoaded()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    failShellLoad(error?.description?.toString())
                    handleError(activeRender?.payloadId.orEmpty(), error?.description?.toString().orEmpty())
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
        }
    }

    var destroyed: Boolean = false
        private set
    var hasRenderedPayload: Boolean = false
        private set

    private var shellLoaded = false
    private var shellLoading = false
    private var shellContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private var shellTimeoutRunnable: Runnable? = null
    private var activeRender: ActiveRender? = null

    suspend fun warmUp(timeoutMs: Long): Boolean {
        if (destroyed) return false
        if (shellLoaded) return true
        return suspendCancellableCoroutine { continuation ->
            shellContinuation = continuation
            shellTimeoutRunnable = Runnable {
                failShellLoad("Snapshot shell load timed out after ${timeoutMs}ms")
            }.also { main.postDelayed(it, timeoutMs) }
            if (!shellLoading) {
                shellLoading = true
                webView.loadDataWithBaseURL(
                    RENDERER_BASE_URL,
                    context.loadBubbleRendererShellHtml(),
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            continuation.invokeOnCancellation {
                if (shellContinuation === continuation) {
                    shellContinuation = null
                }
            }
        }
    }

    suspend fun render(
        request: BubbleSnapshotRequest,
        queueWaitMs: Long,
    ): BubbleSnapshotResult {
        if (!warmUp(timeoutMs = request.timeoutMs)) {
            return BubbleSnapshotResult.Failure(
                reason = BubbleSnapshotFailureReason.RendererError,
                message = "Snapshot shell did not load",
                queueWaitMs = queueWaitMs,
            )
        }
        val sessionReused = hasRenderedPayload
        return suspendCancellableCoroutine { continuation ->
            var completed = false

            fun complete(result: BubbleSnapshotResult) {
                if (completed) return
                completed = true
                activeRender?.timeoutRunnable?.let(main::removeCallbacks)
                activeRender = null
                hasRenderedPayload = true
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }

            val render = ActiveRender(
                payloadId = request.payload.id,
                request = request,
                queueWaitMs = queueWaitMs,
                sessionReused = sessionReused,
                renderStartedAtMs = SystemClock.uptimeMillis(),
                complete = ::complete,
            )
            render.timeoutRunnable = Runnable {
                render.complete(
                    BubbleSnapshotResult.Failure(
                        reason = BubbleSnapshotFailureReason.Timeout,
                        message = "Snapshot render timed out after ${request.timeoutMs}ms",
                        queueWaitMs = queueWaitMs,
                    )
                )
            }
            activeRender = render

            continuation.invokeOnCancellation {
                if (activeRender === render) {
                    render.timeoutRunnable?.let(main::removeCallbacks)
                    activeRender = null
                    runCatching {
                        webView.evaluateJavascript("window.UniVCPRenderer && window.UniVCPRenderer.dispose();", null)
                    }
                }
            }

            main.postDelayed(render.timeoutRunnable!!, request.timeoutMs)
            measureForRequest(request)
            val script = "window.UniVCPRenderer && window.UniVCPRenderer.dispose();" +
                bubbleRenderScript(request.payload.toRendererJson())
            webView.evaluateJavascript(script, null)
        }
    }

    fun destroy() {
        destroyed = true
        shellTimeoutRunnable?.let(main::removeCallbacks)
        activeRender?.timeoutRunnable?.let(main::removeCallbacks)
        shellContinuation?.let { continuation ->
            shellContinuation = null
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        activeRender = null
        runCatching { webView.removeJavascriptInterface("UniVCPAndroid") }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.destroy() }
    }

    private fun failShellLoad(message: String?) {
        shellLoading = false
        shellTimeoutRunnable?.let(main::removeCallbacks)
        shellTimeoutRunnable = null
        shellContinuation?.let { continuation ->
            shellContinuation = null
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        activeRender?.complete(
            BubbleSnapshotResult.Failure(
                reason = BubbleSnapshotFailureReason.RendererError,
                message = message,
                queueWaitMs = activeRender?.queueWaitMs ?: 0L,
            )
        )
    }

    private fun measureForRequest(request: BubbleSnapshotRequest) {
        webView.layoutParams = ViewGroup.LayoutParams(request.widthPx, request.maxHeightPx)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(request.widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(request.maxHeightPx, View.MeasureSpec.AT_MOST),
        )
        webView.layout(0, 0, request.widthPx, request.maxHeightPx)
    }

    private fun handleHeight(id: String, heightPx: Int) {
        val render = activeRender ?: return
        if (render.payloadId != id) return
        render.reportedHeightPx = heightPx.coerceAtLeast(80)
    }

    private fun handleStatus(id: String, status: String) {
        val render = activeRender ?: return
        if (render.payloadId != id || status != "ready" || render.capturePosted) return
        render.capturePosted = true
        main.postDelayed({ capture(render) }, 120L)
    }

    private fun handleError(id: String, message: String) {
        val render = activeRender ?: return
        if (id.isNotBlank() && render.payloadId != id) return
        render.complete(
            BubbleSnapshotResult.Failure(
                reason = BubbleSnapshotFailureReason.RendererError,
                message = message,
                queueWaitMs = render.queueWaitMs,
            )
        )
    }

    private fun capture(render: ActiveRender) {
        if (activeRender !== render) return
        val request = render.request
        val heightPx = max(render.reportedHeightPx, 80).coerceAtMost(request.maxHeightPx)
        if (request.widthPx.toLong() * heightPx.toLong() > request.maxPixels.toLong()) {
            webView.evaluateJavascript("window.UniVCPRenderer && window.UniVCPRenderer.dispose();", null)
            render.complete(
                BubbleSnapshotResult.Failure(
                    reason = BubbleSnapshotFailureReason.SnapshotTooLarge,
                    message = "Snapshot ${request.widthPx}x$heightPx exceeds ${request.maxPixels} pixels",
                    queueWaitMs = render.queueWaitMs,
                )
            )
            return
        }

        val exactWidth = View.MeasureSpec.makeMeasureSpec(request.widthPx, View.MeasureSpec.EXACTLY)
        val exactHeight = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        runCatching {
            webView.measure(exactWidth, exactHeight)
            webView.layout(0, 0, request.widthPx, heightPx)
            val bitmap = Bitmap.createBitmap(request.widthPx, heightPx, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))
            webView.evaluateJavascript("window.UniVCPRenderer && window.UniVCPRenderer.dispose();", null)
            render.complete(
                BubbleSnapshotResult.Success(
                    bitmap = bitmap,
                    widthPx = request.widthPx,
                    heightPx = heightPx,
                    renderTimeMs = (SystemClock.uptimeMillis() - render.renderStartedAtMs).coerceAtLeast(0L),
                    queueWaitMs = render.queueWaitMs,
                    sessionReused = render.sessionReused,
                )
            )
        }.onFailure { throwable ->
            render.complete(
                BubbleSnapshotResult.Failure(
                    reason = BubbleSnapshotFailureReason.RendererError,
                    message = throwable.message,
                    queueWaitMs = render.queueWaitMs,
                )
            )
        }
    }
}

private class ActiveRender(
    val payloadId: String,
    val request: BubbleSnapshotRequest,
    val queueWaitMs: Long,
    val sessionReused: Boolean,
    val renderStartedAtMs: Long,
    val complete: (BubbleSnapshotResult) -> Unit,
) {
    var reportedHeightPx: Int = 0
    var capturePosted: Boolean = false
    var timeoutRunnable: Runnable? = null
}

private class SnapshotBridge(
    private val onHeight: (String, Int) -> Unit,
    private val onStatus: (String, String) -> Unit,
    private val onError: (String, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportHeight(id: String, px: Int) {
        main.post { onHeight(id, px) }
    }

    @JavascriptInterface
    fun reportStatus(id: String, status: String) {
        main.post { onStatus(id, status) }
    }

    @JavascriptInterface
    fun reportError(id: String, message: String) {
        main.post { onError(id, message) }
    }

    @JavascriptInterface
    fun sendInput(id: String, text: String) {
        // Snapshot previews are static images; button hit testing stays in the native path.
    }
}
