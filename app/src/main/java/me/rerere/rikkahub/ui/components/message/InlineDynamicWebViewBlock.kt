package me.rerere.rikkahub.ui.components.message

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewParent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FullScreen
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import me.rerere.rikkahub.ui.components.richtext.LocalRichRenderScrollState
import me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCache
import me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheEntry
import me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheKey
import me.rerere.rikkahub.ui.components.richtext.RichRenderHeightConfidence
import me.rerere.rikkahub.ui.components.richtext.RichRenderScrollDirection
import me.rerere.rikkahub.ui.components.richtext.RichRenderScrollState
import me.rerere.rikkahub.ui.components.ui.Tooltip
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt

private const val TAG = "InlineDynamicWebView"
private const val MAX_INLINE_WEBVIEWS = 2
private const val MAX_POOLED_WEBVIEWS = 2
private const val INLINE_WEBVIEW_BASE_URL = "https://univcp-inline.local/"
private const val DEFAULT_HEIGHT_CSS_PX = 720
private const val MAX_HEIGHT_CSS_PX = 6_000
private const val MAX_FROZEN_PIXELS = 6_000_000
private const val HEIGHT_UPDATE_THRESHOLD_CSS_PX = 8
private const val EARLY_HEIGHT_SPIKE_MIN_DELTA_CSS_PX = 240
private const val EARLY_HEIGHT_SPIKE_RATIO = 1.55f
private const val LIVE_NEAR_RELEASE_DELAY_MS = 800L
private const val LIVE_FAR_RELEASE_DELAY_MS = 320L
private const val FRAME_PRESSURE_DEFER_WINDOW_MS = 900L
private const val FRAME_PRESSURE_ADMISSION_COOLDOWN_MS = 360L
private const val SCROLL_NEW_ADMISSION_STAGGER_MS = 180L
private const val INLINE_DYNAMIC_WEBVIEW_CONTENT_TYPE = "InlineDynamicWebView"

private object InlineDynamicWebViewSeenRegistry {
    private val seen = Collections.synchronizedSet(linkedSetOf<String>())

    fun markSeen(id: String) {
        seen += id
    }

    fun hasSeen(id: String): Boolean = id in seen
}

private object InlineWebViewAdmission {
    private val activeIds = Collections.synchronizedSet(linkedSetOf<String>())
    private var lastNewAcquireAtMs = 0L

    fun tryAcquire(id: String, staggerNew: Boolean = false): InlineAcquireResult {
        synchronized(activeIds) {
            if (id in activeIds) return InlineAcquireResult(acquired = true, reason = "already-active")
            if (activeIds.size >= MAX_INLINE_WEBVIEWS) {
                return InlineAcquireResult(acquired = false, reason = "pool-full")
            }
            val nowMs = SystemClock.uptimeMillis()
            if (staggerNew && activeIds.isNotEmpty() && nowMs - lastNewAcquireAtMs < SCROLL_NEW_ADMISSION_STAGGER_MS) {
                return InlineAcquireResult(acquired = false, reason = "admission-stagger")
            }
            activeIds += id
            lastNewAcquireAtMs = nowMs
            return InlineAcquireResult(acquired = true, reason = "new-active")
        }
    }

    fun release(id: String) {
        synchronized(activeIds) {
            activeIds -= id
        }
    }

    fun resetForTest() {
        synchronized(activeIds) {
            activeIds.clear()
            lastNewAcquireAtMs = 0L
        }
    }

    fun acquireForTest(id: String): Boolean {
        synchronized(activeIds) {
            if (id in activeIds) return true
            if (activeIds.size >= MAX_INLINE_WEBVIEWS) return false
            activeIds += id
            return true
        }
    }

    fun activeCount(): Int = synchronized(activeIds) {
        activeIds.size
    }
}

internal fun inlineDynamicWebViewActiveCount(): Int = InlineWebViewAdmission.activeCount()

internal fun inlineDynamicWebViewAcquireForTest(id: String): Boolean = InlineWebViewAdmission.acquireForTest(id)

internal fun inlineDynamicWebViewReleaseForTest(id: String) {
    InlineWebViewAdmission.release(id)
}

internal fun inlineDynamicWebViewResetAdmissionForTest() {
    InlineWebViewAdmission.resetForTest()
}

private data class InlineAcquireResult(
    val acquired: Boolean,
    val reason: String,
)

internal enum class InlineDynamicWebViewPhase {
    Deferred,
    Acquiring,
    Attaching,
    Loading,
    Measuring,
    Live,
    Freezing,
    Releasing,
    Released,
    Crashed,
}

