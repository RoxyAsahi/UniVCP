package me.rerere.rikkahub.ui.components.message

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
    return listOfNotNull(
        findMetaThinkingBlock(text, startIndex),
        findToolFenceBlock(text, startIndex),
        findToolRequestBlock(text, startIndex),
        findToolResultBlock(text, startIndex),
        findVcpRootBlock(text, startIndex, streaming),
    ).minByOrNull { it.start }
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

private fun findVcpRootBlock(text: String, startIndex: Int, streaming: Boolean): SpecialBlock? {
    var searchIndex = startIndex
    while (searchIndex < text.length) {
        val startMatch = VCP_ROOT_START.find(text, searchIndex) ?: return null
        val start = startMatch.range.first
        val startTagEnd = text.indexOf('>', startMatch.range.last)
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

        val end = findMatchingDivEnd(text, startTagEnd + 1)
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

        searchIndex = startMatch.range.last + 1
    }
    return null
}

private fun findMatchingDivEnd(text: String, startIndex: Int): Int? {
    var cursor = startIndex
    var depth = 1

    while (cursor < text.length) {
        val nextTag = text.indexOf('<', cursor)
        if (nextTag < 0) return null

        when {
            text.startsWith("<!--", nextTag) -> {
                cursor = text.indexOf("-->", nextTag + 4).let { if (it >= 0) it + 3 else text.length }
            }

            text.regionMatches(nextTag, "<script", 0, "<script".length, ignoreCase = true) -> {
                cursor = skipElementContent(text, nextTag, "script")
            }

            text.regionMatches(nextTag, "<style", 0, "<style".length, ignoreCase = true) -> {
                cursor = skipElementContent(text, nextTag, "style")
            }

            text.regionMatches(nextTag, "<div", 0, "<div".length, ignoreCase = true) &&
                isTagBoundary(text.getOrNull(nextTag + "<div".length)) -> {
                depth += 1
                cursor = text.indexOf('>', nextTag + 4).let { if (it >= 0) it + 1 else text.length }
            }

            text.regionMatches(nextTag, "</div", 0, "</div".length, ignoreCase = true) &&
                isTagBoundary(text.getOrNull(nextTag + "</div".length)) -> {
                val closeEnd = text.indexOf('>', nextTag + 5)
                if (closeEnd < 0) return null
                depth -= 1
                cursor = closeEnd + 1
                if (depth == 0) return cursor
            }

            else -> {
                cursor = nextTag + 1
            }
        }
    }
    return null
}

private fun skipElementContent(text: String, tagStart: Int, tagName: String): Int {
    val openEnd = text.indexOf('>', tagStart)
    if (openEnd < 0) return text.length
    val closeStart = text.indexOf("</$tagName", openEnd + 1, ignoreCase = true)
    if (closeStart < 0) return text.length
    val closeEnd = text.indexOf('>', closeStart)
    return if (closeEnd >= 0) closeEnd + 1 else text.length
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

private fun isTagBoundary(char: Char?): Boolean {
    return char == null || char.isWhitespace() || char == '>' || char == '/'
}

private val TOOL_FENCE_START = Regex("""(?m)^```\s*VCPToolCall\s*\r?\n""")
private val TOOL_FENCE_END = Regex("""(?m)^```\s*$""")
private val VCP_ROOT_START = Regex("""<div\b(?=[^>]*\bid\s*=\s*(['"])vcp-root\1)[^>]*>""", RegexOption.IGNORE_CASE)
private val EXECUTABLE_HTML_PATTERNS = listOf(
    Regex("""<script\b""", RegexOption.IGNORE_CASE),
    Regex("""\shref\s*=\s*(['"])\s*javascript:""", RegexOption.IGNORE_CASE),
)
private val TOOL_RESULT_SUCCESS = Regex("""(✅\s*SUCCESS|执行状态:\s*✅?\s*SUCCESS|\bSUCCESS\b)""", RegexOption.IGNORE_CASE)
private val TOOL_RESULT_FAILURE = Regex("""(❌|执行状态:\s*(FAILED|FAILURE|ERROR)|\b(FAILED|FAILURE|ERROR)\b)""", RegexOption.IGNORE_CASE)
