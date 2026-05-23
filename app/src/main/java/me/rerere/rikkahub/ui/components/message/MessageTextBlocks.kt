package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

internal sealed interface MessageTextBlock {
    data class Markdown(val text: String) : MessageTextBlock
    data class VcpHtml(
        val html: String,
        val partial: Boolean,
        val executable: Boolean
    ) : MessageTextBlock

    data class Protocol(
        val kind: ProtocolKind,
        val raw: String,
        val success: Boolean? = null
    ) : MessageTextBlock
}

internal enum class ProtocolKind {
    MetaThinking,
    ToolRequest,
    ToolResult
}

internal fun parseMessageTextBlocks(
    text: String,
    streaming: Boolean
): List<MessageTextBlock> {
    if (text.isBlank()) return emptyList()
    if (!streaming) {
        return messageTextBlockCache.getOrPut(renderTextCacheKey(text)) {
            parseMessageTextBlocksUncached(text = text, streaming = false)
        }
    }

    return parseMessageTextBlocksUncached(text = text, streaming = true)
}

private fun parseMessageTextBlocksUncached(
    text: String,
    streaming: Boolean
): List<MessageTextBlock> {
    if (text.isBlank()) return emptyList()

    val result = mutableListOf<MessageTextBlock>()
    var cursor = 0

    fun appendMarkdown(value: String) {
        if (value.isBlank()) return
        val previous = result.lastOrNull()
        if (previous is MessageTextBlock.Markdown) {
            result[result.lastIndex] = previous.copy(text = previous.text + value)
        } else {
            result += MessageTextBlock.Markdown(value)
        }
    }

    while (cursor < text.length) {
        val next = findNextSpecialBlock(text, cursor, streaming)
        if (next == null) {
            appendMarkdown(text.substring(cursor))
            break
        }

        if (next.start > cursor) {
            appendMarkdown(text.substring(cursor, next.start))
        }
        result += next.block
        cursor = next.end.coerceAtLeast(next.start + 1)
    }

    return result
}

internal fun stableMessageTextBlockKey(
    messageId: String,
    blockIndex: Int,
    block: MessageTextBlock,
): String {
    val type = when (block) {
        is MessageTextBlock.Markdown -> "markdown"
        is MessageTextBlock.VcpHtml -> if (block.partial) "html-partial" else "html"
        is MessageTextBlock.Protocol -> "protocol-${block.kind.name}"
    }
    val content = when (block) {
        is MessageTextBlock.Markdown -> block.text
        is MessageTextBlock.VcpHtml -> block.html
        is MessageTextBlock.Protocol -> block.raw
    }
    return "$messageId:$blockIndex:$type:${renderTextCacheKey(content)}"
}

private data class SpecialBlock(
    val start: Int,
    val end: Int,
    val block: MessageTextBlock
)

private fun findNextSpecialBlock(
    text: String,
    startIndex: Int,
    streaming: Boolean
): SpecialBlock? {
    var searchIndex = startIndex
    while (searchIndex < text.length) {
        val next = listOfNotNull(
            findMetaThinkingBlock(text, searchIndex),
            findToolFenceBlock(text, searchIndex),
            findToolRequestBlock(text, searchIndex),
            findToolResultBlock(text, searchIndex),
            findRichRootBlock(text, searchIndex, streaming),
        ).minByOrNull { it.start } ?: return null

        if (!isInsidePlainMarkdownFence(text, next.start)) {
            return next
        }
        searchIndex = next.end.coerceAtLeast(next.start + 1)
    }
    return null
}

private fun findMetaThinkingBlock(text: String, startIndex: Int): SpecialBlock? {
    val start = text.indexOf("[--- VCP元思考链:", startIndex)
    if (start < 0) return null
    val endToken = "[--- 元思考链结束 ---]"
    val endTokenStart = text.indexOf(endToken, start)
    val end = if (endTokenStart >= 0) endTokenStart + endToken.length else text.length
    return SpecialBlock(
        start = start,
        end = end,
        block = MessageTextBlock.Protocol(
            kind = ProtocolKind.MetaThinking,
            raw = text.substring(start, end),
        )
    )
}