internal fun inlineDynamicWebViewNextPhase(
    current: InlineDynamicWebViewPhase,
    event: String,
): InlineDynamicWebViewPhase = when (event) {
    "admit" -> InlineDynamicWebViewPhase.Acquiring
    "factory" -> InlineDynamicWebViewPhase.Attaching
    "load" -> if (current == InlineDynamicWebViewPhase.Live) {
        current
    } else {
        InlineDynamicWebViewPhase.Loading
    }
    "page-finished" -> if (current == InlineDynamicWebViewPhase.Live) {
        current
    } else {
        InlineDynamicWebViewPhase.Measuring
    }
    "live" -> InlineDynamicWebViewPhase.Live
    "height" -> if (current == InlineDynamicWebViewPhase.Measuring ||
        current == InlineDynamicWebViewPhase.Loading ||
        current == InlineDynamicWebViewPhase.Attaching
    ) {
        InlineDynamicWebViewPhase.Live
    } else {
        current
    }
    "freeze" -> InlineDynamicWebViewPhase.Freezing
    "release" -> InlineDynamicWebViewPhase.Releasing
    "released" -> InlineDynamicWebViewPhase.Released
    "crash" -> InlineDynamicWebViewPhase.Crashed
    "defer" -> InlineDynamicWebViewPhase.Deferred
    else -> current
}

private fun InlineDynamicWebViewPhase.isBeforeStableHeight(): Boolean = this == InlineDynamicWebViewPhase.Acquiring ||
    this == InlineDynamicWebViewPhase.Attaching ||
    this == InlineDynamicWebViewPhase.Loading

private fun shouldDeferNewInlineForFramePressure(scrollState: RichRenderScrollState): Boolean {
    if (!scrollState.scrollInProgress) return false
    if (scrollState.scrollDirection != RichRenderScrollDirection.Up) return false
    if (InlineWebViewAdmission.activeCount() <= 0) return false
    val pressure = RichHtmlRenderTelemetry.recentFramePressureWindow(
        windowMs = FRAME_PRESSURE_DEFER_WINDOW_MS,
        direction = RichRenderScrollDirection.Up.name,
    )
    return pressure.severeFrames > 0 ||
        pressure.jankyFrames > 0 ||
        (pressure.slowFrames >= 3 && pressure.maxFrameMs >= 28f)
}

private object InlineDynamicWebViewPool {
    private val pool = ArrayDeque<WebView>()
    private val discarded = Collections.newSetFromMap(IdentityHashMap<WebView, Boolean>())
    private var createdCount = 0

    fun prewarm(context: Context) {
        if (pool.size >= MAX_POOLED_WEBVIEWS || createdCount >= MAX_POOLED_WEBVIEWS) return
        val webView = create(context)
        reset(webView)
        pool.addLast(webView)
        Log.d(TAG, "prewarm pooled WebView poolSize=${pool.size}")
    }

    fun acquire(context: Context): WebView {
        val webView = if (pool.isNotEmpty()) {
            pool.removeFirst()
        } else {
            create(context)
        }
        webView.detachFromParent()
        (webView.context as? MutableContextWrapper)?.baseContext = context
        return webView
    }

    fun release(webView: WebView, appContext: Context) {
        if (discarded.remove(webView)) {
            createdCount = (createdCount - 1).coerceAtLeast(0)
            return
        }
        webView.detachFromParent()
        reset(webView)
        (webView.context as? MutableContextWrapper)?.baseContext = appContext.applicationContext
        if (pool.size < MAX_POOLED_WEBVIEWS) {
            pool.addLast(webView)
        } else {
            webView.destroy()
            createdCount = (createdCount - 1).coerceAtLeast(0)
        }
    }

    fun discard(webView: WebView) {
        webView.detachFromParent()
        discarded += webView
        runCatching { webView.destroy() }
    }

    private fun create(context: Context): WebView {
        createdCount += 1
        return WebView(MutableContextWrapper(context.applicationContext)).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mediaPlaybackRequiresUserGesture = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            }
            loadUrl("about:blank")
        }
    }

    private fun reset(webView: WebView) {
        webView.onPause()
        webView.stopLoading()
        webView.removeJavascriptInterface("UniVCPInline")
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.tag = null
        webView.loadUrl("about:blank")
        webView.clearHistory()
    }
}

private fun WebView.detachFromParent() {
    val currentParent: ViewParent? = parent
    if (currentParent is ViewGroup) {
        currentParent.removeView(this)
    }
}

private object InlineHeightCache {
    private const val PrefsName = "inline_dynamic_webview_height_v3"
    private const val MaxPersistedEntries = 256
    private var prefs: SharedPreferences? = null
    private val cache: MutableMap<String, Int> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Int>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 128
        },
    )
    private val persistedKeys: MutableSet<String> = Collections.synchronizedSet(linkedSetOf())

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        prefs?.getStringSet("__keys", emptySet()).orEmpty().forEach { persistedKeys += it }
    }

    fun get(id: String): Int? {
        cache[id]?.let { return it }
        val value = prefs?.getInt(id, -1)?.takeIf { it > 0 } ?: return null
        cache[id] = value
        return value
    }

    fun put(id: String, heightCssPx: Int) {
        val height = heightCssPx.coerceIn(96, MAX_HEIGHT_CSS_PX)
        cache[id] = height
        val preferences = prefs ?: return
        synchronized(persistedKeys) {
            persistedKeys += id
            while (persistedKeys.size > MaxPersistedEntries) {
                val oldest = persistedKeys.first()
                persistedKeys -= oldest
                preferences.edit().remove(oldest).apply()
            }
            preferences.edit()
                .putInt(id, height)
                .putStringSet("__keys", persistedKeys.toSet())
                .apply()
        }
    }
}

