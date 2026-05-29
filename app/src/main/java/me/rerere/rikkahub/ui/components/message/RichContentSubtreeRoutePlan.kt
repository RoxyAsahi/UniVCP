package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

internal data class RichContentSubtreeRoutePlan(
    val candidateNodeCount: Int,
    val rejectedNodeCount: Int,
    val rejectReasons: Set<String>,
    val nativePreservedActionCount: Int,
    val inlineWebViewRequiredCount: Int,
    val wholeSnapshotLikely: Boolean,
    val candidateStablePaths: Set<String> = emptySet(),
    val rejectedStablePaths: Set<String> = emptySet(),
    val candidateKindCounts: Map<String, Int> = emptyMap(),
    val rejectReasonCounts: Map<String, Int> = emptyMap(),
) {
    val hasSnapshotIslandCandidate: Boolean get() = candidateNodeCount > 0
    val allowsSnapshotIslandOptimization: Boolean
        get() = hasSnapshotIslandCandidate &&
            inlineWebViewRequiredCount == 0 &&
            !wholeSnapshotLikely
    fun allowsSnapshotIslandOptimizationFor(modelCandidateCount: Int): Boolean =
        allowsSnapshotIslandOptimization &&
            modelCandidateCount > 0 &&
            modelCandidateCount <= candidateNodeCount

    fun metadataLine(): String = listOf(
        "subtreeCandidates=$candidateNodeCount",
        "subtreeRejected=$rejectedNodeCount",
        "subtreeRejectReasons=${rejectReasons.sorted()}",
        "subtreeRejectReasonCounts=$rejectReasonCounts",
        "subtreeCandidateKinds=$candidateKindCounts",
        "subtreeCandidateStablePaths=${candidateStablePaths.sorted()}",
        "subtreeRejectedStablePaths=${rejectedStablePaths.sorted()}",
        "subtreeNativeActions=$nativePreservedActionCount",
        "subtreeInlineRequired=$inlineWebViewRequiredCount",
        "subtreeWholeSnapshotLikely=$wholeSnapshotLikely",
    ).joinToString(" ")

    companion object {
        val Empty = RichContentSubtreeRoutePlan(
            candidateNodeCount = 0,
            rejectedNodeCount = 0,
            rejectReasons = emptySet(),
            nativePreservedActionCount = 0,
            inlineWebViewRequiredCount = 0,
            wholeSnapshotLikely = false,
        )
    }
}

internal fun RichContentDocument.subtreeRoutePlan(maxIslandCandidates: Int = 2): RichContentSubtreeRoutePlan {
    var candidates = 0
    var rejected = 0
    var inlineRequired = 0
    val rejectReasons = linkedSetOf<String>()
    val candidateStablePaths = linkedSetOf<String>()
    val rejectedStablePaths = linkedSetOf<String>()
    val candidateKinds = mutableListOf<String>()
    val rejectReasonList = mutableListOf<String>()

    fun reject(node: RichContentNodeV2, reason: String) {
        rejected += 1
        rejectReasons += reason
        rejectReasonList += reason
        rejectedStablePaths += node.stablePath
    }

    fun visit(node: RichContentNodeV2, insideInteractive: Boolean) {
        val interactive = insideInteractive || node.capabilities.interactive || node.kind == RichContentNodeKind.Action
        if (node.capabilities.inlineWebViewRequired || node.capabilities.unsafeRuntime) {
            inlineRequired += 1
            reject(node, "Runtime")
        } else if (node.capabilities.snapshotIslandEligible || node.capabilities.browserOnlyVisual) {
            if (interactive) {
                reject(node, "Action")
            } else {
                candidates += 1
                candidateStablePaths += node.stablePath
                candidateKinds += node.kind.name
            }
        } else if (node.kind == RichContentNodeKind.Unsupported) {
            reject(node, "Unsupported")
        }
        node.children.forEach { child -> visit(child, interactive) }
    }

    visit(root, insideInteractive = false)
    if (candidates > maxIslandCandidates) {
        rejected += candidates
        rejectReasons += "TooManyIslands"
        rejectReasonList += List(candidates) { "TooManyIslands" }
        rejectedStablePaths += candidateStablePaths
    }
    return RichContentSubtreeRoutePlan(
        candidateNodeCount = candidates,
        rejectedNodeCount = rejected,
        rejectReasons = rejectReasons,
        nativePreservedActionCount = stats.actionCount,
        inlineWebViewRequiredCount = inlineRequired,
        wholeSnapshotLikely = candidates > maxIslandCandidates ||
            (inlineRequired == 0 && candidates > 0 && stats.nativeBackendCount <= candidates),
        candidateStablePaths = candidateStablePaths,
        rejectedStablePaths = rejectedStablePaths,
        candidateKindCounts = candidateKinds.groupingBy { it }.eachCount(),
        rejectReasonCounts = rejectReasonList.groupingBy { it }.eachCount(),
    )
}

internal fun buildRichContentSubtreeRoutePlanFromHtml(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentSubtreeRoutePlan {
    val key = renderTextCacheKey(html)
    return RichContentSubtreeRoutePlanCache.getOrPut(key) {
        buildRichContentDocumentFromHtml(html, analysis).subtreeRoutePlan()
    }
}

private object RichContentSubtreeRoutePlanCache {
    private val cache = RenderLruCache<String, RichContentSubtreeRoutePlan>(maxEntries = 128)

    fun getOrPut(key: String, value: () -> RichContentSubtreeRoutePlan): RichContentSubtreeRoutePlan =
        cache.getOrPut(key, value)
}
