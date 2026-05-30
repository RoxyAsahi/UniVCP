package me.rerere.rikkahub.ui.components.richtext

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.univcp.bubble.BubblePayload
import com.univcp.bubble.BubbleRenderMode
import com.univcp.bubble.BubbleSnapshotFailureReason
import com.univcp.bubble.BubbleSnapshotRenderer
import com.univcp.bubble.BubbleSnapshotRequest
import com.univcp.bubble.BubbleSnapshotResult
import com.univcp.bubble.BubbleTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichHtmlSnapshotRendererInstrumentedTest {
    @Before
    fun resetRendererSession() = runBlocking {
        BubbleSnapshotRenderer.resetForTest()
    }

    @Test
    fun simpleHtmlProducesBitmapSnapshot() = runBlocking {
        val result = BubbleSnapshotRenderer.render(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            request = BubbleSnapshotRequest(
                payload = testPayload(
                    """
                        <div id="response-root" style="padding:16px;border-radius:12px;background:#f8fafc;color:#0f172a;">
                          <h2 style="margin:0;">Snapshot</h2>
                          <p>Static WebView snapshot content.</p>
                        </div>
                    """.trimIndent()
                ),
                widthPx = 480,
            ),
        )

        assertTrue(result is BubbleSnapshotResult.Success)
        val success = result as BubbleSnapshotResult.Success
        assertEquals(480, success.widthPx)
        assertTrue(success.heightPx >= 80)
        assertTrue(success.bitmap.width == 480)
        assertTrue(success.bitmap.height >= 80)
    }

    @Test
    fun retainedSessionReusesShellAcrossSnapshots() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        BubbleSnapshotRenderer.warmUp(context)
        val first = BubbleSnapshotRenderer.render(
            context = context,
            request = BubbleSnapshotRequest(
                payload = testPayload("""<div id="response-root" style="padding:12px;">First snapshot</div>"""),
                widthPx = 420,
            ),
        )
        val second = BubbleSnapshotRenderer.render(
            context = context,
            request = BubbleSnapshotRequest(
                payload = testPayload("""<div id="response-root" style="padding:12px;">Second snapshot</div>"""),
                widthPx = 420,
            ),
        )

        assertTrue(first is BubbleSnapshotResult.Success)
        assertTrue(second is BubbleSnapshotResult.Success)
        assertEquals(true, (second as BubbleSnapshotResult.Success).sessionReused)

        val stats = BubbleSnapshotRenderer.statsForTest()
        assertEquals(1, stats.sessionCreateCount)
        assertEquals(1, stats.shellLoadCount)
        assertEquals(2, stats.renderRequestCount)
        assertEquals(1, stats.reusedRenderCount)
    }

    @Test
    fun maxPixelBudgetRejectsLargeSnapshot() = runBlocking {
        val result = BubbleSnapshotRenderer.render(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            request = BubbleSnapshotRequest(
                payload = testPayload("""<div id="response-root" style="height:400px;">Too large</div>"""),
                widthPx = 480,
                maxPixels = 100,
            ),
        )

        assertTrue(result is BubbleSnapshotResult.Failure)
        assertEquals(BubbleSnapshotFailureReason.SnapshotTooLarge, (result as BubbleSnapshotResult.Failure).reason)
    }

    private fun testPayload(html: String): BubblePayload {
        return BubblePayload(
            id = "snapshot-test-${html.length}",
            rawContent = html,
            renderMode = BubbleRenderMode.RICH_HTML,
            theme = BubbleTheme(
                dark = false,
                background = "#FFFFFF",
                onBackground = "#111827",
                surface = "#F3F4F6",
                onSurface = "#111827",
                primary = "#2563EB",
                outline = "#D1D5DB",
            ),
        )
    }
}
