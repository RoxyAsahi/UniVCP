package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class RichHtmlBudget(
    val maxNodes: Int = 900,
    val maxDepth: Int = 48,
    val maxTextChars: Int = 80_000,
    val maxTableCells: Int = 160,
    val maxSvgCommands: Int = 128,
    val maxSvgPathChars: Int = 8_000,
)

internal enum class RichHtmlSafetyReason {
    DangerousTag,
    DangerousAttribute,
    UnsafeUrl,
    NodeBudget,
    DepthBudget,
    TextBudget,
    TableBudget,
    SvgBudget,
    ParseFailure,
}

internal data class RichHtmlSafetyReport(
    val safeForNative: Boolean,
    val reason: RichHtmlSafetyReason? = null,
)

internal fun inspectRichHtmlSafety(
    html: String,
    budget: RichHtmlBudget = RichHtmlBudget(),
): RichHtmlSafetyReport {
    return richHtmlSafetyCache.getOrPut("${renderTextCacheKey(html)}:$budget") {
        inspectRichHtmlSafetyUncached(html = html, budget = budget)
    }
}

private fun inspectRichHtmlSafetyUncached(
    html: String,
    budget: RichHtmlBudget,
): RichHtmlSafetyReport {
    val document = runCatching { Jsoup.parseBodyFragment(html) }
        .getOrElse { return RichHtmlSafetyReport(false, RichHtmlSafetyReason.ParseFailure) }
    val visitor = RichHtmlSafetyVisitor(budget)
    document.body().childNodes().forEach { node ->
        visitor.visit(node, depth = 0)?.let { report ->
            RichHtmlRenderTelemetry.recordFallback(
                stage = RichHtmlFallbackStage.Safety,
                reason = report.reason?.name ?: "Unknown",
            )
            return report
        }
    }
    return RichHtmlSafetyReport(safeForNative = true)
}

internal fun isDangerousRichHtmlTag(tagName: String): Boolean {
    return tagName.lowercase() in DANGEROUS_RICH_HTML_TAGS
}

internal fun isSafeRichHtmlImageSource(src: String): Boolean {
    val value = src.trim()
    if (value.isBlank()) return false
    val normalized = value.lowercase()
    return when {
        normalized.startsWith("https://") -> true
        normalized.startsWith("http://") -> true
        normalized.startsWith("content://") -> true
        normalized.startsWith("file://") -> true
        normalized.startsWith("android.resource://") -> true
        normalized.startsWith("data:image/") -> true
        ":" !in normalized.substringBefore("/", missingDelimiterValue = normalized) -> true
        else -> false
    }
}

internal fun isSafeRichHtmlHref(href: String): Boolean {
    return !href.trim().startsWith("javascript:", ignoreCase = true)
}

internal fun isSafeRichHtmlOnClick(value: String): Boolean {
    return SAFE_INPUT_CALL_REGEX.containsMatchIn(value)
}

private class RichHtmlSafetyVisitor(
    private val budget: RichHtmlBudget,
) {
    private var nodeCount = 0
    private var textChars = 0

    fun visit(node: Node, depth: Int): RichHtmlSafetyReport? {
        if (depth > budget.maxDepth) return RichHtmlSafetyReport(false, RichHtmlSafetyReason.DepthBudget)
        nodeCount += 1
        if (nodeCount > budget.maxNodes) return RichHtmlSafetyReport(false, RichHtmlSafetyReason.NodeBudget)

        return when (node) {
            is TextNode -> {
                textChars += node.wholeText.length
                if (textChars > budget.maxTextChars) {
                    RichHtmlSafetyReport(false, RichHtmlSafetyReason.TextBudget)
                } else {
                    null
                }
            }

            is Element -> visitElement(node, depth)
            else -> null
        }
    }

    private fun visitElement(element: Element, depth: Int): RichHtmlSafetyReport? {
        val tagName = element.tagName().lowercase()
        if (isDangerousRichHtmlTag(tagName)) {
            return RichHtmlSafetyReport(false, RichHtmlSafetyReason.DangerousTag)
        }
        inspectAttributes(element)?.let { return it }
        inspectTable(element, tagName)?.let { return it }
        inspectSvg(element, tagName)?.let { return it }

        if (tagName == "style") return null

        element.childNodes().forEach { child ->
            visit(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun inspectAttributes(element: Element): RichHtmlSafetyReport? {
        val tagName = element.tagName().lowercase()
        for (attribute in element.attributes()) {
            val key = attribute.key.lowercase()
            val value = attribute.value.trim()
            when {
                key == "href" && !isSafeRichHtmlHref(value) -> {
                    return RichHtmlSafetyReport(false, RichHtmlSafetyReason.UnsafeUrl)
                }

                key == "src" && tagName == "img" && !isSafeRichHtmlImageSource(value) -> {
                    return RichHtmlSafetyReport(false, RichHtmlSafetyReason.UnsafeUrl)
                }

                key == "src" && value.startsWith("javascript:", ignoreCase = true) -> {
                    return RichHtmlSafetyReport(false, RichHtmlSafetyReason.UnsafeUrl)
                }
            }
        }
        return null
    }

    private fun inspectTable(element: Element, tagName: String): RichHtmlSafetyReport? {
        if (tagName != "table") return null
        val cellCount = element.select("th,td").size
        return if (cellCount > budget.maxTableCells) {
            RichHtmlSafetyReport(false, RichHtmlSafetyReason.TableBudget)
        } else {
            null
        }
    }

    private fun inspectSvg(element: Element, tagName: String): RichHtmlSafetyReport? {
        if (tagName != "svg") return null
        val commands = element.select("path,rect,circle,ellipse,line,polyline,polygon,text")
        if (commands.size > budget.maxSvgCommands) {
            return RichHtmlSafetyReport(false, RichHtmlSafetyReason.SvgBudget)
        }
        val pathChars = element.select("path").sumOf { it.attr("d").length }
        return if (pathChars > budget.maxSvgPathChars) {
            RichHtmlSafetyReport(false, RichHtmlSafetyReason.SvgBudget)
        } else {
            null
        }
    }
}

private val DANGEROUS_RICH_HTML_TAGS = setOf(
    "script",
    "iframe",
    "object",
    "embed",
    "video",
    "audio",
    "canvas",
)

private val SAFE_INPUT_CALL_REGEX = Regex("""\binput\s*\(\s*(['"])([\s\S]*?)\1\s*\)""", RegexOption.IGNORE_CASE)
private val richHtmlSafetyCache = RenderLruCache<String, RichHtmlSafetyReport>(maxEntries = 256)
