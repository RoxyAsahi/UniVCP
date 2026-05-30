package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class RichVcpMediaRequestGateTest {
    @Before
    fun setUp() {
        RichVcpMediaRequestGate.clearForTests()
    }

    @After
    fun tearDown() {
        RichVcpMediaRequestGate.clearForTests()
    }

    @Test
    fun `skips recently failed vcp image request`() {
        val request = RichMediaRequest.fromSource(
            source = "http://vcp.example.test/pw=real-key/images/Nova表情包/启动.png",
            kind = RichMediaKind.Image,
        )

        assertFalse(RichVcpMediaRequestGate.shouldSkip(request))

        RichVcpMediaRequestGate.markFailure(request)

        assertTrue(RichVcpMediaRequestGate.shouldSkip(request))
    }

    @Test
    fun `success clears failed vcp image request`() {
        val request = RichMediaRequest.fromSource(
            source = "http://vcp.example.test/pw=real-key/images/Nova表情包/启动.png",
            kind = RichMediaKind.Image,
        )

        RichVcpMediaRequestGate.markFailure(request)
        RichVcpMediaRequestGate.markSuccess(request)

        assertFalse(RichVcpMediaRequestGate.shouldSkip(request))
    }

    @Test
    fun `does not gate non vcp image request`() {
        val request = RichMediaRequest.fromSource(
            source = "https://example.test/image.png",
            kind = RichMediaKind.Image,
        )

        RichVcpMediaRequestGate.markFailure(request)

        assertFalse(RichVcpMediaRequestGate.shouldSkip(request))
        assertFalse(request.vcpCacheKey().orEmpty().contains("example.test"))
    }
}
