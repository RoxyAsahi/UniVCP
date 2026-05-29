package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.jsoup.nodes.Document

internal const val RichContentDocumentSchemaVersion: Int = 1

internal data class RichContentDocument(
    val documentId: String,
    val schemaVersion: Int,
    val sourceKind: RichContentSourceKind,
    val root: RichContentNodeV2,
    val imports: RichContentImportDiagnostics,
    val stats: RichContentDocumentStats,
)

internal sealed interface RichContentNodeV2 {
    val nodeId: String
    val stablePath: String
    val subtreeDigest: String
    val capabilities: RichNodeCapabilities
    val kind: RichContentNodeKind
    val children: List<RichContentNodeV2>
}

internal data class RichContentElementNode(
    override val nodeId: String,
    override val stablePath: String,
    override val subtreeDigest: String,
    override val capabilities: RichNodeCapabilities,
    override val kind: RichContentNodeKind,
    override val children: List<RichContentNodeV2> = emptyList(),
    val marks: Set<RichContentMark> = emptySet(),
) : RichContentNodeV2

internal enum class RichContentNodeKind {
    Document,
    BlockContainer,
    Paragraph,
    TextRun,
    MarkRange,
    ListBlock,
    ListItem,
    Table,
    TableRow,
    TableCell,
    Media,
    Svg,
    Formula,
    Action,
    Details,
    VisualIslandCandidate,
    Runtime,
    Unsupported,
}

internal enum class RichContentMark {
    Bold,
    Italic,
    Underline,
    Strike,
    Code,
    Link,
    Color,
    Background,
    Font,
}

internal data class RichNodeCapabilities(
    val textFlowEligible: Boolean = false,
    val nativeBoxEligible: Boolean = true,
    val snapshotIslandEligible: Boolean = false,
    val inlineWebViewRequired: Boolean = false,
    val interactive: Boolean = false,
    val browserOnlyVisual: Boolean = false,
    val unsafeRuntime: Boolean = false,
    val unsupportedReasons: Set<String> = emptySet(),
)

internal data class RichContentImportDiagnostics(
    val sourceNodeCount: Int,
    val normalizedNodeCount: Int,
    val conversionLossCount: Int,
    val warnings: Map<String, Int> = emptyMap(),
)

internal data class RichContentDocumentStats(
    val sourceNodeCount: Int,
    val canonicalNodeCount: Int,
    val normalizedNodeCount: Int,
    val droppedUnsupportedNodeCount: Int,
    val textRunCount: Int,
    val markRangeCount: Int,
    val paragraphCount: Int,
    val actionCount: Int,
    val mediaCount: Int,
    val tableCount: Int,
    val svgCount: Int,
    val browserOnlyCount: Int,
    val textFlowEligibleSubtreeCount: Int,
    val snapshotIslandEligibleSubtreeCount: Int,
    val nativeBackendCount: Int,
    val snapshotBackendCount: Int,
    val inlineWebViewRequiredCount: Int,
    val importConversionLossCount: Int,
)

internal data class RichContentDocumentRouteReport(
    val documentId: String,
    val route: RichContentDocumentRoute,
    val reason: String,
    val nativeBackendCount: Int,
    val snapshotBackendCount: Int,
    val inlineWebViewRequiredCount: Int,
)

internal enum class RichContentDocumentRoute {
    Native,
    NativeWithSnapshotIslands,
    Snapshot,
    DynamicPreview,
}

internal object RichContentDocumentTextStore {
    private const val MaxEntries = 2_048
    private val textByNodeId = linkedMapOf<String, String>()

    fun put(nodeId: String, text: String) = synchronized(textByNodeId) {
        if (text.isBlank()) return@synchronized
        textByNodeId[nodeId] = text
        while (textByNodeId.size > MaxEntries) {
            val first = textByNodeId.keys.firstOrNull() ?: break
            textByNodeId.remove(first)
        }
    }

    fun text(node: RichContentNodeV2): String = synchronized(textByNodeId) {
        textByNodeId[node.nodeId].orEmpty()
    }

