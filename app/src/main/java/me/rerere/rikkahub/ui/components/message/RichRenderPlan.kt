package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import me.rerere.rikkahub.ui.components.richtext.RichBlock
import me.rerere.rikkahub.ui.components.richtext.RichButtonBlock
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichDetailsBlock
import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModel
import me.rerere.rikkahub.ui.components.richtext.RichImageBlock
import me.rerere.rikkahub.ui.components.richtext.RichMathBlock
import me.rerere.rikkahub.ui.components.richtext.RichSvgBlock
import me.rerere.rikkahub.ui.components.richtext.RichTableBlock
import me.rerere.rikkahub.ui.components.richtext.RichTextBlock
import me.rerere.rikkahub.ui.components.richtext.RichUnsupportedBlock
import me.rerere.rikkahub.ui.components.richtext.RichUnsupportedReason
import me.rerere.rikkahub.ui.components.richtext.RichVisualHint

internal data class RichRenderPlan(
    val id: String,
    val route: RichRenderPlanRoute,
    val nativeConfidence: RichRenderNativeConfidence,
    val reason: String,
    val htmlLength: Int,
    val sourceNodeCount: Int,
    val estimatedRenderBlockCount: Int,
    val textFlowCandidateCount: Int,
    val snapshotIslandCandidateCount: Int,
    val interactiveActionCount: Int,
    val visualHints: Set<RichVisualHint>,
    val unsupported: Set<RichUnsupportedReason>,
    val riskScore: Int,
    val riskReasons: Set<String>,
    val heightCache: RichRenderHeightCacheState,
) {
    fun withRoute(route: RichRenderPlanRoute, reason: String): RichRenderPlan = copy(
        route = route,
        reason = reason,
        nativeConfidence = when (route) {
            RichRenderPlanRoute.Native -> nativeConfidence
            RichRenderPlanRoute.Snapshot -> if (nativeConfidence == RichRenderNativeConfidence.BrowserRequired) {
                RichRenderNativeConfidence.BrowserRequired
            } else {
                RichRenderNativeConfidence.Low
            }
            RichRenderPlanRoute.DynamicPreview,
            RichRenderPlanRoute.InlineWebView -> RichRenderNativeConfidence.BrowserRequired
            RichRenderPlanRoute.Lightweight -> nativeConfidence
        },
    )

    fun toMetadataLine(): String = listOf(
        "id=$id",
        "route=${route.name}",
        "confidence=${nativeConfidence.name}",
        "reason=$reason",
        "htmlLength=$htmlLength",
        "sourceNodes=$sourceNodeCount",
        "estimatedRenderBlocks=$estimatedRenderBlockCount",
        "textFlowCandidates=$textFlowCandidateCount",
        "snapshotIslandCandidates=$snapshotIslandCandidateCount",
        "interactiveActions=$interactiveActionCount",
        "visualHints=${visualHints.map { it.name }.sorted()}",
        "unsupported=${unsupported.map { it.name }.sorted()}",
        "riskScore=$riskScore",
        "riskReasons=${riskReasons.sorted()}",
        "heightCache=${heightCache.name}",
    ).joinToString(" ")
}

internal enum class RichRenderPlanRoute {
    Native,
    Snapshot,
    DynamicPreview,
    Lightweight,
    InlineWebView,
}

internal enum class RichRenderNativeConfidence {
    High,
    Medium,
    Low,
    BrowserRequired,
}

internal enum class RichRenderHeightCacheState {
    Unknown,
    Miss,
    Hit,
}

