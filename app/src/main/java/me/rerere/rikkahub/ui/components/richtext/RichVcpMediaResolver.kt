package me.rerere.rikkahub.ui.components.richtext

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val VCP_EMOTICON_PORT = 6005
private const val VCP_PASSWORD_SEGMENT_PREFIX = "pw="

internal object RichVcpMediaResolver {
    fun resolve(source: String?, settings: Settings): String? {
        val original = source?.trim() ?: return source
        val url = original.toHttpUrlOrNull() ?: return source
        if (!url.isVcpLocalEmoticonUrl()) return source
        val baseUrl = settings.vcpMediaBaseUrl() ?: return source
        val fileKey = settings.vcpFileKey.trim()

        return url.newBuilder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .apply {
                if (fileKey.isNotBlank()) {
                    replacePwSegment(fileKey)
                }
            }
            .build()
            .toString()
    }

    private fun HttpUrl.isVcpLocalEmoticonUrl(): Boolean {
        return host.isLoopbackHost() &&
            port == VCP_EMOTICON_PORT &&
            pathSegments.any { it == "images" } &&
            pathSegments.any { it.startsWith(VCP_PASSWORD_SEGMENT_PREFIX) }
    }

    private fun Settings.vcpMediaBaseUrl(): HttpUrl? {
        vcpLogUrl.toVcpMediaBaseUrlOrNull(requireLikelyVcp = false)?.let { return it }
        val currentProvider = getCurrentChatModel()?.findProvider(providers)
        return currentProvider?.baseUrlOrNull()?.toVcpMediaBaseUrlOrNull(requireLikelyVcp = true)
    }

    private fun ProviderSetting.baseUrlOrNull(): String? = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }

    private fun String.toVcpMediaBaseUrlOrNull(requireLikelyVcp: Boolean): HttpUrl? {
        val normalized = trim()
            .replacePrefix("wss://", "https://")
            .replacePrefix("ws://", "http://")
            .let { value ->
                if ("://" in value) value else "http://$value"
        }
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (url.host.isLoopbackHost()) return null
        if (requireLikelyVcp && !url.isLikelyVcpServiceOrigin()) return null
        return url
    }

    private fun HttpUrl.isLikelyVcpServiceOrigin(): Boolean {
        return host.contains("vcp", ignoreCase = true) || port == VCP_EMOTICON_PORT
    }

    private fun String.replacePrefix(oldValue: String, newValue: String): String {
        return if (startsWith(oldValue, ignoreCase = true)) {
            newValue + substring(oldValue.length)
        } else {
            this
        }
    }

    private fun HttpUrl.Builder.replacePwSegment(fileKey: String) {
        val index = build().pathSegments.indexOfFirst { it.startsWith("pw=") }
        if (index >= 0) {
            setPathSegment(index, "pw=$fileKey")
        }
    }

    private fun String.isLoopbackHost(): Boolean {
        val normalized = lowercase().trim('[', ']')
        return normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1"
    }
}