    fun resetForTest() = synchronized(textByNodeId) {
        textByNodeId.clear()
    }
}

internal fun buildRichContentDocumentFromHtml(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentDocument {
    val legacyAst = buildRichContentAstFromHtml(html, analysis)
    return buildRichContentDocumentFromAst(
        source = html,
        sourceKind = RichContentSourceKind.Html,
        legacyAst = legacyAst,
    )
}

internal fun buildRichContentDocumentFromParsedHtml(
    html: String,
    document: Document,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentDocument {
    val legacyAst = buildRichContentAstFromParsedHtml(html, document, analysis)
    return buildRichContentDocumentFromAst(
        source = html,
        sourceKind = RichContentSourceKind.Html,
        legacyAst = legacyAst,
    )
}

internal fun buildRichContentDocumentFromAst(
    source: String,
    sourceKind: RichContentSourceKind,
    legacyAst: RichContentAst,
): RichContentDocument {
    val root = (legacyAst.root.toDocumentNode("r0") as RichContentElementNode).normalizeDocumentNode()
    val nodes = root.flattenDocumentNodes()
    val stats = nodes.toDocumentStats(
        sourceNodeCount = legacyAst.stats.sourceNodeCount,
        normalizedNodeCount = nodes.size,
        importConversionLossCount = legacyAst.stats.browserOnlyCount,
    )
    return RichContentDocument(
        documentId = renderTextCacheKey(source),
        schemaVersion = RichContentDocumentSchemaVersion,
        sourceKind = sourceKind,
        root = root,
        imports = RichContentImportDiagnostics(
            sourceNodeCount = legacyAst.stats.sourceNodeCount,
            normalizedNodeCount = nodes.size,
            conversionLossCount = legacyAst.stats.browserOnlyCount,
            warnings = nodes
                .flatMap { it.capabilities.unsupportedReasons }
                .groupingBy { it }
                .eachCount(),
        ),
        stats = stats,
    )
}

internal fun buildRichContentDocumentFromMarkdown(markdown: String): RichContentDocument {
    val children = parseMarkdownBlocks(markdown)
    return documentFromRootChildren(
        source = markdown,
        sourceKind = RichContentSourceKind.Markdown,
        children = children,
        conversionLossCount = 0,
    )
}

private fun parseMarkdownBlocks(markdown: String): List<RichContentNodeV2> {
    val blocks = mutableListOf<RichContentNodeV2>()
    val lines = markdown.replace("\r\n", "\n").lines()
    var index = 0
    var blockIndex = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index += 1
            continue
        }
        val listMatch = MARKDOWN_LIST_LINE.matchEntire(line)
        if (listMatch != null) {
            val ordered = listMatch.groupValues[1].lastOrNull()?.isDigit() == true
            val items = mutableListOf<RichContentNodeV2>()
            while (index < lines.size) {
                val itemMatch = MARKDOWN_LIST_LINE.matchEntire(lines[index]) ?: break
                val itemText = itemMatch.groupValues[2].trim()
                val itemPath = "r0/l$blockIndex/li${items.size}"
                items += textNode(
                    kind = RichContentNodeKind.ListItem,
                    stablePath = itemPath,
                    textDigest = "li",
                    capabilities = RichNodeCapabilities(textFlowEligible = true),
                    children = listOf(
                        markdownParagraphNode(
                            text = itemText,
                            stablePath = "$itemPath/p0",
                        )
                    ),
                )
                index += 1
            }
            blocks += textNode(
                kind = RichContentNodeKind.ListBlock,
                stablePath = "r0/l$blockIndex",
                textDigest = "list:$ordered",
                capabilities = RichNodeCapabilities(textFlowEligible = true),
                children = items,
            )
            blockIndex += 1
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (index < lines.size && lines[index].isNotBlank() && MARKDOWN_LIST_LINE.matchEntire(lines[index]) == null) {
            paragraphLines += lines[index].trim()
            index += 1
        }
        val text = paragraphLines.joinToString(" ").trim()
        if (text.isNotBlank()) {
            blocks += markdownParagraphNode(text = text, stablePath = "r0/p$blockIndex")
            blockIndex += 1
        }
    }
    return blocks
}

private fun markdownParagraphNode(text: String, stablePath: String): RichContentNodeV2 =
    textNode(
        kind = RichContentNodeKind.Paragraph,
        stablePath = stablePath,
        textDigest = "paragraph",
        capabilities = RichNodeCapabilities(textFlowEligible = true),
        children = parseMarkdownInlineNodes(text, stablePath),
    )

private fun parseMarkdownInlineNodes(text: String, parentPath: String): List<RichContentNodeV2> {
    val nodes = mutableListOf<RichContentNodeV2>()
    var cursor = 0
    var inlineIndex = 0
    fun addPlain(until: Int) {
        if (until <= cursor) return
        val plain = text.substring(cursor, until)
        if (plain.isNotBlank()) {
            nodes += markdownInlineNode(
                text = plain,
                stablePath = "$parentPath/t${inlineIndex++}",
                marks = emptySet(),
            )
        }
    }
    while (cursor < text.length) {
        val match = findNextMarkdownInlineMatch(text, cursor)
        if (match == null) {
            addPlain(text.length)
            break
        }
        addPlain(match.start)
        val marks = when (match.kind) {
            MarkdownInlineKind.Code -> setOf(RichContentMark.Code)
            MarkdownInlineKind.Bold -> setOf(RichContentMark.Bold)
            MarkdownInlineKind.Italic -> setOf(RichContentMark.Italic)
            MarkdownInlineKind.Link -> setOf(RichContentMark.Link)
        }
        val safeLink = match.kind != MarkdownInlineKind.Link || isSafeRichHtmlHref(match.url.orEmpty())
        nodes += markdownInlineNode(
            text = match.body,
            stablePath = "$parentPath/t${inlineIndex++}",
            marks = marks,
            unsupportedReasons = if (safeLink) emptySet() else setOf("UnsafeHref"),
        )
        cursor = match.end
    }
    return nodes
}

private fun findNextMarkdownInlineMatch(text: String, startAt: Int): MarkdownInlineMatch? =
    listOfNotNull(
        MARKDOWN_CODE.find(text, startAt)?.let {
            MarkdownInlineMatch(MarkdownInlineKind.Code, it.range.first, it.range.last + 1, it.groupValues[1])
        },
        MARKDOWN_BOLD.find(text, startAt)?.let {
            MarkdownInlineMatch(MarkdownInlineKind.Bold, it.range.first, it.range.last + 1, it.groupValues[1])
        },
        MARKDOWN_LINK.find(text, startAt)?.let {
            MarkdownInlineMatch(
                kind = MarkdownInlineKind.Link,
                start = it.range.first,
                end = it.range.last + 1,
                body = it.groupValues[1],
                url = it.groupValues[2],
            )
        },
        MARKDOWN_ITALIC.find(text, startAt)?.let {
            MarkdownInlineMatch(MarkdownInlineKind.Italic, it.range.first, it.range.last + 1, it.groupValues[1])
        },
    ).minWithOrNull(compareBy<MarkdownInlineMatch> { it.start }.thenBy { it.end - it.start })

private enum class MarkdownInlineKind {
    Code,
    Bold,
    Italic,
    Link,
}

private data class MarkdownInlineMatch(
    val kind: MarkdownInlineKind,
    val start: Int,
    val end: Int,
    val body: String,
    val url: String? = null,
)

private fun markdownInlineNode(
    text: String,
    stablePath: String,
    marks: Set<RichContentMark>,
    unsupportedReasons: Set<String> = emptySet(),
): RichContentNodeV2 =
    textNode(
        kind = if (marks.isEmpty()) RichContentNodeKind.TextRun else RichContentNodeKind.MarkRange,
        stablePath = stablePath,
        textDigest = text,
        rawText = text,
        marks = marks,
        capabilities = RichNodeCapabilities(
            textFlowEligible = unsupportedReasons.isEmpty(),
            nativeBoxEligible = false,
            unsupportedReasons = unsupportedReasons,
        ),
    )

internal fun buildRichContentDocumentFromProtocolAction(actionDigestSource: String): RichContentDocument {
    val action = textNode(
        kind = RichContentNodeKind.Action,
        stablePath = "r0/a0",
        textDigest = actionDigestSource,
        capabilities = RichNodeCapabilities(
            textFlowEligible = false,
            interactive = true,
            nativeBoxEligible = true,
        ),
    )
    return documentFromRootChildren(
        source = actionDigestSource,
        sourceKind = RichContentSourceKind.Protocol,
        children = listOf(action),
        conversionLossCount = 0,
    )
}

internal fun RichContentDocument.routeReport(): RichContentDocumentRouteReport {
    val nodes = root.flattenDocumentNodes()
    val inline = nodes.count { it.capabilities.inlineWebViewRequired || it.capabilities.unsafeRuntime }
    val snapshot = nodes.count { it.capabilities.snapshotIslandEligible || it.capabilities.browserOnlyVisual }
    val native = nodes.count { it.capabilities.nativeBoxEligible && !it.capabilities.inlineWebViewRequired }
    val route = when {
        inline > 0 -> RichContentDocumentRoute.DynamicPreview
        snapshot > 0 && native > snapshot -> RichContentDocumentRoute.NativeWithSnapshotIslands
        snapshot > 0 -> RichContentDocumentRoute.Snapshot
        else -> RichContentDocumentRoute.Native
    }
    return RichContentDocumentRouteReport(
        documentId = documentId,
        route = route,
        reason = when (route) {
            RichContentDocumentRoute.Native -> "AstNativeEligible"
            RichContentDocumentRoute.NativeWithSnapshotIslands -> "AstSnapshotIslandEligible"
            RichContentDocumentRoute.Snapshot -> "AstBrowserVisual"
            RichContentDocumentRoute.DynamicPreview -> "AstRuntimeRequired"
        },
        nativeBackendCount = native,
        snapshotBackendCount = snapshot,
        inlineWebViewRequiredCount = inline,
    )
}

private fun documentFromRootChildren(
    source: String,
    sourceKind: RichContentSourceKind,
    children: List<RichContentNodeV2>,
    conversionLossCount: Int,
): RichContentDocument {
    val root = textNode(
        kind = RichContentNodeKind.Document,
        stablePath = "r0",
        textDigest = sourceKind.name,
        capabilities = RichNodeCapabilities(nativeBoxEligible = true),
        children = children,
    )
    val nodes = root.flattenDocumentNodes()
    return RichContentDocument(
        documentId = renderTextCacheKey(source),
        schemaVersion = RichContentDocumentSchemaVersion,
        sourceKind = sourceKind,
        root = root,
        imports = RichContentImportDiagnostics(
            sourceNodeCount = children.size,
            normalizedNodeCount = nodes.size,
            conversionLossCount = conversionLossCount,
        ),
        stats = nodes.toDocumentStats(
            sourceNodeCount = children.size,
            normalizedNodeCount = nodes.size,
            importConversionLossCount = conversionLossCount,
        ),
    )
}

private fun RichContentNode.toDocumentNode(path: String): RichContentNodeV2 {
    return when (this) {
        is RichContentNode.Document -> textNode(
            kind = RichContentNodeKind.Document,
            stablePath = stablePath,
            textDigest = sourceKind.name,
            capabilities = RichNodeCapabilities(nativeBoxEligible = true),
            children = children.mapIndexed { index, child -> child.toDocumentNode("$path/c$index") },
        )
        is RichContentNode.Container -> textNode(
            kind = if (blockedTextFlow) RichContentNodeKind.VisualIslandCandidate else RichContentNodeKind.BlockContainer,
            stablePath = stablePath,
            textDigest = tagName,
            capabilities = RichNodeCapabilities(
                textFlowEligible = textFlowCandidate && !blockedTextFlow,
                snapshotIslandEligible = blockedTextFlow,
                browserOnlyVisual = blockedTextFlow,
                unsupportedReasons = if (blockedTextFlow) setOf("TextFlowBlockedVisual") else emptySet(),
            ),
            children = children.mapIndexed { index, child -> child.toDocumentNode("$path/c$index") },
        )
        is RichContentNode.Paragraph -> textNode(
            kind = RichContentNodeKind.Paragraph,
            stablePath = stablePath,
            textDigest = "paragraph",
            capabilities = RichNodeCapabilities(
                textFlowEligible = textFlowCandidate && !blockedTextFlow,
                snapshotIslandEligible = blockedTextFlow,
                browserOnlyVisual = blockedTextFlow,
                unsupportedReasons = if (blockedTextFlow) setOf("TextFlowBlockedVisual") else emptySet(),
            ),
            children = children.mapIndexed { index, child -> child.toDocumentNode("$path/c$index") },
        )
        is RichContentNode.TextRun -> textNode(
            kind = RichContentNodeKind.TextRun,
            stablePath = stablePath,
            textDigest = text,
            rawText = text,
            capabilities = RichNodeCapabilities(textFlowEligible = true, nativeBoxEligible = false),
        )
        is RichContentNode.StyledTextRun -> textNode(
            kind = RichContentNodeKind.MarkRange,
            stablePath = stablePath,
            textDigest = text,
            rawText = text,
            marks = styleTags.toRichContentMarks(),
            capabilities = RichNodeCapabilities(textFlowEligible = true, nativeBoxEligible = false),
        )
        is RichContentNode.LinkRun -> textNode(
            kind = RichContentNodeKind.MarkRange,
            stablePath = stablePath,
            textDigest = text,
            rawText = text,
            marks = setOf(RichContentMark.Link),
            capabilities = RichNodeCapabilities(
                textFlowEligible = true,
                nativeBoxEligible = false,
                unsupportedReasons = if (safeHref) emptySet() else setOf("UnsafeHref"),
            ),
        )
        is RichContentNode.InlineCodeRun -> textNode(
            kind = RichContentNodeKind.MarkRange,
            stablePath = stablePath,
            textDigest = text,
            rawText = text,
            marks = setOf(RichContentMark.Code),
            capabilities = RichNodeCapabilities(textFlowEligible = true, nativeBoxEligible = false),
        )
        is RichContentNode.ListBlock -> textNode(
            kind = RichContentNodeKind.ListBlock,
            stablePath = stablePath,
            textDigest = "list:$ordered",
            capabilities = RichNodeCapabilities(textFlowEligible = textFlowCandidate && !blockedTextFlow),
            children = items.mapIndexed { index, item -> item.toDocumentNode("$path/li$index") },
        )
        is RichContentNode.ListItem -> textNode(
            kind = RichContentNodeKind.ListItem,
            stablePath = stablePath,
            textDigest = "li",
            capabilities = RichNodeCapabilities(textFlowEligible = textFlowCandidate && !blockedTextFlow),
            children = children.mapIndexed { index, child -> child.toDocumentNode("$path/c$index") },
        )
        is RichContentNode.ActionButton -> textNode(
            kind = RichContentNodeKind.Action,
            stablePath = stablePath,
            textDigest = "action:$hasAction",
            capabilities = RichNodeCapabilities(interactive = true, textFlowEligible = false),
        )
        is RichContentNode.Image -> textNode(
            kind = RichContentNodeKind.Media,
            stablePath = stablePath,
            textDigest = "image:$hasAlt:$safeSource",
            capabilities = RichNodeCapabilities(
                textFlowEligible = false,
                unsupportedReasons = if (safeSource) emptySet() else setOf("UnsafeMediaSource"),
            ),
        )
        is RichContentNode.Svg -> textNode(
            kind = RichContentNodeKind.Svg,
            stablePath = stablePath,
            textDigest = "svg:$complex",
            capabilities = RichNodeCapabilities(
                textFlowEligible = false,
                snapshotIslandEligible = complex,
                browserOnlyVisual = complex,
                unsupportedReasons = if (complex) setOf("ComplexSvg") else emptySet(),
            ),
        )
        is RichContentNode.Table -> textNode(
            kind = RichContentNodeKind.Table,
            stablePath = stablePath,
            textDigest = "table:$rowCount:$cellCount",
            capabilities = RichNodeCapabilities(textFlowEligible = false),
        )
        is RichContentNode.Details -> textNode(
            kind = RichContentNodeKind.Details,
            stablePath = stablePath,
            textDigest = "details:$open",
            capabilities = RichNodeCapabilities(textFlowEligible = false),
            children = children.mapIndexed { index, child -> child.toDocumentNode("$path/c$index") },
        )
        is RichContentNode.Formula -> textNode(
            kind = RichContentNodeKind.Formula,
            stablePath = stablePath,
            textDigest = "formula:$inline",
            capabilities = RichNodeCapabilities(textFlowEligible = inline, nativeBoxEligible = true),
        )
        is RichContentNode.BrowserOnly -> textNode(
            kind = RichContentNodeKind.Runtime,
            stablePath = stablePath,
            textDigest = "runtime:$tagName:$reason",
            capabilities = RichNodeCapabilities(
                textFlowEligible = false,
                inlineWebViewRequired = true,
                unsafeRuntime = reason == "RuntimeElement",
                unsupportedReasons = setOf(reason),
            ),
        )
        is RichContentNode.Unsupported -> textNode(
            kind = RichContentNodeKind.Unsupported,
            stablePath = stablePath,
            textDigest = "unsupported:$tagName:$reason",
            capabilities = RichNodeCapabilities(
                textFlowEligible = false,
                nativeBoxEligible = false,
                unsupportedReasons = setOf(reason),
            ),
        )
    }
}

private fun RichContentElementNode.normalizeDocumentNode(): RichContentElementNode {
    val normalizedChildren = children
        .map { (it as? RichContentElementNode)?.normalizeDocumentNode() ?: it }
        .mergeAdjacentMarkRuns()
        .filterNot { child ->
            child.kind == RichContentNodeKind.BlockContainer &&
                child.children.isEmpty() &&
                !child.capabilities.browserOnlyVisual &&
                !child.capabilities.snapshotIslandEligible
        }
    return copy(
        children = normalizedChildren,
        subtreeDigest = documentSubtreeDigest(kind, stablePath, marks, normalizedChildren, subtreeDigest),
    )
}

private fun List<RichContentNodeV2>.mergeAdjacentMarkRuns(): List<RichContentNodeV2> {
    if (isEmpty()) return this
    val merged = mutableListOf<RichContentNodeV2>()
    forEach { node ->
        val last = merged.lastOrNull() as? RichContentElementNode
        val current = node as? RichContentElementNode
        if (last != null &&
            current != null &&
            last.kind == current.kind &&
            last.kind in setOf(RichContentNodeKind.TextRun, RichContentNodeKind.MarkRange) &&
            last.marks == current.marks
        ) {
            val mergedText = RichContentDocumentTextStore.text(last) + RichContentDocumentTextStore.text(current)
            val mergedNode = last.copy(
                subtreeDigest = renderTextCacheKey(last.subtreeDigest + current.subtreeDigest),
            )
            RichContentDocumentTextStore.put(mergedNode.nodeId, mergedText)
            merged[merged.lastIndex] = mergedNode
        } else {
            merged += node
        }
    }
    return merged
}

private fun textNode(
    kind: RichContentNodeKind,
    stablePath: String,
    textDigest: String,
    rawText: String = "",
    capabilities: RichNodeCapabilities,
    children: List<RichContentNodeV2> = emptyList(),
    marks: Set<RichContentMark> = emptySet(),
): RichContentElementNode {
    val nodeId = renderTextCacheKey(
        "$RichContentDocumentSchemaVersion:$stablePath:$kind:${renderTextCacheKey(textDigest)}:" +
            marks.sortedBy { it.name }.joinToString(",") { it.name }
    )
    val subtreeDigest = documentSubtreeDigest(kind, stablePath, marks, children, renderTextCacheKey(textDigest))
    return RichContentElementNode(
        nodeId = nodeId,
        stablePath = stablePath,
        subtreeDigest = subtreeDigest,
        capabilities = capabilities,
        kind = kind,
        children = children,
        marks = marks,
    ).also { node ->
        if (rawText.isNotBlank()) {
            RichContentDocumentTextStore.put(node.nodeId, rawText)
        }
    }
}

private fun documentSubtreeDigest(
    kind: RichContentNodeKind,
    stablePath: String,
    marks: Set<RichContentMark>,
    children: List<RichContentNodeV2>,
    localDigest: String,
): String = renderTextCacheKey(
    buildString {
        append(kind.name)
        append('|')
        append(stablePath)
        append('|')
        append(marks.sortedBy { it.name }.joinToString(",") { it.name })
        append('|')
        append(localDigest)
        children.forEach { child ->
            append('|')
            append(child.subtreeDigest)
        }
    }
)

private fun List<RichContentNodeV2>.toDocumentStats(
    sourceNodeCount: Int,
    normalizedNodeCount: Int,
    importConversionLossCount: Int,
): RichContentDocumentStats = RichContentDocumentStats(
    sourceNodeCount = sourceNodeCount,
    canonicalNodeCount = size,
    normalizedNodeCount = normalizedNodeCount,
    droppedUnsupportedNodeCount = count { it.kind == RichContentNodeKind.Unsupported },
    textRunCount = count { it.kind == RichContentNodeKind.TextRun || it.kind == RichContentNodeKind.MarkRange },
    markRangeCount = count { (it as? RichContentElementNode)?.marks?.isNotEmpty() == true },
    paragraphCount = count { it.kind == RichContentNodeKind.Paragraph },
    actionCount = count { it.kind == RichContentNodeKind.Action },
    mediaCount = count { it.kind == RichContentNodeKind.Media || it.kind == RichContentNodeKind.Formula },
    tableCount = count { it.kind == RichContentNodeKind.Table },
    svgCount = count { it.kind == RichContentNodeKind.Svg },
    browserOnlyCount = count { it.capabilities.inlineWebViewRequired || it.capabilities.browserOnlyVisual },
    textFlowEligibleSubtreeCount = count { it.capabilities.textFlowEligible },
    snapshotIslandEligibleSubtreeCount = count { it.capabilities.snapshotIslandEligible },
    nativeBackendCount = count { it.capabilities.nativeBoxEligible && !it.capabilities.inlineWebViewRequired },
    snapshotBackendCount = count { it.capabilities.snapshotIslandEligible || it.capabilities.browserOnlyVisual },
    inlineWebViewRequiredCount = count { it.capabilities.inlineWebViewRequired },
    importConversionLossCount = importConversionLossCount,
)

internal fun RichContentNodeV2.flattenDocumentNodes(): List<RichContentNodeV2> =
    listOf(this) + children.flatMap { it.flattenDocumentNodes() }

private fun Set<String>.toRichContentMarks(): Set<RichContentMark> = mapNotNullTo(linkedSetOf()) { tag ->
    when (tag.lowercase()) {
        "b", "strong" -> RichContentMark.Bold
        "i", "em" -> RichContentMark.Italic
        "u" -> RichContentMark.Underline
        "s", "del" -> RichContentMark.Strike
        "code" -> RichContentMark.Code
        "mark" -> RichContentMark.Background
        else -> null
    }
}

private val MARKDOWN_LIST_LINE = Regex("""^\s*((?:[-*+])|(?:\d+[.)]))\s+(.+?)\s*$""")
private val MARKDOWN_CODE = Regex("""`([^`]+)`""")
private val MARKDOWN_BOLD = Regex("""\*\*([^*]+)\*\*""")
private val MARKDOWN_ITALIC = Regex("""(?<!\*)\*([^*]+)\*(?!\*)""")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
