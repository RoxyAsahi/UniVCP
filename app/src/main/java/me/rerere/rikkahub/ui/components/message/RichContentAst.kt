package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal data class RichContentAst(
    val id: String,
    val root: RichContentNode,
    val stats: RichContentAstStats,
)

internal sealed interface RichContentNode {
    val stablePath: String
    val sourceKind: RichContentSourceKind

    data class Document(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val children: List<RichContentNode>,
    ) : RichContentNode

    data class Container(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val tagName: String,
        val children: List<RichContentNode>,
        val textFlowCandidate: Boolean,
        val blockedTextFlow: Boolean,
    ) : RichContentNode

    data class Paragraph(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val children: List<RichContentNode>,
        val textFlowCandidate: Boolean,
        val blockedTextFlow: Boolean,
    ) : RichContentNode

    data class TextRun(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val text: String,
    ) : RichContentNode

    data class StyledTextRun(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val text: String,
        val styleTags: Set<String>,
    ) : RichContentNode

    data class LinkRun(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val text: String,
        val safeHref: Boolean,
    ) : RichContentNode

    data class InlineCodeRun(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val text: String,
    ) : RichContentNode

    data class ListBlock(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val ordered: Boolean,
        val items: List<ListItem>,
        val textFlowCandidate: Boolean,
        val blockedTextFlow: Boolean,
    ) : RichContentNode

    data class ListItem(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val children: List<RichContentNode>,
        val textFlowCandidate: Boolean,
        val blockedTextFlow: Boolean,
    ) : RichContentNode

    data class ActionButton(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val hasAction: Boolean,
    ) : RichContentNode

    data class Image(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val hasAlt: Boolean,
        val safeSource: Boolean,
    ) : RichContentNode

    data class Svg(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val complex: Boolean,
    ) : RichContentNode

    data class Table(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val rowCount: Int,
        val cellCount: Int,
    ) : RichContentNode

    data class Details(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val children: List<RichContentNode>,
        val open: Boolean,
    ) : RichContentNode

    data class Formula(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val inline: Boolean,
    ) : RichContentNode

    data class BrowserOnly(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val tagName: String,
        val reason: String,
    ) : RichContentNode

    data class Unsupported(
        override val stablePath: String,
        override val sourceKind: RichContentSourceKind,
        val tagName: String,
        val reason: String,
    ) : RichContentNode
}

internal enum class RichContentSourceKind {
    Html,
    Markdown,
    Protocol,
}

internal data class RichContentAstStats(
    val sourceNodeCount: Int,
    val astNodeCount: Int,
    val textRunCount: Int,
    val paragraphCount: Int,
    val textFlowCandidateCount: Int,
    val blockedTextFlowCount: Int,
    val actionCount: Int,
    val mediaCount: Int,
    val tableCount: Int,
    val svgCount: Int,
    val browserOnlyCount: Int,
)