private fun findToolFenceBlock(text: String, startIndex: Int): SpecialBlock? {
    val startMatch = TOOL_FENCE_START.find(text, startIndex) ?: return null
    val contentStart = startMatch.range.last + 1
    val endMatch = TOOL_FENCE_END.find(text, contentStart)
    val end = if (endMatch != null) endMatch.range.last + 1 else text.length
    return SpecialBlock(
        start = startMatch.range.first,
        end = end,
        block = MessageTextBlock.Protocol(
            kind = ProtocolKind.ToolRequest,
            raw = text.substring(startMatch.range.first, end),
        )
    )
}

private fun findToolRequestBlock(text: String, startIndex: Int): SpecialBlock? {
    val startToken = "<<<[TOOL_REQUEST]>>>"
    val endToken = "<<<[END_TOOL_REQUEST]>>>"
    val start = text.indexOf(startToken, startIndex)
    if (start < 0) return null
    val endTokenStart = text.indexOf(endToken, start + startToken.length)
    val end = if (endTokenStart >= 0) endTokenStart + endToken.length else text.length
    return SpecialBlock(
        start = start,
        end = end,
        block = MessageTextBlock.Protocol(
            kind = ProtocolKind.ToolRequest,
            raw = text.substring(start, end),
        )
    )
}

private fun findToolResultBlock(text: String, startIndex: Int): SpecialBlock? {
    val starts = listOf(
        "[[VCP调用结果信息汇总:",
        "[[工具调用结果信息汇总",
    )
    val start = starts
        .map { text.indexOf(it, startIndex) }
        .filter { it >= 0 }
        .minOrNull()
        ?: return null

    val endTokens = listOf(
        "VCP调用结果结束]]",
        "工具调用结果结束]]",
    )
    val endTokenStart = endTokens
        .map { token -> text.indexOf(token, start).let { token to it } }
        .filter { (_, index) -> index >= 0 }
        .minByOrNull { (_, index) -> index }

    val end = if (endTokenStart != null) {
        endTokenStart.second + endTokenStart.first.length
    } else {
        text.length
    }
    val raw = text.substring(start, end)
    return SpecialBlock(
        start = start,
        end = end,
        block = MessageTextBlock.Protocol(
            kind = ProtocolKind.ToolResult,
            raw = raw,
            success = parseToolResultSuccess(raw),
        )
    )
}

private fun findRichRootBlock(text: String, startIndex: Int, streaming: Boolean): SpecialBlock? {
    var searchIndex = startIndex
    while (searchIndex < text.length) {
        val root = RichHtmlRootDetector.findNextCandidate(text, searchIndex) ?: return null
        val start = findAdjacentLeadingStyleStart(text, root.start, startIndex)
        val startTagEnd = root.startTagEnd
        if (startTagEnd < 0) {
            if (!streaming) return null
            return SpecialBlock(
                start = start,
                end = text.length,
                block = MessageTextBlock.VcpHtml(
                    html = text.substring(start),
                    partial = true,
                    executable = false,
                )
            )
        }

        val end = RichHtmlRootDetector.findMatchingElementEnd(text, startTagEnd + 1, root.tagName)
        if (end != null) {
            val html = text.substring(start, end)
            return SpecialBlock(
                start = start,
                end = end,
                block = MessageTextBlock.VcpHtml(
                    html = html,
                    partial = false,
                    executable = hasExecutableHtml(html),
                )
            )
        }

        if (streaming) {
            val html = text.substring(start)
            return SpecialBlock(
                start = start,
                end = text.length,
                block = MessageTextBlock.VcpHtml(
                    html = html,
                    partial = true,
                    executable = hasExecutableHtml(html),
                )
            )
        }

        searchIndex = root.start + 1
    }
    return null
}

