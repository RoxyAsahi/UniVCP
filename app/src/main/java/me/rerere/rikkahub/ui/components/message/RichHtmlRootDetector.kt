package me.rerere.rikkahub.ui.components.message

internal data class RichHtmlRootCandidate(
    val tagName: String,
    val start: Int,
    val startTagEnd: Int,
)

internal object RichHtmlRootDetector {
    const val MAX_SCAN_CHARS = 256_000
    const val MAX_MATCH_DEPTH = 128

    fun findNextCandidate(text: String, startIndex: Int): RichHtmlRootCandidate? {
        var cursor = startIndex
        while (cursor < text.length) {
            val nextTag = text.indexOf('<', cursor)
            if (nextTag < 0) return null

            when {
                text.startsWith("<!--", nextTag) -> {
                    cursor = text.indexOf("-->", nextTag + 4).let { if (it >= 0) it + 3 else text.length }
                }

                text.regionMatches(nextTag, "<script", 0, "<script".length, ignoreCase = true) &&
                    isTagBoundary(text.getOrNull(nextTag + "<script".length)) -> {
                    cursor = skipElementContent(text, nextTag, "script")
                }

                text.regionMatches(nextTag, "<style", 0, "<style".length, ignoreCase = true) &&
                    isTagBoundary(text.getOrNull(nextTag + "<style".length)) -> {
                    cursor = skipElementContent(text, nextTag, "style")
                }

                else -> {
                    val match = ROOT_TAG_OPEN.find(text, nextTag)
                    if (match?.range?.first == nextTag) {
                        val tagName = match.groupValues[1].lowercase()
                        val startTagEnd = findTagEnd(text, nextTag)
                        if (startTagEnd == null) {
                            return RichHtmlRootCandidate(tagName = tagName, start = nextTag, startTagEnd = -1)
                        }
                        val attributes = text.substring(match.range.last + 1, startTagEnd)
                        if (isRichRoot(tagName, attributes) ||
                            isRichRootStyledByAdjacentStylesheet(text, nextTag, tagName, attributes)
                        ) {
                            return RichHtmlRootCandidate(
                                tagName = tagName,
                                start = nextTag,
                                startTagEnd = startTagEnd,
                            )
                        }
                        cursor = startTagEnd + 1
                    } else if (isPlausibleTagStart(text, nextTag)) {
                        cursor = findTagEnd(text, nextTag)?.plus(1) ?: (nextTag + 1)
                    } else {
                        cursor = nextTag + 1
                    }
                }
            }
        }
        return null
    }

    fun isRichRoot(tagName: String, attributes: String): Boolean {
        if (tagName.equals("details", ignoreCase = true)) return true
        if (tagName.lowercase() !in VISUAL_ROOT_TAGS) return false

        val id = extractAttribute(attributes, "id")
        if (id != null && RICH_ROOT_ID.matches(id)) return true

        val style = extractAttribute(attributes, "style")
        val className = extractAttribute(attributes, "class")
        if (style.isNullOrBlank()) {
            return className != null && VISUAL_CLASS_HINTS.any { it.containsMatchIn(className) }
        }

        if (className.isNullOrBlank()) {
            return hasStrongStandaloneVisualStyle(style)
        }

        return VISUAL_STYLE_HINTS.any { it.containsMatchIn(style) } ||
            VISUAL_CLASS_HINTS.any { it.containsMatchIn(className) }
    }