internal fun buildRichRenderPlan(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
    risk: RenderRiskScore = RenderRiskScore.fromHtml(html, analysis),
    model: RichHtmlRenderModel? = null,
    heightCacheState: RichRenderHeightCacheState = RichRenderHeightCacheState.Unknown,
): RichRenderPlan {
    val metrics = inspectRichRenderEstimatorMetrics(html)
    val beforeCompile = RichHtmlSnapshotPolicy.beforeCompile(analysis)
    val modelDecision = model?.let { RichHtmlSnapshotPolicy.afterCompile(analysis, it) }
    val decision = modelDecision ?: beforeCompile
    val inferredHints = metrics.inferVisualHints()
    val visualHints = model?.visualHints?.toSet() ?: inferredHints
    val unsupported = model?.unsupported?.toSet().orEmpty()
    val route = when {
        analysis.kind == RichHtmlRenderKind.ComplexDynamic -> RichRenderPlanRoute.DynamicPreview
        risk.route == RichContentRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview
        modelDecision != null -> decision.route.toPlanRoute()
        risk.route == RichContentRoute.Snapshot -> RichRenderPlanRoute.Snapshot
        beforeCompile.route == RichHtmlSnapshotRoute.Snapshot -> RichRenderPlanRoute.Snapshot
        beforeCompile.route == RichHtmlSnapshotRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview
        else -> RichRenderPlanRoute.Native
    }
    val reason = when {
        modelDecision != null && decision.reason.isNotBlank() -> "AfterCompile:${decision.reason}"
        modelDecision != null -> "AfterCompile:${decision.route.name}"
        analysis.kind == RichHtmlRenderKind.ComplexDynamic -> "ComplexDynamic"
        risk.route == RichContentRoute.DynamicPreview -> "Risk:DynamicPreview"
        risk.route == RichContentRoute.Snapshot -> "Risk:Snapshot"
        beforeCompile.reason.isNotBlank() -> "BeforeCompile:${beforeCompile.reason}"
        else -> "NativeEligible"
    }

    return RichRenderPlan(
        id = renderTextCacheKey(html),
        route = route,
        nativeConfidence = resolvePlanConfidence(analysis, route, visualHints, unsupported),
        reason = reason,
        htmlLength = html.length,
        sourceNodeCount = metrics.sourceNodeCount,
        estimatedRenderBlockCount = model?.blocks?.sumOf(::countRichRenderBlocks)
            ?: metrics.estimatedRenderBlockCount(),
        textFlowCandidateCount = metrics.textFlowCandidateCount(),
        snapshotIslandCandidateCount = metrics.snapshotIslandCandidateCount(),
        interactiveActionCount = metrics.interactiveActionCount,
        visualHints = visualHints,
        unsupported = unsupported,
        riskScore = risk.score,
        riskReasons = risk.reasons,
        heightCache = heightCacheState,
    )
}

internal fun inspectRichRenderEstimatorMetrics(html: String): RichRenderEstimatorMetrics {
    return RichRenderEstimatorMetrics(
        html = html,
        sourceNodeCount = HTML_TAG.findAll(html).count(),
        tableCellCount = TABLE_CELL_TAG.findAll(html).count(),
        svgPathChars = SVG_PATH_D_ATTR.findAll(html).sumOf { it.groupValues.getOrNull(2)?.length ?: 0 },
        svgCommandCount = SVG_COMMAND_TAG.findAll(html).count(),
        animationHintCount = ANIMATION_HINT.findAll(html).count(),
        visualHintCount = VISUAL_HINT.findAll(html).count(),
        runtimeDynamic = RUNTIME_DYNAMIC_HINT.containsMatchIn(html),
        interactiveActionCount = INTERACTION_HINT.findAll(html).count(),
        textFlowTagCount = TEXT_FLOW_TAG.findAll(html).count(),
        textHeavyDivCount = TEXT_HEAVY_DIV.findAll(html).count { match ->
            val tag = match.value
            val style = STYLE_ATTR.find(tag)?.groupValues?.getOrNull(2).orEmpty()
            style.isBlank() || !TEXT_FLOW_BLOCKING_STYLE.containsMatchIn(style)
        },
        layoutStyleCount = LAYOUT_STYLE_HINT.findAll(html).count(),
        imageCount = IMAGE_TAG.findAll(html).count(),
        buttonCount = BUTTON_TAG.findAll(html).count(),
        detailsCount = DETAILS_TAG.findAll(html).count(),
        snapshotHintCount = SNAPSHOT_ISLAND_HINT.findAll(html).count(),
        multiBackgroundCount = MULTI_BACKGROUND_HINT.findAll(html).count(),
        complexSvgFeatureCount = COMPLEX_SVG_FEATURE.findAll(html).count(),
    )
}

