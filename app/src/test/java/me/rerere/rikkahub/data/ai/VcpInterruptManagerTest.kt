package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VcpInterruptManagerTest {
    private val manager = VcpInterruptManager(
        client = OkHttpClient(),
        json = Json,
    )

    @Test
    fun `builds interrupt url from base url with v1`() {
        val url = manager.buildInterruptUrl(
            ProviderSetting.OpenAI(
                baseUrl = "http://127.0.0.1:5890/v1",
                chatCompletionsPath = "/chat/completions",
            )
        )

        assertEquals("http://127.0.0.1:5890/v1/interrupt", url.toString())
    }

    @Test
    fun `builds interrupt url from chat completions path with v1`() {
        val url = manager.buildInterruptUrl(
            ProviderSetting.OpenAI(
                baseUrl = "http://127.0.0.1:5890",
                chatCompletionsPath = "/v1/chat/completions",
            )
        )

        assertEquals("http://127.0.0.1:5890/v1/interrupt", url.toString())
    }

    @Test
    fun `does not build interrupt url for response api`() {
        val url = manager.buildInterruptUrl(
            ProviderSetting.OpenAI(
                baseUrl = "http://127.0.0.1:5890/v1",
                useResponseApi = true,
            )
        )

        assertNull(url)
    }
}
