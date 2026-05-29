package me.rerere.rikkahub.ui.components.richtext.fidelity

import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

internal enum class RichFidelityFixtureSourceKind {
    Synthetic,
    RenderSeed,
    DeviceSeed,
}

internal enum class RichFidelityCategory {
    TextHeavy,
    Table,
    Card,
    Svg,
    CssVisual,
    DynamicRuntime,
    Media,
    MixedInteractive,
}

internal enum class RichFidelityPrivacyMode {
    MetadataOnly,
    CuratedPublicHtml,
    CuratedPublicContent,
    LocalDeviceOnly,
}

internal data class RichFidelityFixture(
    val id: String,
    val sourceKind: RichFidelityFixtureSourceKind,
    val contentDigest: String,
    val category: RichFidelityCategory,
    val expectedRoute: String? = null,
    val privacyMode: RichFidelityPrivacyMode = RichFidelityPrivacyMode.MetadataOnly,
    val html: String? = null,
    val markdown: String? = null,
    val protocolActionDigestSource: String? = null,
) {
    init {
        if (privacyMode == RichFidelityPrivacyMode.MetadataOnly) {
            require(html == null) { "Metadata-only fixtures must not store raw HTML." }
            require(markdown == null) { "Metadata-only fixtures must not store raw Markdown." }
            require(protocolActionDigestSource == null) { "Metadata-only fixtures must not store raw protocol actions." }
        }
    }
}

internal object RichFidelityFixtures {
    val synthetic: List<RichFidelityFixture> = listOf(
        curated("synthetic-text-heavy", "<div id=\"vcp-root\"><p>${"text ".repeat(120)}</p></div>", RichFidelityCategory.TextHeavy),
        markdown(
            id = "synthetic-markdown-textflow",
            markdown = """
                This is a **bold** and _italic_ markdown paragraph with `inline code` and [link](https://example.test).

                - first item
                - second item
            """.trimIndent(),
            category = RichFidelityCategory.TextHeavy,
        ),
        protocol(
            id = "synthetic-protocol-action",
            actionDigestSource = "action:synthetic-send",
            category = RichFidelityCategory.MixedInteractive,
        ),
        curated("synthetic-svg", "<div id=\"vcp-root\"><svg width=\"80\" height=\"80\"><rect width=\"80\" height=\"80\"/></svg></div>", RichFidelityCategory.Svg),
        curated("synthetic-css-visual", "<div id=\"vcp-root\" style=\"filter:blur(2px);width:120px;height:80px\"></div>", RichFidelityCategory.CssVisual),
        curated("synthetic-dynamic", "<div id=\"vcp-root\"><canvas></canvas><script>draw()</script></div>", RichFidelityCategory.DynamicRuntime),
    )

    fun deviceSeedDescriptor(
        assistant: String = "Uika",
        conversation: String = "文字游龙",
    ): RichFidelityFixture {
        val id = "device:${assistant}:${conversation}"
        return RichFidelityFixture(
            id = id,
            sourceKind = RichFidelityFixtureSourceKind.DeviceSeed,
            contentDigest = renderTextCacheKey(id),
            category = RichFidelityCategory.MixedInteractive,
            privacyMode = RichFidelityPrivacyMode.LocalDeviceOnly,
        )
    }

    private fun curated(id: String, html: String, category: RichFidelityCategory): RichFidelityFixture {
        return RichFidelityFixture(
            id = id,
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = renderTextCacheKey(html),
            category = category,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = html,
        )
    }

    private fun markdown(
        id: String,
        markdown: String,
        category: RichFidelityCategory,
    ): RichFidelityFixture {
        return RichFidelityFixture(
            id = id,
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = renderTextCacheKey(markdown),
            category = category,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicContent,
            markdown = markdown,
        )
    }

    private fun protocol(
        id: String,
        actionDigestSource: String,
        category: RichFidelityCategory,
    ): RichFidelityFixture {
        return RichFidelityFixture(
            id = id,
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = renderTextCacheKey(actionDigestSource),
            category = category,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicContent,
            protocolActionDigestSource = actionDigestSource,
        )
    }
}