private fun findAdjacentLeadingStyleStart(text: String, rootStart: Int, lowerBound: Int): Int {
    var blockStart = rootStart
    while (true) {
        val beforeStyleEnd = skipWhitespaceBackward(text, blockStart, lowerBound)
        val closeStart = text.lastIndexOf("</style", beforeStyleEnd - 1, ignoreCase = true)
        if (closeStart < lowerBound) return blockStart

        val closeEnd = RichHtmlRootDetector.findTagEnd(text, closeStart) ?: return blockStart
        if (closeEnd + 1 != beforeStyleEnd) return blockStart

        val openStart = text.lastIndexOf("<style", closeStart, ignoreCase = true)
        if (openStart < lowerBound) return blockStart

        blockStart = openStart
    }
}

private fun skipWhitespaceBackward(text: String, fromExclusive: Int, lowerBound: Int): Int {
    var cursor = fromExclusive
    while (cursor > lowerBound && text[cursor - 1].isWhitespace()) {
        cursor -= 1
    }
    return cursor
}

private fun hasExecutableHtml(html: String): Boolean {
    return EXECUTABLE_HTML_PATTERNS.any { it.containsMatchIn(html) }
}

private fun parseToolResultSuccess(raw: String): Boolean? {
    return when {
        TOOL_RESULT_SUCCESS.containsMatchIn(raw) -> true
        TOOL_RESULT_FAILURE.containsMatchIn(raw) -> false
        else -> null
    }
}

private data class MarkdownFence(
    val marker: Char,
    val length: Int,
    val protectsContent: Boolean,
)

private fun isInsidePlainMarkdownFence(text: String, index: Int): Boolean {
    var cursor = 0
    var openFence: MarkdownFence? = null

    while (cursor < index && cursor < text.length) {
        val lineEnd = text.indexOf('\n', cursor).let { if (it >= 0) it else text.length }
        val line = text.substring(cursor, lineEnd).trimStart()
        val match = MARKDOWN_FENCE_LINE.find(line)
        if (match != null) {
            val marker = match.groupValues[1]
            val fence = openFence
            if (fence == null) {
                val info = match.groupValues.getOrNull(2).orEmpty().trim()
                val language = info.substringBefore(' ').substringBefore('\t')
                openFence = MarkdownFence(
                    marker = marker.first(),
                    length = marker.length,
                    protectsContent = !language.equals("VCPToolCall", ignoreCase = true),
                )
            } else if (marker.first() == fence.marker && marker.length >= fence.length) {
                openFence = null
            }
        }

        cursor = if (lineEnd < text.length) lineEnd + 1 else text.length
    }

    return openFence?.protectsContent == true
}

private val TOOL_FENCE_START = Regex("""(?m)^```\s*VCPToolCall\s*\r?\n""")
private val TOOL_FENCE_END = Regex("""(?m)^```\s*$""")
private val MARKDOWN_FENCE_LINE = Regex("""^(`{3,}|~{3,})(.*)$""")
private val EXECUTABLE_HTML_PATTERNS = listOf(
    Regex("""<script\b""", RegexOption.IGNORE_CASE),
    Regex("""\shref\s*=\s*(['"])\s*javascript:""", RegexOption.IGNORE_CASE),
)
private val TOOL_RESULT_SUCCESS = Regex("""(✅\s*SUCCESS|执行状态:\s*✅?\s*SUCCESS|\bSUCCESS\b)""", RegexOption.IGNORE_CASE)
private val TOOL_RESULT_FAILURE = Regex("""(❌|执行状态:\s*(FAILED|FAILURE|ERROR)|\b(FAILED|FAILURE|ERROR)\b)""", RegexOption.IGNORE_CASE)
private val messageTextBlockCache = RenderLruCache<String, List<MessageTextBlock>>(maxEntries = 192)