internal data class RichRenderEstimatorMetrics(
    val html: String,
    val sourceNodeCount: Int,
    val tableCellCount: Int,
    val svgPathChars: Int,
    val svgCommandCount: Int,
    val animationHintCount: Int,
    val visualHintCount: Int,
    val runtimeDynamic: Boolean,
    val interactiveActionCount: Int,
    val textFlowTagCount: Int,
    val textHeavyDivCount: Int,
    val layoutStyleCount: Int,
    val imageCount: Int,
    val buttonCount: Int,
    val detailsCount: Int,
    val snapshotHintCount: Int,
    val multiBackgroundCount: Int,
    val complexSvgFeatureCount: Int,
) {
    fun estimatedRenderBlockCount(): Int {
        val textFlowDiscount = textFlowCandidateCount() / 2
        val weightedAdditions = tableCellCount +
            svgCommandCount +
            imageCount * 2 +
            buttonCount * 2 +
            detailsCount * 3 +
            layoutStyleCount * 2
        return (sourceNodeCount - textFlowDiscount + weightedAdditions).coerceAtLeast(1)
    }

    fun textFlowCandidateCount(): Int = textFlowTagCount + textHeavyDivCount

    fun snapshotIslandCandidateCount(): Int {
        return snapshotHintCount +
            multiBackgroundCount +
            complexSvgFeatureCount +
            (svgPathChars / 2_000) +
            (svgCommandCount / 24)
    }

    fun inferVisualHints(): Set<RichVisualHint> = buildSet {
        if (Regex("""backdrop-filter\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.CssBackdropFilter)
        }
        if (Regex("""filter\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.CssFilter)
        }
        if (Regex("""\bmask(?:-image)?\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.CssMask)
        }
        if (Regex("""mix-blend-mode\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.CssMixBlendMode)
        }
        if (Regex("""clip-path\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.CssClipPath)
        }
        if (animationHintCount > 0) add(RichVisualHint.CssAnimation)
        if (multiBackgroundCount > 0) add(RichVisualHint.BackgroundExtraLayer)
        if (Regex("""<\s*(use)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add(RichVisualHint.SvgUse)
        if (Regex("""<\s*(symbol)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add(RichVisualHint.SvgSymbol)
        if (Regex("""<\s*(foreignObject)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.SvgForeignObject)
        }
        if (Regex("""<\s*(clipPath)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            add(RichVisualHint.SvgClipPath)
        }
        if (Regex("""<\s*(mask)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add(RichVisualHint.SvgMask)
        if (Regex("""<\s*(filter)\b""", RegexOption.IGNORE_CASE).containsMatchIn(html)) add(RichVisualHint.SvgFilter)
    }
}

internal fun countRichRenderBlocks(block: RichBlock): Int {
    return when (block) {
        is RichContainerBlock -> 1 + block.children.sumOf(::countRichRenderBlocks)
        is RichButtonBlock -> 1 + block.children.sumOf(::countRichRenderBlocks) +
            block.inlineBoxes.sumOf { countRichRenderBlocks(it.block) }
        is RichDetailsBlock -> 1 + block.children.sumOf(::countRichRenderBlocks)
        is RichTextBlock -> 1 + block.inlineBoxes.sumOf { countRichRenderBlocks(it.block) }
        is RichSvgBlock -> 1 + block.model.commands.size
        is RichImageBlock,
        is RichTableBlock,
        is RichMathBlock,
        is RichUnsupportedBlock -> 1
    }
}

private fun RichHtmlSnapshotRoute.toPlanRoute(): RichRenderPlanRoute = when (this) {
    RichHtmlSnapshotRoute.Native -> RichRenderPlanRoute.Native
    RichHtmlSnapshotRoute.Snapshot -> RichRenderPlanRoute.Snapshot
    RichHtmlSnapshotRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview
}

private fun resolvePlanConfidence(
    analysis: RichHtmlAnalysis,
    route: RichRenderPlanRoute,
    visualHints: Set<RichVisualHint>,
    unsupported: Set<RichUnsupportedReason>,
): RichRenderNativeConfidence {
    if (route == RichRenderPlanRoute.DynamicPreview || route == RichRenderPlanRoute.InlineWebView) {
        return RichRenderNativeConfidence.BrowserRequired
    }
    if (unsupported.isNotEmpty()) return RichRenderNativeConfidence.BrowserRequired
    if (route == RichRenderPlanRoute.Snapshot) {
        return if (analysis.nativeConfidence == NativeConfidence.WebViewFallback) {
            RichRenderNativeConfidence.BrowserRequired
        } else {
            RichRenderNativeConfidence.Low
        }
    }
    return when (analysis.nativeConfidence) {
        NativeConfidence.High -> RichRenderNativeConfidence.High
        NativeConfidence.Medium -> {
            if (visualHints.isEmpty()) RichRenderNativeConfidence.Medium else RichRenderNativeConfidence.Low
        }
        NativeConfidence.WebViewFallback,
        NativeConfidence.DynamicPreview -> RichRenderNativeConfidence.BrowserRequired
    }
}

private val HTML_TAG = Regex("""<[^>]+>""")
private val TABLE_CELL_TAG = Regex("""<\s*(td|th)\b""", RegexOption.IGNORE_CASE)
private val SVG_COMMAND_TAG = Regex("""<\s*(path|rect|circle|ellipse|line|polyline|polygon|text)\b""", RegexOption.IGNORE_CASE)
private val SVG_PATH_D_ATTR = Regex("""<\s*path\b[^>]*\sd\s*=\s*(['"])([\s\S]*?)\1""", RegexOption.IGNORE_CASE)
private val ANIMATION_HINT = Regex("""(@keyframes|\banimation\s*:|\btransition\s*:|requestAnimationFrame)""", RegexOption.IGNORE_CASE)
private val VISUAL_HINT = Regex("""(linear-gradient|radial-gradient|box-shadow|filter\s*:|clip-path|mask\s*:|mix-blend-mode)""", RegexOption.IGNORE_CASE)
private val RUNTIME_DYNAMIC_HINT = Regex(
    """(<\s*(script|canvas|video|audio|iframe|object|embed)\b|\brequestAnimationFrame\s*\(|\bsetInterval\s*\(|\bTHREE\s*\.|\bWebGLRenderer\b|\bmermaid\s*\.)""",
    RegexOption.IGNORE_CASE,
)
private val INTERACTION_HINT = Regex(
    """(<\s*button\b|\bdata-(send|input)\s*=|\bonclick\s*=\s*(['"])[\s\S]*?\binput\s*\()""",
    RegexOption.IGNORE_CASE,
)
private val TEXT_FLOW_TAG = Regex("""<\s*(p|span|b|strong|i|em|u|code|a|li)\b""", RegexOption.IGNORE_CASE)
private val TEXT_HEAVY_DIV = Regex("""<\s*div\b[^>]*>""", RegexOption.IGNORE_CASE)
private val STYLE_ATTR = Regex("""\bstyle\s*=\s*(['"])([\s\S]*?)\1""", RegexOption.IGNORE_CASE)
private val TEXT_FLOW_BLOCKING_STYLE = Regex(
    """\b(display\s*:\s*(flex|grid|inline-flex|inline-grid)|position\s*:\s*(absolute|fixed|sticky)|background(?:-image)?\s*:|box-shadow\s*:|filter\s*:|backdrop-filter\s*:|clip-path\s*:|mask(?:-image)?\s*:)""",
    RegexOption.IGNORE_CASE,
)
private val LAYOUT_STYLE_HINT = Regex(
    """\b(display\s*:\s*(flex|grid|inline-flex|inline-grid)|position\s*:\s*(absolute|fixed|sticky)|grid-template-columns|flex\s*:|flex-flow\s*:|columns?\s*:)""",
    RegexOption.IGNORE_CASE,
)
private val IMAGE_TAG = Regex("""<\s*img\b""", RegexOption.IGNORE_CASE)
private val BUTTON_TAG = Regex("""<\s*button\b""", RegexOption.IGNORE_CASE)
private val DETAILS_TAG = Regex("""<\s*details\b""", RegexOption.IGNORE_CASE)
private val SNAPSHOT_ISLAND_HINT = Regex(
    """(backdrop-filter\s*:|filter\s*:|mix-blend-mode\s*:|clip-path\s*:\s*(path|polygon)\(|\bmask(?:-image)?\s*:)""",
    RegexOption.IGNORE_CASE,
)
private val MULTI_BACKGROUND_HINT = Regex(
    """background(?:-image)?\s*:[^;]*(?:linear-gradient|radial-gradient|url\()[^;]*,[^;]*(?:linear-gradient|radial-gradient|url\()""",
    RegexOption.IGNORE_CASE,
)
private val COMPLEX_SVG_FEATURE = Regex(
    """<\s*(defs|use|symbol|filter|mask|foreignObject|clipPath)\b""",
    RegexOption.IGNORE_CASE,
)
