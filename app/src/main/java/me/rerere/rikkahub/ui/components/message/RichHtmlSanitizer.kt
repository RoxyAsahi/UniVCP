package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.richtext.RichMediaKind
import me.rerere.rikkahub.ui.components.richtext.RichMediaLoader
import me.rerere.rikkahub.ui.components.richtext.RichMediaRequest
import me.rerere.rikkahub.ui.components.richtext.RichMediaSafety
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class RichHtmlSanitizerReport(
    val removedTagCount: Int,
    val removedAttributeCount: Int,
    val dangerousProtocolCount: Int,
    val eventHandlerCount: Int,
    val runtimeReason: String?,
    val existingSafetyAgreed: Boolean,
) {
    fun metadataLine(): String = listOf(
        "removedTags=$removedTagCount",
        "removedAttrs=$removedAttributeCount",
        "dangerousProtocols=$dangerousProtocolCount",
        "eventHandlers=$eventHandlerCount",
        "runtimeReason=${runtimeReason.orEmpty()}",
        "existingSafetyAgreed=$existingSafetyAgreed",
    ).joinToString(" ")
}

internal object RichHtmlSanitizer {
    private val allowedTags = setOf(
        "div", "section", "article", "header", "footer", "main", "aside",
        "p", "span", "strong", "b", "em", "i", "u", "s", "del", "ins", "mark", "small",
        "code", "pre", "kbd", "samp", "br", "hr", "a",
        "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
        "img", "svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
        "text", "defs", "clipPath", "mask", "filter", "linearGradient", "radialGradient", "stop", "use",
        "button", "details", "summary", "style",
    ).map { it.lowercase() }.toSet()

    private val globalAllowedAttrs = setOf(
        "id", "class", "style", "title", "role", "aria-label", "aria-hidden",
        "data-input", "data-send", "data-latex", "onclick",
    )

    fun inspect(
        html: String,
        existingSafety: RichHtmlSafetyReport = inspectRichHtmlSafety(html),
    ): RichHtmlSanitizerReport {
        val document = runCatching { Jsoup.parseBodyFragment(html) }
            .getOrElse {
                return RichHtmlSanitizerReport(
                    removedTagCount = 1,
                    removedAttributeCount = 0,
                    dangerousProtocolCount = 0,
                    eventHandlerCount = 0,
                    runtimeReason = RichHtmlSafetyReason.ParseFailure.name,
                    existingSafetyAgreed = existingSafety.safeForNative.not(),
                )
            }
        val visitor = Visitor()
        document.body().childNodes().forEach(visitor::visit)
        val reportUnsafe = visitor.runtimeReason != null ||
            visitor.dangerousProtocolCount > 0 ||
            visitor.eventHandlerCount > 0 ||
            visitor.removedTagCount > 0
        return RichHtmlSanitizerReport(
            removedTagCount = visitor.removedTagCount,
            removedAttributeCount = visitor.removedAttributeCount,
            dangerousProtocolCount = visitor.dangerousProtocolCount,
            eventHandlerCount = visitor.eventHandlerCount,
            runtimeReason = visitor.runtimeReason,
            existingSafetyAgreed = reportUnsafe != existingSafety.safeForNative,
        )
    }

    private class Visitor {
        var removedTagCount = 0
            private set
        var removedAttributeCount = 0
            private set
        var dangerousProtocolCount = 0
            private set
        var eventHandlerCount = 0
            private set
        var runtimeReason: String? = null
            private set

        fun visit(node: Node) {
            when (node) {
                is TextNode -> Unit
                is Element -> visitElement(node)
            }
        }

        private fun visitElement(element: Element) {
            val tag = element.tagName().lowercase()
            if (tag !in allowedTags || isDangerousRichHtmlTag(tag)) {
                removedTagCount += 1
                runtimeReason = runtimeReason ?: when {
                    isDangerousRichHtmlTag(tag) -> RichHtmlSafetyReason.DangerousTag.name
                    else -> "DisallowedTag"
                }
            }
            element.attributes().forEach { attribute ->
                val key = attribute.key.lowercase()
                val value = attribute.value.trim()
                val allowed = key in globalAllowedAttrs ||
                    key.startsWith("aria-") ||
                    key.startsWith("data-") ||
                    key in tagAllowedAttributes(tag)
                if (key.startsWith("on")) {
                    eventHandlerCount += 1
                    if (key != "onclick" || !isSafeRichHtmlOnClick(value)) {
                        runtimeReason = runtimeReason ?: RichHtmlSafetyReason.DangerousAttribute.name
                    }
                }
                if ((key == "href" || key == "src" || key.endsWith("href")) && !isSafeProtocol(value, key, tag)) {
                    dangerousProtocolCount += 1
                    runtimeReason = runtimeReason ?: RichHtmlSafetyReason.UnsafeUrl.name
                }
                if (!allowed) removedAttributeCount += 1
            }
            element.childNodes().forEach(::visit)
        }

        private fun tagAllowedAttributes(tag: String): Set<String> = when (tag) {
            "a" -> setOf("href", "target", "rel")
            "img" -> setOf("src", "alt", "width", "height", "loading")
            "svg" -> setOf("width", "height", "viewbox", "viewBox", "fill", "stroke", "xmlns")
            "path" -> setOf("d", "fill", "stroke", "stroke-width", "transform", "clip-path", "mask", "filter")
            "rect", "circle", "ellipse", "line", "polyline", "polygon" -> setOf(
                "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx", "ry",
                "points", "width", "height", "fill", "stroke", "stroke-width", "transform", "clip-path", "mask", "filter",
            )
            else -> emptySet()
        }

        private fun isSafeProtocol(value: String, key: String, tag: String): Boolean {
            if (key == "src" && tag == "img") {
                val request = RichMediaRequest.fromSource(value, RichMediaKind.Image)
                return RichMediaLoader.safety(request) == RichMediaSafety.Safe
            }
            val lower = value.lowercase()
            return when {
                lower.startsWith("javascript:") -> false
                lower.startsWith("data:") -> lower.startsWith("data:image/")
                ":" !in lower.substringBefore("/", missingDelimiterValue = lower) -> true
                lower.startsWith("https://") || lower.startsWith("http://") -> true
                lower.startsWith("content://") || lower.startsWith("android.resource://") -> true
                else -> false
            }
        }
    }
}
