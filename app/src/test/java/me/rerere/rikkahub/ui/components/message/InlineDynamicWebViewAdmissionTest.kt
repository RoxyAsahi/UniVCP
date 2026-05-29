package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineDynamicWebViewAdmissionTest {
    @Test
    fun `crash releases admission slot`() {
        inlineDynamicWebViewResetAdmissionForTest()

        assertTrue(inlineDynamicWebViewAcquireForTest("inline-a"))
        inlineDynamicWebViewReleaseForTest("inline-a")

        assertEquals(0, inlineDynamicWebViewActiveCount())
        assertTrue(inlineDynamicWebViewAcquireForTest("inline-b"))
        inlineDynamicWebViewResetAdmissionForTest()
    }

    @Test
    fun `double crash does not exhaust admission slots`() {
        inlineDynamicWebViewResetAdmissionForTest()

        assertTrue(inlineDynamicWebViewAcquireForTest("inline-a"))
        assertTrue(inlineDynamicWebViewAcquireForTest("inline-b"))
        assertFalse(inlineDynamicWebViewAcquireForTest("inline-c"))

        inlineDynamicWebViewReleaseForTest("inline-a")
        inlineDynamicWebViewReleaseForTest("inline-b")

        assertEquals(0, inlineDynamicWebViewActiveCount())
        assertTrue(inlineDynamicWebViewAcquireForTest("inline-c"))
        assertTrue(inlineDynamicWebViewAcquireForTest("inline-d"))
        inlineDynamicWebViewResetAdmissionForTest()
    }

    @Test
    fun `dispose after crash release is idempotent`() {
        inlineDynamicWebViewResetAdmissionForTest()

        assertTrue(inlineDynamicWebViewAcquireForTest("inline-a"))
        inlineDynamicWebViewReleaseForTest("inline-a")
        inlineDynamicWebViewReleaseForTest("inline-a")

        assertEquals(0, inlineDynamicWebViewActiveCount())
        inlineDynamicWebViewResetAdmissionForTest()
    }
}
