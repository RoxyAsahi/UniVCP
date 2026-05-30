package me.rerere.rikkahub.ui.components.richtext

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichVcpMediaResolverTest {
    @Test
    fun `rewrites localhost vcp image url to configured vcp origin`() {
        val model = Model(modelId = "vcp")
        val settings = Settings(
            chatModelId = model.id,
            vcpLogUrl = "ws://vcp.uniquest.us.kg",
            vcpFileKey = "real-key",
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "UniVCP Dev Relay",
                    baseUrl = "http://154.36.184.44:3000",
                    models = listOf(model),
                )
            )
        )

        val resolved = RichVcpMediaResolver.resolve(
            "http://localhost:6005/pw=123456/images/Hornet表情包/傲娇嘀咕.png",
            settings,
        )

        assertTrue(resolved.orEmpty().startsWith("http://vcp.uniquest.us.kg/pw=real-key/images/"))
        assertTrue(resolved.orEmpty().contains("Hornet%E8%A1%A8%E6%83%85%E5%8C%85"))
        assertTrue(!resolved.orEmpty().contains("localhost"))
        assertTrue(!resolved.orEmpty().contains("pw=123456"))
    }

    @Test
    fun `rewrites localhost vcp image url without emoticon keyword`() {
        val settings = Settings(
            vcpLogUrl = "https://vcp.example.test/ws",
            vcpFileKey = "real-key",
        )

        val resolved = RichVcpMediaResolver.resolve(
            "http://localhost:6005/pw=123456/images/CustomCategory/file.png",
            settings,
        )

        assertEquals("https://vcp.example.test/pw=real-key/images/CustomCategory/file.png", resolved)
    }

    @Test
    fun `does not rewrite when configured origin is unavailable`() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
            )
        )
        val source = "http://localhost:6005/pw=123456/images/Hornet表情包/傲娇嘀咕.png"

        assertEquals(source, RichVcpMediaResolver.resolve(source, settings))
    }

    @Test
    fun `detects resolved vcp media urls`() {
        assertTrue(
            RichVcpMediaResolver.isVcpMediaUrl(
                "http://vcp.example.test/pw=real-key/images/Nova表情包/启动.png"
            )
        )
        assertTrue(
            RichVcpMediaResolver.isVcpMediaUrl(
                "http://192.168.6.162:6005/pw=real-key/images/Hornet表情包/傲娇嘀咕.png"
            )
        )
    }

}