    fun findMatchingElementEnd(text: String, startIndex: Int, tagName: String): Int? {
        var cursor = startIndex
        var depth = 1
        val openPrefix = "<$tagName"
        val closePrefix = "</$tagName"

        while (cursor < text.length) {
            if (cursor - startIndex > MAX_SCAN_CHARS) return null
            val nextTag = text.indexOf('<', cursor)
            if (nextTag < 0) return null
            if (nextTag - startIndex > MAX_SCAN_CHARS) return null

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

                text.regionMatches(nextTag, openPrefix, 0, openPrefix.length, ignoreCase = true) &&
                    isTagBoundary(text.getOrNull(nextTag + openPrefix.length)) -> {
                    val tagEnd = findTagEnd(text, nextTag) ?: return null
                    if (!isSelfClosingTag(text, nextTag, tagEnd)) {
                        depth += 1
                        if (depth > MAX_MATCH_DEPTH) return null
                    }
                    cursor = tagEnd + 1
                }

                text.regionMatches(nextTag, closePrefix, 0, closePrefix.length, ignoreCase = true) &&
                    isTagBoundary(text.getOrNull(nextTag + closePrefix.length)) -> {
                    val closeEnd = findTagEnd(text, nextTag) ?: return null
                    depth -= 1
                    cursor = closeEnd + 1
                    if (depth == 0) return cursor
                }

                else -> {
                    if (!isPlausibleTagStart(text, nextTag)) {
                        cursor = nextTag + 1
                        continue
                    }
                    cursor = findTagEnd(text, nextTag)?.plus(1) ?: (nextTag + 1)
                }
            }
        }
        return null
    }

    fun canAutoCloseElementAtEof(text: String, startIndex: Int, tagName: String): Boolean {
        var cursor = startIndex
        var depth = 1
        val openPrefix = "<$tagName"
        val closePrefix = "</$tagName"

        while (cursor < text.length) {
            if (cursor - startIndex > MAX_SCAN_CHARS) return false
            val nextTag = text.indexOf('<', cursor)
            if (nextTag < 0) return depth > 0
            if (nextTag - startIndex > MAX_SCAN_CHARS) return false

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

                text.regionMatches(nextTag, openPrefix, 0, openPrefix.length, ignoreCase = true) &&
                    isTagBoundary(text.getOrNull(nextTag + openPrefix.length)) -> {
                    val tagEnd = findTagEnd(text, nextTag) ?: return false
                    if (!isSelfClosingTag(text, nextTag, tagEnd)) {
                        depth += 1
                        if (depth > MAX_MATCH_DEPTH) return false
                    }
                    cursor = tagEnd + 1
                }

                text.regionMatches(nextTag, closePrefix, 0, closePrefix.length, ignoreCase = true) &&
                    isTagBoundary(text.getOrNull(nextTag + closePrefix.length)) -> {
                    val closeEnd = findTagEnd(text, nextTag) ?: return false
                    depth -= 1
                    cursor = closeEnd + 1
                    if (depth == 0) return false
                }

                else -> {
                    if (!isPlausibleTagStart(text, nextTag)) {
                        cursor = nextTag + 1
                        continue
                    }
                    cursor = findTagEnd(text, nextTag)?.plus(1) ?: return false
                }
            }
        }
        return depth > 0
    }

    fun findTagEnd(text: String, tagStart: Int): Int? {
        var quote: Char? = null
        var cursor = tagStart
        while (cursor < text.length) {
            val char = text[cursor]
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '>' -> return cursor
            }
            cursor += 1
        }
        return null
    }

    private fun extractAttribute(attributes: String, name: String): String? {
        val match = Regex("""(?i)(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>/]+))""")
            .find(attributes)
            ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun isRichRootStyledByAdjacentStylesheet(
        text: String,
        tagStart: Int,
        tagName: String,
        attributes: String,
    ): Boolean {
        if (tagName.lowercase() !in VISUAL_ROOT_TAGS) return false
        val stylesheet = adjacentLeadingStylesheet(text, tagStart) ?: return false
        val selectors = buildList {
            extractAttribute(attributes, "id")?.takeIf { it.isNotBlank() }?.let { add("#$it") }
            extractAttribute(attributes, "class")
                ?.split(Regex("""\s+"""))
                ?.filter { it.isNotBlank() }
                ?.forEach { add(".$it") }
        }
        if (selectors.isEmpty()) return false

        return selectors.any { selector ->
            stylesheetHasVisualRuleForSelector(stylesheet, selector)
        }
    }

    private fun adjacentLeadingStylesheet(text: String, tagStart: Int): String? {
        val beforeStyleEnd = skipWhitespaceBackward(text, tagStart, 0)
        val closeStart = text.lastIndexOf("</style", beforeStyleEnd - 1, ignoreCase = true)
        if (closeStart < 0) return null
        val closeEnd = findTagEnd(text, closeStart) ?: return null
        if (closeEnd + 1 != beforeStyleEnd) return null
        val openStart = text.lastIndexOf("<style", closeStart, ignoreCase = true)
        if (openStart < 0) return null
        val openEnd = findTagEnd(text, openStart) ?: return null
        return text.substring(openEnd + 1, closeStart)
    }

    private fun skipWhitespaceBackward(text: String, fromExclusive: Int, lowerBound: Int): Int {
        var cursor = fromExclusive
        while (cursor > lowerBound && text[cursor - 1].isWhitespace()) {
            cursor -= 1
        }
        return cursor
    }

    private fun stylesheetHasVisualRuleForSelector(stylesheet: String, selector: String): Boolean {
        val escapedSelector = Regex.escape(selector)
        val selectorRule = Regex("""(?s)(^|[},])\s*([^{}]*$escapedSelector[^{}]*)\{([^{}]*)\}""")
        return selectorRule.findAll(stylesheet).any { match ->
            val selectorList = match.groupValues[2]
            val declarations = match.groupValues[3]
            selectorList.split(",").any { selectorPart ->
                selectorPart.trim().contains(selector)
            } && hasStrongStandaloneVisualStyle(declarations)
        }
    }

    private fun skipElementContent(text: String, tagStart: Int, tagName: String): Int {
        val openEnd = findTagEnd(text, tagStart) ?: return text.length
        val closeStart = text.indexOf("</$tagName", openEnd + 1, ignoreCase = true)
        if (closeStart < 0) return text.length
        val closeEnd = findTagEnd(text, closeStart) ?: return text.length
        return closeEnd + 1
    }

    private fun isSelfClosingTag(text: String, tagStart: Int, tagEnd: Int): Boolean {
        var cursor = tagEnd - 1
        while (cursor > tagStart && text[cursor].isWhitespace()) {
            cursor -= 1
        }
        return cursor > tagStart && text[cursor] == '/'
    }

    private fun isTagBoundary(char: Char?): Boolean {
        return char == null || char.isWhitespace() || char == '>' || char == '/'
    }

    private fun isPlausibleTagStart(text: String, index: Int): Boolean {
        if (text.getOrNull(index) != '<') return false
        val next = text.getOrNull(index + 1) ?: return false
        if (next.isLetter() || next == '!' || next == '?') return true
        return next == '/' && text.getOrNull(index + 2)?.isLetter() == true
    }

    private val ROOT_TAG_OPEN = Regex("""<\s*(div|section|article|main|aside|header|footer|details)\b""", RegexOption.IGNORE_CASE)
    private val RICH_ROOT_ID = Regex("""(?:vcp-root|response-root|vcp-[a-z0-9_-]+-widget)""", RegexOption.IGNORE_CASE)
    private val VISUAL_ROOT_TAGS = setOf("div", "section", "article", "main", "aside", "header", "footer")
    private val VISUAL_STYLE_HINTS = listOf(
        Regex("""(?:^|;)\s*(background|background-color|border|border-radius|padding|display|font-family|color|width|min-height|box-shadow)\s*:""", RegexOption.IGNORE_CASE),
    )
    private val STANDALONE_STYLE_CATEGORIES = listOf(
        Regex("""(?:^|;)\s*(background|background-color|background-image|box-shadow)\s*:""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|;)\s*(border|border-radius|border-left|border-top|border-right|border-bottom)\s*:""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|;)\s*(padding|padding-left|padding-top|padding-right|padding-bottom)\s*:""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|;)\s*(display|grid-template-columns|flex-direction|justify-content|align-items|gap)\s*:""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|;)\s*(width|min-width|max-width|height|min-height|max-height)\s*:""", RegexOption.IGNORE_CASE),
    )
    private val VISUAL_CLASS_HINTS = listOf(
        Regex("""\b(?:vcp|math|card|widget|panel|badge|tag|block)\b""", RegexOption.IGNORE_CASE),
    )

    private fun hasStrongStandaloneVisualStyle(style: String): Boolean {
        val matchedCategories = STANDALONE_STYLE_CATEGORIES.count { it.containsMatchIn(style) }
        val hasSurfacePaint = STANDALONE_STYLE_CATEGORIES.take(2).any { it.containsMatchIn(style) }
        return hasSurfacePaint && matchedCategories >= 2
    }
}
