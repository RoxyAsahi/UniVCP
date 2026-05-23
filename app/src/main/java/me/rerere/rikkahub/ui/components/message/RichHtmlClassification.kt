package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey

internal enum class RichHtmlRenderKind {
    NativeStatic,
    InteractiveStatic,
    ComplexDynamic,
}

internal enum class NativeConfidence {
    High,
    Medium,
    WebViewFallback,
    DynamicPreview,
}

internal data class RichHtmlAnalysis(
    val kind: RichHtmlRenderKind,
    val previewText: String,
    val nativeConfidence: NativeConfidence,
)

internal fun analyzeRichHtml(html: String): RichHtmlAnalysis {
    return richHtmlAnalysisCache.getOrPut(renderTextCacheKey(html)) {
        analyzeRichHtmlUncached(html)
    }
}

private fun analyzeRichHtmlUncached(html: String): RichHtmlAnalysis {
    val previewText = html
        .replace(SCRIPT_OR_STYLE_BLOCK, " ")
        .replace(TAG_BLOCK, " ")
        .replace(HTML_ENTITY_NBSP, " ")
        .replace(WHITESPACE_BLOCK, " ")
        .trim()
        .take(140)

    val dynamicReason = COMPLEX_DYNAMIC_PATTERNS.firstOrNull { it.pattern.containsMatchIn(html) }?.reason
        ?: fastBudgetReason(html)
    if (dynamicReason != null) {
        RichHtmlRenderTelemetry.recordFallback(
            stage = RichHtmlFallbackStage.Classification,
            reason = dynamicReason,
        )
        return RichHtmlAnalysis(
            kind = RichHtmlRenderKind.ComplexDynamic,
            previewText = previewText,
            nativeConfidence = NativeConfidence.DynamicPreview,
        )
    }

    val kind = if (INTERACTIVE_STATIC_PATTERNS.any { it.containsMatchIn(html) }) {
        RichHtmlRenderKind.InteractiveStatic
    } else {
        RichHtmlRenderKind.NativeStatic
    }

    return RichHtmlAnalysis(
        kind = kind,
        previewText = previewText,
        nativeConfidence = estimateNativeConfidence(html, kind),
    )
}

