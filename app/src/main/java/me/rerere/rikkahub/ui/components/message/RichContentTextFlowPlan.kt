package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

internal data class RichContentTextFlowPlan(
    val eligibleNodeCount: Int,
    val hardStopNodeCount: Int,
    val hardStopReasons: Set<String>,
) {
    val hasEligibleTextFlow: Boolean get() = eligibleNodeCount > 0

    fun metadataLine(): String = listOf(
        "textFlowEligible=$eligibleNodeCount",
        "textFlowHardStops=$hardStopNodeCount",
        "textFlowHardStopReasons=${hardStopReasons.sorted()}",
    ).joinToString(" ")

    companion object {
        val Empty = RichContentTextFlowPlan(
            eligibleNodeCount = 0,
            hardStopNodeCount = 0,
            hardStopReasons = emptySet(),
        )
    }
}

internal fun RichContentDocument.textFlowPlan(): RichContentTextFlowPlan {
    val nodes = root.flattenDocumentNodes()
    val hardStopReasons = linkedSetOf<String>()
    var hardStops = 0
    nodes.forEach { node ->
        val reason = node.textFlowHardStopReason()
        if (reason != null) {
            hardStops += 1
            hardStopReasons += reason
        }
    }
    return RichContentTextFlowPlan(
        eligibleNodeCount = nodes.count { it.capabilities.textFlowEligible },
        hardStopNodeCount = hardStops,
        hardStopReasons = hardStopReasons,
    )
}

internal fun buildRichContentTextFlowPlanFromHtml(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentTextFlowPlan {
    val key = renderTextCacheKey(html)
    return RichContentTextFlowPlanCache.getOrPut(key) {
        buildRichContentDocumentFromHtml(html, analysis).textFlowPlan()
    }
}

private object RichContentTextFlowPlanCache {
    private val cache = RenderLruCache<String, RichContentTextFlowPlan>(maxEntries = 128)

    fun getOrPut(key: String, value: () -> RichContentTextFlowPlan): RichContentTextFlowPlan =
        cache.getOrPut(key, value)
}

private fun RichContentNodeV2.textFlowHardStopReason(): String? {
    return when {
        capabilities.inlineWebViewRequired || capabilities.unsafeRuntime -> "Runtime"
        capabilities.snapshotIslandEligible || capabilities.browserOnlyVisual -> "BrowserVisual"
        capabilities.interactive -> "Action"
        kind == RichContentNodeKind.Table -> "Table"
        kind == RichContentNodeKind.Media -> "Media"
        kind == RichContentNodeKind.Svg -> "Svg"
        kind == RichContentNodeKind.Details -> "Details"
        kind == RichContentNodeKind.Unsupported -> "Unsupported"
        capabilities.unsupportedReasons.isNotEmpty() -> "UnsupportedCapability"
        else -> null
    }
}