private data class InlineFrozenBitmapEntry(
    val bitmap: Bitmap,
    val widthPx: Int,
    val heightPx: Int,
)

private object InlineFrozenBitmapCache {
    private const val MaxEntries = 8
    private const val MaxBytes = 24 * 1024 * 1024
    private val lock = Any()
    private val entries = object : LinkedHashMap<String, InlineFrozenBitmapEntry>(MaxEntries, 0.75f, true) {}
    private var totalBytes = 0

    fun get(id: String): InlineFrozenBitmapEntry? = synchronized(lock) {
        entries[id]
    }

    fun put(id: String, entry: InlineFrozenBitmapEntry) {
        synchronized(lock) {
            entries.remove(id)?.let { totalBytes -= it.bitmap.byteCount }
            entries[id] = entry
            totalBytes += entry.bitmap.byteCount
            trimToBudget()
        }
    }

    private fun trimToBudget() {
        val iterator = entries.entries.iterator()
        while ((entries.size > MaxEntries || totalBytes > MaxBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.bitmap.byteCount
            iterator.remove()
        }
    }
}

private fun captureFrozenBitmap(webView: WebView, inlineId: String): Boolean {
    val width = webView.width.takeIf { it > 0 } ?: webView.measuredWidth
    val cachedPhysicalHeight = InlineHeightCache.get(inlineId)
        ?.let { (it * webView.resources.displayMetrics.density).roundToInt() }
    val height = webView.height.takeIf { it > 0 }
        ?: webView.measuredHeight.takeIf { it > 0 }
        ?: cachedPhysicalHeight
        ?: return false
    if (width <= 0 || height <= 0) return false
    if (width.toLong() * height.toLong() > MAX_FROZEN_PIXELS) return false
    return runCatching {
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        webView.draw(Canvas(bitmap))
        InlineFrozenBitmapCache.put(
            inlineId,
            InlineFrozenBitmapEntry(
                bitmap = bitmap,
                widthPx = width,
                heightPx = height,
            ),
        )
        true
    }.onFailure { error ->
        Log.w(TAG, "Failed to freeze inline WebView: ${error.message}")
    }.getOrDefault(false)
}

private fun isCurrentInlineDocumentCallback(
    webView: WebView?,
    inlineId: String,
    url: String?,
): Boolean {
    if (webView?.tag != inlineId) return false
    if (url == null) return true
    return url.startsWith(INLINE_WEBVIEW_BASE_URL)
}

private class InlineWebViewBridge(
    private val inlineId: String,
    private val onHeight: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onStaleReport: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun reportHeight(px: Int) {
        reportHeightFor(inlineId, px)
    }

    @JavascriptInterface
    fun reportHeightFor(id: String, px: Int) {
        if (id != inlineId) {
            main.post { onStaleReport("height") }
            return
        }
        main.post {
            onHeight(px.coerceIn(96, MAX_HEIGHT_CSS_PX))
        }
    }

    @JavascriptInterface
    fun reportStatus(status: String) {
        reportStatusFor(inlineId, status)
    }

    @JavascriptInterface
    fun reportStatusFor(id: String, status: String) {
        if (id != inlineId) {
            main.post { onStaleReport("status") }
            return
        }
        main.post { onStatus(status) }
    }
}

@Composable
internal fun InlineDynamicWebViewBlock(
    html: String,
    previewText: String,
    cellIndex: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    remember(context) {
        InlineHeightCache.init(context.applicationContext)
        RichRenderHeightCache.initialize(context.applicationContext)
        true
    }
    val scrollState = LocalRichRenderScrollState.current
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val inlineId = remember(html, scrollState.viewportWidthDp, density.fontScale) {
        inlineDynamicWebViewCacheId(
            html = html,
            viewportWidthDp = scrollState.viewportWidthDp,
            fontScale = density.fontScale,
        )
    }
    val heightCacheKey = remember(
        html,
        scrollState.viewportWidthDp,
        density.fontScale,
        density.density,
        dark,
    ) {
        RichRenderHeightCache.key(
            id = renderTextCacheKey(html),
            viewportWidthDp = scrollState.viewportWidthDp,
            fontScale = density.fontScale,
            density = density.density,
            themeBucket = if (dark) "dark" else "light",
            contentType = INLINE_DYNAMIC_WEBVIEW_CONTENT_TYPE,
        )
    }
    val cachedHeightEntry = remember(heightCacheKey) { RichRenderHeightCache.getEntry(heightCacheKey) }
    val legacyCachedHeightCssPx = remember(inlineId) { InlineHeightCache.get(inlineId) }
    val cachedHeightCssPx = remember(cachedHeightEntry, legacyCachedHeightCssPx, density.density) {
        cachedHeightEntry?.toInlineCssPx(density) ?: legacyCachedHeightCssPx
    }
    LaunchedEffect(heightCacheKey, cachedHeightEntry, legacyCachedHeightCssPx) {
        if (cachedHeightEntry == null && legacyCachedHeightCssPx != null) {
            RichRenderHeightCache.put(
                key = heightCacheKey,
                heightPx = legacyCachedHeightCssPx.toInlinePhysicalHeightPx(density),
                confidence = RichRenderHeightConfidence.MeasuredInlineWebView,
            )
        }
        RichHtmlRenderTelemetry.recordHeightCache(
            id = heightCacheKey.id,
            contentType = INLINE_DYNAMIC_WEBVIEW_CONTENT_TYPE,
            hit = cachedHeightCssPx != null,
            heightPx = cachedHeightEntry?.heightPx ?: legacyCachedHeightCssPx?.toInlinePhysicalHeightPx(density),
            confidence = cachedHeightEntry?.confidence?.name ?: legacyCachedHeightCssPx?.let {
                RichRenderHeightConfidence.MeasuredInlineWebView.name
            },
            rendererVersion = heightCacheKey.rendererVersion,
            documentSchemaVersion = heightCacheKey.documentSchemaVersion,
            persistent = true,
        )
    }
    val estimatedHeightCssPx = remember(html, previewText) { estimateInlineHeightPx(html, previewText) }
    var admitted by remember(inlineId) { mutableStateOf(false) }
    var renderProcessGone by remember(inlineId) { mutableStateOf(false) }
    var hasBeenLive by remember(inlineId) {
        mutableStateOf(InlineDynamicWebViewSeenRegistry.hasSeen(inlineId))
    }
    var framePressureCooldownUntilMs by remember(inlineId) { mutableStateOf(0L) }
    var admissionRetryTick by remember(inlineId) { mutableIntStateOf(0) }
    val visible = scrollState.visibleCellRange.first <= cellIndex &&
        cellIndex <= scrollState.visibleCellRange.last
    val nearViewport = scrollState.nearViewportRange.first <= cellIndex &&
        cellIndex <= scrollState.nearViewportRange.last

    LaunchedEffect(Unit) {
        InlineDynamicWebViewPool.prewarm(context.applicationContext)
    }

    LaunchedEffect(
        inlineId,
        visible,
        nearViewport,
        scrollState.scrollInProgress,
        scrollState.fastScrolling,
        scrollState.scrollDirection,
        admitted,
        renderProcessGone,
        framePressureCooldownUntilMs,
        admissionRetryTick,
    ) {
        if (renderProcessGone) {
            if (admitted) {
                InlineWebViewAdmission.release(inlineId)
                admitted = false
            }
            return@LaunchedEffect
        }
        if (!admitted) {
            if (visible && !scrollState.fastScrolling) {
                val nowMs = SystemClock.uptimeMillis()
                if (!hasBeenLive &&
                    scrollState.scrollDirection == RichRenderScrollDirection.Up &&
                    nowMs < framePressureCooldownUntilMs
                ) {
                    RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                        id = inlineId,
                        event = "defer",
                        reason = "history-frame-pressure-cooldown",
                        cellIndex = cellIndex,
                        activeCount = InlineWebViewAdmission.activeCount(),
                    )
                    delay(framePressureCooldownUntilMs - nowMs)
                    admissionRetryTick++
                    return@LaunchedEffect
                }
                if (!hasBeenLive && shouldDeferNewInlineForFramePressure(scrollState)) {
                    val pressure = RichHtmlRenderTelemetry.recentFramePressureWindow(
                        windowMs = FRAME_PRESSURE_DEFER_WINDOW_MS,
                        direction = RichRenderScrollDirection.Up.name,
                    )
                    framePressureCooldownUntilMs = SystemClock.uptimeMillis() + FRAME_PRESSURE_ADMISSION_COOLDOWN_MS
                    RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                        id = inlineId,
                        event = "defer",
                        reason = "history-frame-pressure:jank=${pressure.jankyFrames}:severe=${pressure.severeFrames}",
                        cellIndex = cellIndex,
                        activeCount = InlineWebViewAdmission.activeCount(),
                    )
                    delay(FRAME_PRESSURE_ADMISSION_COOLDOWN_MS)
                    admissionRetryTick++
                    return@LaunchedEffect
                }
                val wasSeen = hasBeenLive
                val acquire = InlineWebViewAdmission.tryAcquire(
                    id = inlineId,
                    staggerNew = scrollState.scrollInProgress,
                )
                admitted = acquire.acquired
                if (admitted) {
                    hasBeenLive = true
                    InlineDynamicWebViewSeenRegistry.markSeen(inlineId)
                    RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                        id = inlineId,
                        event = "admit",
                        reason = if (wasSeen) "visible-seen" else "first-visible",
                        cellIndex = cellIndex,
                        activeCount = InlineWebViewAdmission.activeCount(),
                    )
                } else {
                    RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                        id = inlineId,
                        event = "defer",
                        reason = acquire.reason,
                        cellIndex = cellIndex,
                        activeCount = InlineWebViewAdmission.activeCount(),
                    )
                }
            }
            return@LaunchedEffect
        }

        // Keep an already-live WebView stable while the user is still looking at it. A quick finger
        // adjustment can briefly look like fast scrolling, so release only after a short hysteresis.
        if (visible || (nearViewport && !scrollState.fastScrolling)) {
            admitted = InlineWebViewAdmission.tryAcquire(inlineId).acquired
        } else {
            delay(if (nearViewport) LIVE_NEAR_RELEASE_DELAY_MS else LIVE_FAR_RELEASE_DELAY_MS)
            InlineWebViewAdmission.release(inlineId)
            RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                id = inlineId,
                event = "release",
                reason = if (nearViewport) "near-hysteresis" else "far-offscreen",
                cellIndex = cellIndex,
                activeCount = InlineWebViewAdmission.activeCount(),
            )
            admitted = false
        }
    }

    DisposableEffect(inlineId) {
        onDispose {
            InlineWebViewAdmission.release(inlineId)
        }
    }

    if (admitted) {
        InlineWebViewSurface(
            html = html,
            inlineId = inlineId,
            heightCacheKey = heightCacheKey,
            cachedHeightEntry = cachedHeightEntry,
            previewText = previewText,
            cellIndex = cellIndex,
            onOpen = onOpen,
            onRenderProcessGoneCallback = { reason ->
                InlineWebViewAdmission.release(inlineId)
                RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                    id = inlineId,
                    event = "release",
                    reason = reason,
                    cellIndex = cellIndex,
                    activeCount = InlineWebViewAdmission.activeCount(),
                )
                admitted = false
                renderProcessGone = true
            },
            modifier = modifier,
        )
    } else {
        InlineWebViewDeferredPreview(
            inlineId = inlineId,
            cachedHeightEntry = cachedHeightEntry,
            estimatedHeightCssPx = estimatedHeightCssPx,
            previewText = previewText,
            onOpen = onOpen,
            modifier = modifier,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun InlineWebViewSurface(
    html: String,
    inlineId: String,
    heightCacheKey: RichRenderHeightCacheKey,
    cachedHeightEntry: RichRenderHeightCacheEntry?,
    previewText: String,
    cellIndex: Int,
    onOpen: () -> Unit,
    onRenderProcessGoneCallback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scrollState = LocalRichRenderScrollState.current
    val currentScrollState by rememberUpdatedState(scrollState)
    var progress by remember(inlineId) { mutableFloatStateOf(0f) }
    var status by remember(inlineId) { mutableStateOf("loading") }
    var crashed by remember(inlineId) { mutableStateOf(false) }
    var phase by remember(inlineId) { mutableStateOf(InlineDynamicWebViewPhase.Acquiring) }
    var frozenEntry by remember(inlineId) { mutableStateOf(InlineFrozenBitmapCache.get(inlineId)) }
    val cachedHeightCssPx = remember(cachedHeightEntry, inlineId, density.density) {
        cachedHeightEntry?.toInlineCssPx(density) ?: InlineHeightCache.get(inlineId)
    }
    var heightCssPx by remember(inlineId, cachedHeightCssPx) {
        mutableIntStateOf(cachedHeightCssPx ?: estimateInlineHeightPx(html, previewText))
    }

    fun transition(event: String, reason: String) {
        val next = inlineDynamicWebViewNextPhase(phase, event)
        if (next == phase && event != "height") return
        val previous = phase
        phase = next
        RichHtmlRenderTelemetry.recordInlineDynamicWebViewPhase(
            id = inlineId,
            phase = next.name,
            previous = previous.name,
            reason = reason,
            cellIndex = cellIndex,
        )
    }

    val bridge = remember(inlineId) {
        InlineWebViewBridge(
            inlineId = inlineId,
            onHeight = onHeight@ { px ->
                if (phase == InlineDynamicWebViewPhase.Releasing ||
                    phase == InlineDynamicWebViewPhase.Released ||
                    phase == InlineDynamicWebViewPhase.Crashed
                ) {
                    RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                        id = inlineId,
                        event = "ignore-stale",
                        reason = "height-after-${phase.name}",
                        cellIndex = cellIndex,
                        activeCount = InlineWebViewAdmission.activeCount(),
                    )
                    return@onHeight
                }
                var acceptedHeight = false
                if (px <= 160 && heightCssPx > 240) {
                    Log.d(TAG, "ignore early tiny height inline=${inlineId.takeLast(10)} cssPx=$px current=$heightCssPx")
                } else if (phase.isBeforeStableHeight() &&
                    px > heightCssPx + EARLY_HEIGHT_SPIKE_MIN_DELTA_CSS_PX &&
                    px > heightCssPx * EARLY_HEIGHT_SPIKE_RATIO
                ) {
                    Log.d(TAG, "ignore early height spike inline=${inlineId.takeLast(10)} cssPx=$px current=$heightCssPx")
                } else {
                    acceptedHeight = true
                    if (kotlin.math.abs(heightCssPx - px) >= HEIGHT_UPDATE_THRESHOLD_CSS_PX || px > heightCssPx) {
                        Log.d(TAG, "height inline=${inlineId.takeLast(10)} cssPx=$px previous=$heightCssPx")
                        heightCssPx = px
                        InlineHeightCache.put(inlineId, px)
                    }
                    RichRenderHeightCache.put(
                        key = heightCacheKey,
                        heightPx = px.toInlinePhysicalHeightPx(density),
                        confidence = RichRenderHeightConfidence.MeasuredInlineWebView,
                    )
                }
                if (acceptedHeight) {
                    transition("height", "bridge-height")
                }
            },
            onStatus = { value ->
                status = value
                if (value.startsWith("error:")) {
                    transition("load", value)
                }
            },
            onStaleReport = { kind ->
                RichHtmlRenderTelemetry.recordInlineDynamicWebView(
                    id = inlineId,
                    event = "ignore-stale",
                    reason = kind,
                    cellIndex = cellIndex,
                    activeCount = InlineWebViewAdmission.activeCount(),
                )
            },
        )
    }
    val document = remember(html, inlineId) { buildInlineHtmlDocument(html, inlineId) }
    val heightDp = remember(heightCssPx) { heightCssPx.dp }
    fun captureAndRefresh(webView: WebView) {
        if (currentScrollState.scrollInProgress) return
        transition("freeze", "capture")
        if (captureFrozenBitmap(webView, inlineId)) {
            frozenEntry = InlineFrozenBitmapCache.get(inlineId)
        }
        transition("live", "capture-complete")
    }
    val webChromeClient = remember(inlineId) {
        object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress / 100f
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val message = inlineDynamicWebViewConsoleLogLine(
                    inlineId = inlineId,
                    level = consoleMessage.messageLevel().name,
                    lineNumber = consoleMessage.lineNumber(),
                )
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, message)
                } else {
                    Log.d(TAG, message)
                }
                return true
            }
        }
    }
    val webViewClient = remember(inlineId) {
        object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!isCurrentInlineDocumentCallback(view, inlineId, url)) return
                status = "loading"
                transition("load", "page-started")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!isCurrentInlineDocumentCallback(view, inlineId, url)) return
                status = "ready"
                transition("page-finished", "page-finished")
                view?.evaluateJavascript("window.__univcpMeasure && window.__univcpMeasure();", null)
                view?.postDelayed({ captureAndRefresh(view) }, 220L)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                crashed = true
                val reason = if (detail.didCrash()) "render-process-crashed" else "render-process-gone"
                transition("crash", reason)
                onRenderProcessGoneCallback(reason)
                InlineDynamicWebViewPool.discard(view)
                return true
            }
        }
    }

    fun installRuntime(webView: WebView) {
        webView.removeJavascriptInterface("UniVCPInline")
        webView.addJavascriptInterface(bridge, "UniVCPInline")
        webView.webChromeClient = webChromeClient
        webView.webViewClient = webViewClient
    }

    fun requestMeasuredHeight(webView: WebView) {
        webView.evaluateJavascript(
            INLINE_HEIGHT_MEASURE_SCRIPT,
        ) { value ->
            val px = value
                ?.trim()
                ?.trim('"')
                ?.toFloatOrNull()
                ?.toInt()
                ?: return@evaluateJavascript
            bridge.reportHeight(px)
        }
    }

    LaunchedEffect(Unit) {
        InlineDynamicWebViewPool.prewarm(context.applicationContext)
    }

    if (crashed) {
        InlineWebViewDeferredPreview(
            inlineId = inlineId,
            cachedHeightEntry = cachedHeightEntry,
            estimatedHeightCssPx = estimateInlineHeightPx(html, previewText),
            previewText = previewText,
            onOpen = onOpen,
            modifier = modifier,
        )
        return
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heightDp),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heightDp),
                    factory = { context ->
                        transition("factory", "android-view-factory")
                        InlineDynamicWebViewPool.acquire(context).also(::installRuntime)
                    },
                    onReset = { webView ->
                        captureAndRefresh(webView)
                        transition("release", "android-view-reset")
                        webView.onPause()
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.tag = null
                        transition("released", "android-view-reset")
                    },
                    update = { webView ->
                        installRuntime(webView)
                        webView.visibility = android.view.View.VISIBLE
                        webView.onResume()
                        if (webView.tag != inlineId) {
                            webView.tag = inlineId
                            transition("factory", "android-view-update-new")
                            transition("load", "load-new-document")
                            webView.loadDataWithBaseURL(
                                INLINE_WEBVIEW_BASE_URL,
                                document,
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    onRelease = { webView ->
                        captureAndRefresh(webView)
                        transition("release", "android-view-release")
                        InlineDynamicWebViewPool.release(webView, context.applicationContext)
                        transition("released", "pooled")
                    },
                )
                if (progress in 0.01f..0.99f || status == "loading") {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                InlineOpenButton(
                    onOpen = onOpen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun InlineWebViewDeferredPreview(
    inlineId: String,
    cachedHeightEntry: RichRenderHeightCacheEntry?,
    estimatedHeightCssPx: Int,
    previewText: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var frozenEntry by remember(inlineId) { mutableStateOf(InlineFrozenBitmapCache.get(inlineId)) }
    LaunchedEffect(inlineId) {
        delay(32)
        frozenEntry = InlineFrozenBitmapCache.get(inlineId)
    }
    val density = LocalDensity.current
    val cachedHeightDp = remember(inlineId, estimatedHeightCssPx) {
        InlineHeightCache.get(inlineId)?.dp
    }
    val frozenHeight = remember(frozenEntry, density.density) {
        frozenEntry?.let { with(density) { it.heightPx.toDp() } }
    }
    val v3CachedHeightDp = remember(cachedHeightEntry, density.density) {
        cachedHeightEntry?.let { with(density) { it.heightPx.toDp() } }
    }
    val estimatedHeightDp = remember(estimatedHeightCssPx) { estimatedHeightCssPx.dp }
    val placeholderHeight = frozenHeight ?: v3CachedHeightDp ?: cachedHeightDp ?: estimatedHeightDp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = placeholderHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (frozenEntry != null && frozenHeight != null) {
                InlineFrozenContentOrPlaceholder(
                    frozenEntry = frozenEntry,
                    heightDp = frozenHeight,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    InlineOpenButton(
                        onOpen = onOpen,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            } else {
                val measuredPlaceholderHeight = v3CachedHeightDp ?: cachedHeightDp
                if (measuredPlaceholderHeight != null) {
                    InlineFrozenContentOrPlaceholder(
                        frozenEntry = null,
                        heightDp = measuredPlaceholderHeight,
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        InlineOpenButton(
                            onOpen = onOpen,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                } else {
                    Text(
                        text = "动态预览",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = previewText.ifBlank { "这段内容包含脚本、动画或画布。" },
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = onOpen,
                        modifier = Modifier.widthIn(min = 0.dp),
                    ) {
                        Text("全屏查看")
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineFrozenContentOrPlaceholder(
    frozenEntry: InlineFrozenBitmapEntry?,
    heightDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (frozenEntry != null) {
        Image(
            bitmap = frozenEntry.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier
                .fillMaxWidth()
                .height(heightDp),
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(heightDp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
        )
    }
}

@Composable
private fun InlineOpenButton(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Tooltip(
        modifier = modifier,
        tooltip = { Text("全屏查看") },
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
        ) {
            IconButton(
                onClick = onOpen,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.FullScreen,
                    contentDescription = "全屏查看",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun buildInlineHtmlDocument(html: String, inlineId: String): String {
    val escapedId = inlineId.replace("\\", "\\\\").replace("'", "\\'")
    val bridge = """
        <script>
        $INLINE_HEIGHT_MEASURE_FUNCTION
        (function(){
          var id = '$escapedId';
          var lastHeight = 0;
          function measure(){
            var doc = document.documentElement;
            var body = document.body || doc;
            var height = window.__univcpMeasureHeight
              ? window.__univcpMeasureHeight()
              : Math.ceil(Math.max(
                  doc ? doc.scrollHeight : 0,
                  body ? body.scrollHeight : 0,
                  body ? body.offsetHeight : 0,
                  120
                ));
            if (Math.abs(height - lastHeight) < 2) return;
            lastHeight = height;
            try { UniVCPInline.reportHeightFor(id, height); } catch (e) {}
          }
          window.__univcpMeasure = measure;
          window.addEventListener('load', function(){ setTimeout(measure, 0); });
          window.addEventListener('error', function(event){
            try { UniVCPInline.reportStatusFor(id, 'error:' + (event.message || 'error')); } catch (e) {}
            setTimeout(measure, 0);
          });
          window.addEventListener('unhandledrejection', function(event){
            try { UniVCPInline.reportStatusFor(id, 'error:' + String(event.reason || 'promise')); } catch (e) {}
            setTimeout(measure, 0);
          });
          if (window.ResizeObserver) {
            new ResizeObserver(measure).observe(document.documentElement);
            if (document.body) new ResizeObserver(measure).observe(document.body);
          }
          requestAnimationFrame(measure);
          setTimeout(measure, 250);
          setTimeout(measure, 1000);
        })();
        </script>
    """.trimIndent()

    return if (FULL_HTML_DOCUMENT.containsMatchIn(html)) {
        when {
            "</body>" in html.lowercase() -> html.replace(Regex("</body>", RegexOption.IGNORE_CASE), "$bridge</body>")
            else -> "$html$bridge"
        }
    } else {
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <style>
            html,body{margin:0;background:transparent;color:inherit;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;}
            body{padding:0;box-sizing:border-box;overflow-wrap:anywhere;display:flow-root;}
            img,svg,canvas,video{max-width:100%;height:auto;}
            *{box-sizing:border-box;}
          </style>
          <script>
          $INLINE_HEIGHT_MEASURE_FUNCTION
          </script>
        </head>
        <body>
        $html
        $bridge
        </body>
        </html>
        """.trimIndent()
    }
}

private fun estimateInlineHeightPx(html: String, previewText: String): Int {
    HEIGHT_STYLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { explicit ->
        if (explicit in 96..MAX_HEIGHT_CSS_PX) return explicit
    }
    val textLength = previewText.ifBlank {
        html.replace(TAG_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()
    }.length
    return (DEFAULT_HEIGHT_CSS_PX + (textLength / 64) * 32 + TAG_REGEX.findAll(html).count().coerceAtMost(160) * 6)
        .coerceIn(280, MAX_HEIGHT_CSS_PX)
}

private fun RichRenderHeightCacheEntry.toInlineCssPx(density: Density): Int {
    return with(density) { heightPx.toDp().value.roundToInt() }
        .coerceIn(96, MAX_HEIGHT_CSS_PX)
}

private fun Int.toInlinePhysicalHeightPx(density: Density): Int {
    return with(density) { coerceIn(96, MAX_HEIGHT_CSS_PX).dp.roundToPx() }
}

internal fun inlineDynamicWebViewCacheId(
    html: String,
    viewportWidthDp: Float,
    fontScale: Float,
): String {
    val widthBucket = viewportWidthDp.roundToInt().coerceAtLeast(1)
    val fontScaleBucket = (fontScale * 100).roundToInt().coerceAtLeast(1)
    return "inline:${renderTextCacheKey(html)}:w$widthBucket:fs$fontScaleBucket:hm3"
}

internal fun inlineDynamicWebViewConsoleLogLine(
    inlineId: String,
    level: String,
    lineNumber: Int,
): String = "console inline=${inlineId.takeLast(10)} level=$level line=$lineNumber"

private val FULL_HTML_DOCUMENT = Regex("""<\s*html\b|<!doctype\s+html""", RegexOption.IGNORE_CASE)
private val HEIGHT_STYLE_REGEX = Regex("""(?:^|[;\s])(?:min-)?height\s*:\s*(\d{2,4})px\b""", RegexOption.IGNORE_CASE)
private val TAG_REGEX = Regex("""<[^>]+>""")
private val WHITESPACE_REGEX = Regex("""\s+""")

private val INLINE_HEIGHT_MEASURE_SCRIPT = """
(function(){
  return window.__univcpMeasureHeight ? window.__univcpMeasureHeight() : 120;
})();
""".trimIndent()

private val INLINE_HEIGHT_MEASURE_FUNCTION = """
window.__univcpMeasureHeight = function(){
  var doc = document.documentElement;
  var body = document.body || doc;
  var viewportHeight = Math.max(window.innerHeight || 0, window.visualViewport ? window.visualViewport.height : 0, 0);
  var contentMax = 120;
  var flowMax = 120;
  function addContent(value) {
    if (isFinite(value) && value > contentMax) contentMax = value;
  }
  function addFlow(value) {
    if (isFinite(value) && value > flowMax) flowMax = value;
  }
  function addElement(el) {
    if (!el) return;
    var tag = (el.tagName || '').toUpperCase();
    if (tag === 'HTML' || tag === 'BODY') return;
    var rect = null;
    try { rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null; } catch (e) {}
    var top = rect ? rect.top + window.scrollY : 0;
    var bottom = rect ? rect.bottom + window.scrollY : 0;
    addContent(bottom);
    addContent(top + (el.scrollHeight || 0));
    addContent(top + (el.offsetHeight || 0));
    addContent((el.offsetTop || 0) + (el.scrollHeight || 0));
    addContent((el.offsetTop || 0) + (el.offsetHeight || 0));
    if (el.tagName === 'CANVAS') {
      addContent(top + (el.height || el.clientHeight || 0));
    }
    if (el.tagName === 'SVG') {
      try {
        var box = el.viewBox && el.viewBox.baseVal;
        if (box && box.height) addContent(top + box.height);
      } catch (e) {}
    }
    try {
      var style = window.getComputedStyle(el);
      if (style && style.position === 'fixed' && rect) addContent(rect.bottom);
      if (style && style.overflowY && style.overflowY !== 'visible') {
        addContent(top + (el.scrollHeight || 0));
      }
    } catch (e) {}
  }
  addFlow(doc ? doc.scrollHeight : 0);
  addFlow(doc ? doc.offsetHeight : 0);
  addFlow(body ? body.scrollHeight : 0);
  addFlow(body ? body.offsetHeight : 0);
  var all = document.querySelectorAll ? document.querySelectorAll('*') : [];
  var limit = Math.min(all.length, 4000);
  for (var i = 0; i < limit; i++) addElement(all[i]);
  var max = contentMax;
  if (flowMax > viewportHeight + 2 || contentMax <= 140) {
    max = Math.max(max, flowMax);
  }
  return Math.ceil(Math.min(max, $MAX_HEIGHT_CSS_PX));
};
""".trimIndent()