private fun estimateNativeConfidence(html: String, kind: RichHtmlRenderKind): NativeConfidence {
    if (kind == RichHtmlRenderKind.ComplexDynamic) return NativeConfidence.DynamicPreview
    val tagCount = TAG_BLOCK.findAll(html).count()
    val hasStyle = Regex("""\bstyle\s*=|<\s*style\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)
    val hasRichLayout = Regex("""\b(display\s*:\s*(flex|grid)|grid-template-columns|linear-gradient|box-shadow|<\s*(table|img|svg|details)\b)""", RegexOption.IGNORE_CASE)
        .containsMatchIn(html)
    return when {
        tagCount > 500 -> NativeConfidence.WebViewFallback
        hasStyle || hasRichLayout -> NativeConfidence.Medium
        else -> NativeConfidence.High
    }
}

private fun fastBudgetReason(html: String): String? {
    val budget = RichHtmlBudget()
    val tagCount = TAG_BLOCK.findAll(html).count()
    if (tagCount > budget.maxNodes) return RichHtmlSafetyReason.NodeBudget.name
    if (maxApproxElementDepth(html) > budget.maxDepth) return RichHtmlSafetyReason.DepthBudget.name
    val textChars = html
        .replace(SCRIPT_OR_STYLE_BLOCK, " ")
        .replace(TAG_BLOCK, "")
        .length
    if (textChars > budget.maxTextChars) return RichHtmlSafetyReason.TextBudget.name
    val tableCellCount = TABLE_CELL_TAG.findAll(html).count()
    if (tableCellCount > budget.maxTableCells) return RichHtmlSafetyReason.TableBudget.name
    val svgCommandCount = SVG_COMMAND_TAG.findAll(html).count()
    if (svgCommandCount > budget.maxSvgCommands) return RichHtmlSafetyReason.SvgBudget.name
    val svgPathChars = SVG_PATH_D_ATTR.findAll(html).sumOf { match ->
        match.groupValues.getOrNull(2)?.length ?: 0
    }
    if (svgPathChars > budget.maxSvgPathChars) return RichHtmlSafetyReason.SvgBudget.name
    return null
}

private fun maxApproxElementDepth(html: String): Int {
    var depth = 0
    var maxDepth = 0
    TAG_BLOCK.findAll(html).forEach { match ->
        val tag = match.value
        val tagName = tagNameFromTag(tag) ?: return@forEach
        if (tagName in VOID_HTML_TAGS || tag.endsWith("/>")) return@forEach
        if (tag.startsWith("</")) {
            depth = (depth - 1).coerceAtLeast(0)
        } else if (!tag.startsWith("<!")) {
            depth += 1
            maxDepth = maxOf(maxDepth, depth)
        }
    }
    return maxDepth
}

private fun tagNameFromTag(tag: String): String? {
    val start = if (tag.startsWith("</")) 2 else 1
    var cursor = start
    while (cursor < tag.length && tag[cursor].isWhitespace()) cursor += 1
    val nameStart = cursor
    while (cursor < tag.length && (tag[cursor].isLetterOrDigit() || tag[cursor] == '-' || tag[cursor] == ':')) {
        cursor += 1
    }
    return tag.substring(nameStart, cursor).lowercase().takeIf { it.isNotBlank() }
}

internal fun extractInputActionFromElement(
    dataSend: String,
    dataInput: String,
    @Suppress("UNUSED_PARAMETER")
    value: String,
    onclick: String,
    @Suppress("UNUSED_PARAMETER")
    fallbackText: String,
): String {
    return dataSend.takeIf { it.isNotBlank() }
        ?: dataInput.takeIf { it.isNotBlank() }
        ?: INPUT_CALL_REGEX.find(onclick)?.groupValues?.getOrNull(2)?.let(::decodeJsStringLiteral)
        ?: ""
}

private fun decodeJsStringLiteral(value: String): String {
    return value
        .replace("""\\'""", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}

private data class DynamicPattern(
    val reason: String,
    val pattern: Regex,
)

private val COMPLEX_DYNAMIC_PATTERNS = listOf(
    DynamicPattern("ScriptTag", Regex("""<\s*script\b""", RegexOption.IGNORE_CASE)),
    DynamicPattern("RuntimeMediaOrEmbed", Regex("""<\s*(canvas|video|audio|iframe|object|embed)\b""", RegexOption.IGNORE_CASE)),
    DynamicPattern("RequestAnimationFrame", Regex("""\brequestAnimationFrame\s*\(""", RegexOption.IGNORE_CASE)),
    DynamicPattern("SetInterval", Regex("""\bsetInterval\s*\(""", RegexOption.IGNORE_CASE)),
    DynamicPattern("ThreeJs", Regex("""\bTHREE\s*\.""", RegexOption.IGNORE_CASE)),
    DynamicPattern("WebGlRenderer", Regex("""\bWebGLRenderer\b""", RegexOption.IGNORE_CASE)),
    DynamicPattern("MermaidRuntime", Regex("""\bmermaid\s*\.""", RegexOption.IGNORE_CASE)),
    DynamicPattern("JavascriptHref", Regex("""\shref\s*=\s*(['"])\s*javascript:""", RegexOption.IGNORE_CASE)),
    DynamicPattern("JavascriptImageSource", Regex("""\ssrc\s*=\s*(['"])\s*javascript:""", RegexOption.IGNORE_CASE)),
)

private val INTERACTIVE_STATIC_PATTERNS = listOf(
    Regex("""\bdata-(send|input)\s*=""", RegexOption.IGNORE_CASE),
    Regex("""\bonclick\s*=\s*(['"])[\s\S]*?\binput\s*\(""", RegexOption.IGNORE_CASE),
)

private val INPUT_CALL_REGEX = Regex("""\binput\s*\(\s*(['"])([\s\S]*?)\1\s*\)""", RegexOption.IGNORE_CASE)
private val SCRIPT_OR_STYLE_BLOCK = Regex("""<\s*(script|style)\b[\s\S]*?<\s*/\s*\1\s*>""", RegexOption.IGNORE_CASE)
private val TAG_BLOCK = Regex("""<[^>]+>""")
private val TABLE_CELL_TAG = Regex("""<\s*(td|th)\b""", RegexOption.IGNORE_CASE)
private val SVG_COMMAND_TAG = Regex("""<\s*(path|rect|circle|ellipse|line|polyline|polygon|text)\b""", RegexOption.IGNORE_CASE)
private val SVG_PATH_D_ATTR = Regex("""<\s*path\b[^>]*\sd\s*=\s*(['"])([\s\S]*?)\1""", RegexOption.IGNORE_CASE)
private val HTML_ENTITY_NBSP = Regex("""&nbsp;|&#160;""", RegexOption.IGNORE_CASE)
private val WHITESPACE_BLOCK = Regex("""\s+""")
private val VOID_HTML_TAGS = setOf(
    "area",
    "base",
    "br",
    "col",
    "embed",
    "hr",
    "img",
    "input",
    "link",
    "meta",
    "param",
    "source",
    "track",
    "wbr",
)
private val richHtmlAnalysisCache = RenderLruCache<String, RichHtmlAnalysis>(maxEntries = 256)
