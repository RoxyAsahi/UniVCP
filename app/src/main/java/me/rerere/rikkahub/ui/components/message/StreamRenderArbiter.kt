package me.rerere.rikkahub.ui.components.message

internal data class StreamRenderFrame(
    val content: String,
    val streaming: Boolean,
    val nowMs: Long,
)

internal data class StreamRenderDecision(
    val publishText: Boolean,
    val hasUnclosedRichBlock: Boolean,
    val forceFlush: Boolean,
    val nextCheckDelayMs: Long? = null,
)

internal class StreamRenderArbiter(
    private val sampleWindowMs: Long = 120L,
) {
    private var lastPublishedContent: String? = null
    private var lastPublishedAtMs: Long = Long.MIN_VALUE / 4
    private var wasInUnclosedRichBlock: Boolean = false

    fun onFrame(frame: StreamRenderFrame): StreamRenderDecision {
        val richState = inspectStreamRenderContent(frame.content)
        val finalFrame = !frame.streaming
        val contentChanged = frame.content != lastPublishedContent
        val enteredUnclosedRichBlock = richState.hasUnclosedRichBlock && !wasInUnclosedRichBlock
        val closedRichBlock = wasInUnclosedRichBlock && !richState.hasUnclosedRichBlock && richState.hasRichRoot
        val elapsedMs = frame.nowMs - lastPublishedAtMs

        val publish = when {
            !contentChanged -> false
            finalFrame -> true
            lastPublishedContent == null -> true
            enteredUnclosedRichBlock -> true
            richState.hasUnclosedRichBlock -> false
            closedRichBlock -> true
            elapsedMs >= sampleWindowMs -> true
            else -> false
        }
        val nextDelay = if (
            frame.streaming &&
            contentChanged &&
            !publish &&
            !richState.hasUnclosedRichBlock &&
            elapsedMs in 0 until sampleWindowMs
        ) {
            sampleWindowMs - elapsedMs
        } else {
            null
        }

        wasInUnclosedRichBlock = richState.hasUnclosedRichBlock
        if (publish) {
            lastPublishedContent = frame.content
            lastPublishedAtMs = frame.nowMs
        }

        return StreamRenderDecision(
            publishText = publish,
            hasUnclosedRichBlock = richState.hasUnclosedRichBlock,
            forceFlush = finalFrame || closedRichBlock,
            nextCheckDelayMs = nextDelay,
        )
    }
}

internal data class StreamRenderContentState(
    val hasRichRoot: Boolean,
    val hasUnclosedRichBlock: Boolean,
)

internal fun inspectStreamRenderContent(content: String): StreamRenderContentState {
    var cursor = 0
    var hasRichRoot = false
    while (cursor < content.length) {
        val root = RichHtmlRootDetector.findNextCandidate(content, cursor) ?: break
        if (root.startTagEnd < 0) {
            return StreamRenderContentState(hasRichRoot = true, hasUnclosedRichBlock = true)
        }

        hasRichRoot = true
        val end = RichHtmlRootDetector.findMatchingElementEnd(content, root.startTagEnd + 1, root.tagName)
        if (end == null) {
            return StreamRenderContentState(hasRichRoot = true, hasUnclosedRichBlock = true)
        }
        cursor = end
    }
    return StreamRenderContentState(hasRichRoot = hasRichRoot, hasUnclosedRichBlock = false)
}