internal fun buildRichContentAstFromHtml(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentAst {
    val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull()
    if (document == null) {
        val root = RichContentNode.Document(
            stablePath = "r0",
            sourceKind = RichContentSourceKind.Html,
            children = listOf(
                RichContentNode.Unsupported(
                    stablePath = "r0/u0",
                    sourceKind = RichContentSourceKind.Html,
                    tagName = "document",
                    reason = "ParseFailure",
                )
            ),
        )
        return RichContentAst(
            id = renderTextCacheKey(html),
            root = root,
            stats = RichContentAstStats(
                sourceNodeCount = 0,
                astNodeCount = 2,
                textRunCount = 0,
                paragraphCount = 0,
                textFlowCandidateCount = 0,
                blockedTextFlowCount = 0,
                actionCount = 0,
                mediaCount = 0,
                tableCount = 0,
                svgCount = 0,
                browserOnlyCount = 0,
            ),
        )
    }
    return buildRichContentAstFromParsedHtml(html, document, analysis)
}

internal fun buildRichContentAstFromParsedHtml(
    html: String,
    document: Document,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentAst {
    val stylesheetRules = document.select("style").flatMap { styleElement ->
        parseStylesheetCapabilityRules(styleElement.data().ifBlank { styleElement.html() })
    }
    val builder = RichContentAstBuilder(analysis, stylesheetRules)
    val children = document.body().childNodes().mapIndexedNotNull { index, node ->
        builder.buildNode(node, "r0/c$index")
    }
    val root = RichContentNode.Document(
        stablePath = "r0",
        sourceKind = RichContentSourceKind.Html,
        children = children,
    )
    return RichContentAst(
        id = renderTextCacheKey(html),
        root = root,
        stats = builder.stats(root, document.body().select("*").size),
    )
}

private class RichContentAstBuilder(
    private val analysis: RichHtmlAnalysis,
    private val stylesheetRules: List<StylesheetCapabilityRule>,
) {
    fun buildNode(node: Node, path: String): RichContentNode? {
        return when (node) {
            is TextNode -> buildTextNode(node, path)
            is Element -> buildElement(node, path)
            else -> null
        }
    }

    private fun buildTextNode(node: TextNode, path: String): RichContentNode? {
        val text = node.wholeText.normalizeAstText()
        if (text.isBlank()) return null
        return RichContentNode.TextRun(
            stablePath = path,
            sourceKind = RichContentSourceKind.Html,
            text = text,
        )
    }

    private fun buildElement(element: Element, path: String): RichContentNode? {
        val tag = element.tagName().lowercase()
        val style = element.capabilityStyle()
        val textFlowCandidate = tag.isTextFlowCandidateTag() || tag.isTextHeavyDiv(element)
        val blocksTextFlow = style.hasTextFlowBlockingStyle() || element.hasTextFlowBlockingDescendant()
        val blockedTextFlow = (textFlowCandidate && blocksTextFlow) || style.hasComplexVisualStyle()
        return when {
            tag == "style" -> RichContentNode.Unsupported(path, RichContentSourceKind.Html, tag, "StyleElement")
            tag in BROWSER_ONLY_TAGS -> RichContentNode.BrowserOnly(path, RichContentSourceKind.Html, tag, "RuntimeElement")
            tag == "button" -> RichContentNode.ActionButton(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                hasAction = element.hasAttr("data-send") ||
                    element.hasAttr("data-input") ||
                    element.hasAttr("onclick") ||
                    element.hasAttr("value"),
            )
            tag == "img" -> RichContentNode.Image(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                hasAlt = element.attr("alt").isNotBlank(),
                safeSource = isSafeRichHtmlImageSource(element.attr("src")),
            )
            tag == "svg" -> RichContentNode.Svg(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                complex = element.select(SVG_COMPLEX_SELECTOR).isNotEmpty(),
            )
            tag == "table" -> RichContentNode.Table(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                rowCount = element.select("tr").size,
                cellCount = element.select("td,th").size,
            )
            tag == "details" -> RichContentNode.Details(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                children = buildChildren(element, path),
                open = element.hasAttr("open"),
            )
            tag == "math" || element.classNames().any { it in FORMULA_CLASS_NAMES } -> RichContentNode.Formula(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                inline = tag != "div",
            )
            tag == "ul" || tag == "ol" -> RichContentNode.ListBlock(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                ordered = tag == "ol",
                items = element.children()
                    .filter { it.tagName().equals("li", ignoreCase = true) }
                    .mapIndexed { index, item ->
                        RichContentNode.ListItem(
                            stablePath = "$path/li$index",
                            sourceKind = RichContentSourceKind.Html,
                            children = buildChildren(item, "$path/li$index"),
                            textFlowCandidate = true,
                            blockedTextFlow = item.capabilityStyle().hasTextFlowBlockingStyle() ||
                                item.hasTextFlowBlockingDescendant(),
                        )
                    },
                textFlowCandidate = true,
                blockedTextFlow = blockedTextFlow || element.hasTextFlowBlockingDescendant(),
            )
            tag == "p" -> RichContentNode.Paragraph(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                children = buildChildren(element, path),
                textFlowCandidate = true,
                blockedTextFlow = blockedTextFlow,
            )
            tag == "code" && element.children().isEmpty() -> RichContentNode.InlineCodeRun(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                text = element.text().normalizeAstText(),
            )
            tag == "a" && element.children().isEmpty() -> RichContentNode.LinkRun(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                text = element.text().normalizeAstText(),
                safeHref = isSafeRichHtmlHref(element.attr("href")),
            )
            tag in STYLED_TEXT_TAGS && element.children().isEmpty() -> RichContentNode.StyledTextRun(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                text = element.text().normalizeAstText(),
                styleTags = setOf(tag),
            )
            else -> RichContentNode.Container(
                stablePath = path,
                sourceKind = RichContentSourceKind.Html,
                tagName = tag,
                children = buildChildren(element, path),
                textFlowCandidate = textFlowCandidate,
                blockedTextFlow = blockedTextFlow,
            )
        }
    }

    private fun buildChildren(element: Element, path: String): List<RichContentNode> {
        return element.childNodes().mapIndexedNotNull { index, child ->
            buildNode(child, "$path/c$index")
        }
    }

    private fun Element.capabilityStyle(): String {
        val matchedStyles = stylesheetRules
            .asSequence()
            .filter { rule -> rule.matches(this) }
            .joinToString(";") { it.declarations }
        val inline = attr("style")
        return listOf(matchedStyles, inline)
            .filter { it.isNotBlank() }
            .joinToString(";")
    }

    private fun Element.hasTextFlowBlockingDescendant(): Boolean {
        return select(TEXT_FLOW_BLOCKING_SELECTOR).isNotEmpty() ||
            select("*").any { it.capabilityStyle().hasTextFlowBlockingStyle() }
    }

    fun stats(root: RichContentNode, sourceNodeCount: Int): RichContentAstStats {
        val nodes = root.flatten()
        return RichContentAstStats(
            sourceNodeCount = sourceNodeCount,
            astNodeCount = nodes.size,
            textRunCount = nodes.count {
                it is RichContentNode.TextRun ||
                    it is RichContentNode.StyledTextRun ||
                    it is RichContentNode.LinkRun ||
                    it is RichContentNode.InlineCodeRun
            },
            paragraphCount = nodes.count { it is RichContentNode.Paragraph },
            textFlowCandidateCount = nodes.count { it.isTextFlowCandidateNode() },
            blockedTextFlowCount = nodes.count { it.isBlockedTextFlowNode() },
            actionCount = nodes.count { it is RichContentNode.ActionButton },
            mediaCount = nodes.count {
                it is RichContentNode.Image ||
                    it is RichContentNode.Svg ||
                    it is RichContentNode.Formula ||
                    it is RichContentNode.BrowserOnly && it.tagName in MEDIA_BROWSER_ONLY_TAGS
            },
            tableCount = nodes.count { it is RichContentNode.Table },
            svgCount = nodes.count { it is RichContentNode.Svg },
            browserOnlyCount = nodes.count { it is RichContentNode.BrowserOnly },
        )
    }
}

private fun RichContentNode.flatten(): List<RichContentNode> {
    return listOf(this) + when (this) {
        is RichContentNode.Document -> children.flatMap { it.flatten() }
        is RichContentNode.Container -> children.flatMap { it.flatten() }
        is RichContentNode.Paragraph -> children.flatMap { it.flatten() }
        is RichContentNode.ListBlock -> items.flatMap { it.flatten() }
        is RichContentNode.ListItem -> children.flatMap { it.flatten() }
        is RichContentNode.Details -> children.flatMap { it.flatten() }
        is RichContentNode.ActionButton,
        is RichContentNode.BrowserOnly,
        is RichContentNode.Formula,
        is RichContentNode.Image,
        is RichContentNode.InlineCodeRun,
        is RichContentNode.LinkRun,
        is RichContentNode.StyledTextRun,
        is RichContentNode.Svg,
        is RichContentNode.Table,
        is RichContentNode.TextRun,
        is RichContentNode.Unsupported -> emptyList()
    }
}

private fun RichContentNode.isTextFlowCandidateNode(): Boolean = when (this) {
    is RichContentNode.Container -> textFlowCandidate
    is RichContentNode.Paragraph -> textFlowCandidate
    is RichContentNode.ListBlock -> textFlowCandidate
    is RichContentNode.ListItem -> textFlowCandidate
    is RichContentNode.StyledTextRun,
    is RichContentNode.LinkRun,
    is RichContentNode.InlineCodeRun -> true
    else -> false
}

private fun RichContentNode.isBlockedTextFlowNode(): Boolean = when (this) {
    is RichContentNode.Container -> blockedTextFlow
    is RichContentNode.Paragraph -> blockedTextFlow
    is RichContentNode.ListBlock -> blockedTextFlow
    is RichContentNode.ListItem -> blockedTextFlow
    is RichContentNode.Svg -> complex
    is RichContentNode.BrowserOnly -> true
    else -> false
}

private fun String.normalizeAstText(): String = trim().replace(Regex("""\s+"""), " ")

private fun String.hasTextFlowBlockingStyle(): Boolean = TEXT_FLOW_BLOCKING_STYLE.containsMatchIn(this)

private fun String.hasComplexVisualStyle(): Boolean = COMPLEX_VISUAL_STYLE.containsMatchIn(this)

private fun String.isTextFlowCandidateTag(): Boolean = this in TEXT_FLOW_TAGS

private fun String.isTextHeavyDiv(element: Element): Boolean {
    return this == "div" && element.children().none { child ->
        child.tagName().lowercase() in TEXT_FLOW_BLOCKING_TAGS
    } && element.text().normalizeAstText().length >= 24
}

private data class StylesheetCapabilityRule(
    val selector: String,
    val declarations: String,
) {
    fun matches(element: Element): Boolean = runCatching {
        selector.isNotBlank() && element.`is`(selector)
    }.getOrDefault(false)
}

private fun parseStylesheetCapabilityRules(css: String): List<StylesheetCapabilityRule> {
    if (css.isBlank()) return emptyList()
    return CSS_RULE.findAll(css.removeCssComments()).flatMap { match ->
        val selector = match.groupValues.getOrNull(1).orEmpty()
        val declarations = match.groupValues.getOrNull(2).orEmpty()
        if (declarations.isBlank()) {
            emptySequence()
        } else {
            selector.split(",")
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("@") }
                .map { StylesheetCapabilityRule(selector = it, declarations = declarations) }
        }
    }.toList()
}

private fun String.removeCssComments(): String = replace(CSS_COMMENT, "")

private val BROWSER_ONLY_TAGS = setOf("script", "canvas", "iframe", "video", "audio", "object", "embed")
private val MEDIA_BROWSER_ONLY_TAGS = setOf("video", "audio", "object", "embed", "canvas")
private val TEXT_FLOW_TAGS = setOf("p", "span", "b", "strong", "i", "em", "u", "code", "a", "li")
private val STYLED_TEXT_TAGS = setOf("span", "b", "strong", "i", "em", "u", "s", "del")
private val TEXT_FLOW_BLOCKING_TAGS = setOf(
    "button",
    "img",
    "svg",
    "table",
    "details",
    "script",
    "canvas",
    "iframe",
    "video",
    "audio",
    "object",
    "embed",
)
private val FORMULA_CLASS_NAMES = setOf("math", "katex", "tex", "latex")
private const val SVG_COMPLEX_SELECTOR = "defs,use,symbol,filter,mask,foreignObject,clipPath,pattern,marker"
private const val TEXT_FLOW_BLOCKING_SELECTOR = "button,img,svg,table,details,script,canvas,iframe,video,audio,object,embed"
private val TEXT_FLOW_BLOCKING_STYLE = Regex(
    """\b(display\s*:\s*(flex|grid|inline-flex|inline-grid)|position\s*:\s*(absolute|fixed|sticky)|background(?:-image)?\s*:|box-shadow\s*:|filter\s*:|backdrop-filter\s*:|clip-path\s*:|mask(?:-image)?\s*:|mix-blend-mode\s*:)""",
    RegexOption.IGNORE_CASE,
)
private val COMPLEX_VISUAL_STYLE = Regex(
    """\b(filter\s*:|backdrop-filter\s*:|clip-path\s*:|mask(?:-image)?\s*:|mix-blend-mode\s*:|background(?:-image)?\s*:[^;]*,)""",
    RegexOption.IGNORE_CASE,
)
private val CSS_RULE = Regex("""(?s)([^{}]+)\{([^{}]*)\}""")
private val CSS_COMMENT = Regex("""(?s)/\*.*?\*/""")
