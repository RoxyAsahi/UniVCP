package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.components.message.RichHtmlBudget
import me.rerere.rikkahub.ui.components.message.RichHtmlFallbackStage
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.analyzeRichHtml
import me.rerere.rikkahub.ui.components.message.buildRichContentDocumentFromHtml
import me.rerere.rikkahub.ui.components.message.buildRichContentDocumentFromParsedHtml
import me.rerere.rikkahub.ui.components.message.buildRichContentTextFlowPlanFromHtml
import me.rerere.rikkahub.ui.components.message.subtreeRoutePlan
import me.rerere.rikkahub.ui.components.message.textFlowPlan
import me.rerere.rikkahub.ui.components.message.extractInputActionFromElement
import me.rerere.rikkahub.ui.components.message.inspectRichHtmlSafety
import me.rerere.rikkahub.ui.components.message.isDangerousRichHtmlTag
import me.rerere.rikkahub.ui.components.message.isSafeRichHtmlHref
import me.rerere.rikkahub.ui.components.render.RenderLruCache
import me.rerere.rikkahub.ui.components.render.RenderLruCacheStats
import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssCascade
import me.rerere.rikkahub.ui.components.richtext.compiler.RichHtmlAstCompiler
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssParser
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssRule
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssRuleIndex
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssSelectorIndexKey
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssSelectorMatcher
import me.rerere.rikkahub.ui.components.richtext.compiler.RichVisualHintAnalyzer
import com.helger.css.reader.CSSReaderDeclarationList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class RichHtmlCompileOptions(
    val viewportWidthDp: Float = 360f,
    val darkTheme: Boolean = false,
    val budget: RichHtmlBudget = RichHtmlBudget(),
)

internal object RichHtmlCompiler {
    private val cache = RenderLruCache<String, RichHtmlRenderModel>(maxEntries = 128)
    private val transientCache = RenderLruCache<String, RichHtmlRenderModel>(maxEntries = 24)
    private val inFlightLock = Any()
    private val inFlightCompiles = mutableMapOf<String, InFlightCompile>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val compileDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)
    private val compileScope = CoroutineScope(SupervisorJob() + compileDispatcher)

    fun getCached(
        html: String,
        options: RichHtmlCompileOptions = RichHtmlCompileOptions(),
        cacheMode: RichHtmlCompileCacheMode = RichHtmlCompileCacheMode.Persistent,
    ): RichHtmlRenderModel? = cacheFor(cacheMode).get(cacheKey(html, options))

    fun getCachedById(
        id: String,
        options: RichHtmlCompileOptions = RichHtmlCompileOptions(),
        cacheMode: RichHtmlCompileCacheMode = RichHtmlCompileCacheMode.Persistent,
    ): RichHtmlRenderModel? = cacheFor(cacheMode).get(cacheKeyFromId(id, options))

    fun compile(
        html: String,
        options: RichHtmlCompileOptions = RichHtmlCompileOptions(),
    ): RichHtmlRenderModel {
        val key = cacheKey(html, options)
        return cache.getOrPut(key) {
            val startNanos = System.nanoTime()
            compileUncached(html, options).also { model ->
                RichHtmlRenderTelemetry.recordCompileParity(
                    id = model.id,
                    viewportWidthDp = options.viewportWidthDp,
                    compileTimeMs = (System.nanoTime() - startNanos) / 1_000_000,
                    unsupported = model.unsupported.joinToString(",").ifBlank { null },
                )
            }
        }
    }

    suspend fun compileAsync(
        html: String,
        options: RichHtmlCompileOptions = RichHtmlCompileOptions(),
        cacheMode: RichHtmlCompileCacheMode = RichHtmlCompileCacheMode.Persistent,
    ): RichHtmlRenderModel {
        currentCoroutineContext().ensureActive()
        val key = cacheKey(html, options)
        val targetCache = cacheFor(cacheMode)
        targetCache.get(key)?.let { return it }
        val inFlightKey = "${cacheMode.name}:$key"
        val telemetryId = renderTextCacheKey(html)
        val requestedAtMs = System.currentTimeMillis()
        val createdOrJoined = synchronized(inFlightLock) {
            targetCache.get(key)?.let { return it }
            inFlightCompiles[inFlightKey]?.let { existing ->
                existing.waiters += 1
                existing to true
            } ?: run {
                val enqueuedAtMs = System.currentTimeMillis()
                val deferred = compileScope.async {
                    RichHtmlRenderTelemetry.recordCompileQueueWait(
                        id = telemetryId,
                        viewportWidthDp = options.viewportWidthDp,
                        queueWaitMs = (System.currentTimeMillis() - enqueuedAtMs).coerceAtLeast(0L),
                        joinedInFlight = false,
                        cacheMode = cacheMode.name,
                    )
                    withTimeout(RICH_HTML_COMPILE_TIMEOUT_MS) {
                        val job = currentCoroutineContext()[Job]
                        job?.ensureActive()
                        val startNanos = System.nanoTime()
                        val model = compileUncached(html, options) {
                            job?.ensureActive()
                        }.also {
                            RichHtmlRenderTelemetry.recordCompileParity(
                                id = it.id,
                                viewportWidthDp = options.viewportWidthDp,
                                compileTimeMs = (System.nanoTime() - startNanos) / 1_000_000,
                                unsupported = it.unsupported.joinToString(",").ifBlank { null },
                            )
                        }
                        job?.ensureActive()
                        targetCache.putIfAbsent(key, model)
                    }
                }
                val created = InFlightCompile(deferred = deferred, waiters = 1)
                inFlightCompiles[inFlightKey] = created
                deferred.invokeOnCompletion {
                    synchronized(inFlightLock) {
                        if (inFlightCompiles[inFlightKey] === created && created.waiters <= 0) {
                            inFlightCompiles.remove(inFlightKey)
                        }
                    }
                }
                created to false
            }
        }
        val flight = createdOrJoined.first
        val joinedInFlight = createdOrJoined.second
        if (joinedInFlight) {
            RichHtmlRenderTelemetry.recordCompileQueueWait(
                id = telemetryId,
                viewportWidthDp = options.viewportWidthDp,
                queueWaitMs = (System.currentTimeMillis() - requestedAtMs).coerceAtLeast(0L),
                joinedInFlight = true,
                cacheMode = cacheMode.name,
            )
        }
        return try {
            flight.deferred.await()
        } finally {
            synchronized(inFlightLock) {
                flight.waiters -= 1
                if (inFlightCompiles[inFlightKey] === flight && flight.waiters <= 0) {
                    if (cacheMode == RichHtmlCompileCacheMode.Transient && !flight.deferred.isCompleted) {
                        flight.deferred.cancel()
                        inFlightCompiles.remove(inFlightKey)
                    } else if (flight.deferred.isCompleted) {
                        inFlightCompiles.remove(inFlightKey)
                    }
                }
            }
        }
    }

    fun cacheStats(): RenderLruCacheStats = cache.stats()

    fun clearCacheForTest() {
        cache.clear()
        transientCache.clear()
        synchronized(inFlightLock) {
            inFlightCompiles.clear()
        }
    }

    fun validate(html: String) {
        val model = compile(html)
        check(model.unsupported.none { it == RichUnsupportedReason.UnsafeHtml }) {
            "Rich HTML is not safe for native rendering"
        }
    }

    private fun compileUncached(
        html: String,
        options: RichHtmlCompileOptions,
        cancellationCheck: () -> Unit = {},
    ): RichHtmlRenderModel {
        val totalStartNanos = System.nanoTime()
        var parseMs = 0L
        var cascadeMs = 0L
        var domCompileMs = 0L
        var renderModelMs = 0L
        var optimizerMs = 0L
        cancellationCheck()
        val safety = inspectRichHtmlSafety(html, options.budget)
        if (!safety.safeForNative) {
            recordCompileMediaSafetyReport(html)
            RichHtmlRenderTelemetry.recordFallback(
                stage = RichHtmlFallbackStage.Compile,
                reason = safety.reason?.name ?: "UnsafeHtml",
            )
            return RichHtmlRenderModel(
                id = renderTextCacheKey(html),
                blocks = listOf(
                    RichUnsupportedBlock(
                        blockId = "unsafe",
                        style = ComputedStyle.Initial,
                        reason = RichUnsupportedReason.UnsafeHtml,
                        previewText = html.previewText(),
                    )
                ),
                unsupported = listOf(RichUnsupportedReason.UnsafeHtml),
                )
            }

        val parseStartNanos = System.nanoTime()
        val document = runCatching { RichHtmlAstCompiler.parseBody(html) }
            .getOrElse {
                RichHtmlRenderTelemetry.recordFallback(
                    stage = RichHtmlFallbackStage.Compile,
                    reason = "ParseFailure",
                )
                return RichHtmlRenderModel(
                    id = renderTextCacheKey(html),
                    blocks = listOf(
                        RichUnsupportedBlock(
                            blockId = "parse",
                            style = ComputedStyle.Initial,
                            reason = RichUnsupportedReason.Unknown,
                            previewText = html.previewText(),
                        )
                    ),
                    unsupported = listOf(RichUnsupportedReason.Unknown),
                )
            }
        parseMs = (System.nanoTime() - parseStartNanos) / 1_000_000

        cancellationCheck()
        val cascadeStartNanos = System.nanoTime()
        val resolver = StyleResolver.from(document, options, cancellationCheck)
        cascadeMs = (System.nanoTime() - cascadeStartNanos) / 1_000_000
        val compiler = CompilerRun(resolver, cancellationCheck)
        val domStartNanos = System.nanoTime()
        val roots = document.body().children()
            .filterNot { it.tagName().equals("style", ignoreCase = true) || isDangerousRichHtmlTag(it.tagName()) }
        val blocks = roots.mapIndexedNotNull { index, root ->
            cancellationCheck()
            compiler.compileElement(
                element = root,
                parentStyle = ComputedStyle.Initial,
                parentVars = resolver.rootVariables,
                blockId = "r$index",
                forceContainer = true,
            )
        }
        domCompileMs = (System.nanoTime() - domStartNanos) / 1_000_000

        val renderModelStartNanos = System.nanoTime()
        val animationBudget = applyAnimationBudget(blocks, options.budget.maxNativeAnimatedElements)
        val sourceStyleHtml = if (compiler.sourceHtmlByBlockId.isNotEmpty()) {
            document.select("style").joinToString("\n") { styleElement -> styleElement.outerHtml() }
        } else {
            ""
        }
        val baseModel = RichHtmlRenderModel(
            id = renderTextCacheKey(html),
            blocks = animationBudget.blocks,
            unsupported = compiler.unsupported.toList(),
            visualHints = (resolver.visualHints + compiler.visualHints + animationBudget.visualHints).distinct(),
            animationStats = animationBudget.stats,
            sourceHtmlByBlockId = compiler.sourceHtmlByBlockId.toMap(),
            sourceStyleHtml = sourceStyleHtml,
        )
        renderModelMs = (System.nanoTime() - renderModelStartNanos) / 1_000_000

        val selectorStats = resolver.telemetryStats()
        RichHtmlRenderTelemetry.recordCssCascadeStats(
            id = baseModel.id,
            ruleCount = selectorStats.ruleCount,
            idRuleCount = selectorStats.idRuleCount,
            classRuleCount = selectorStats.classRuleCount,
            tagRuleCount = selectorStats.tagRuleCount,
            attrRuleCount = selectorStats.attrRuleCount,
            pseudoRuleCount = selectorStats.pseudoRuleCount,
            universalRuleCount = selectorStats.universalRuleCount,
            complexRuleCount = selectorStats.complexRuleCount,
            unsupportedSelectorCount = selectorStats.unsupportedSelectorCount,
            elementCount = selectorStats.elementCount,
            averageCandidateRules = selectorStats.averageCandidateRules,
            p95CandidateRules = selectorStats.p95CandidateRules,
            legacyScanCount = selectorStats.legacyScanCount,
            legacyVerificationSkippedCount = selectorStats.legacyVerificationSkippedCount,
            indexMismatchCount = selectorStats.indexMismatchCount,
            parserFallbackCount = selectorStats.parserFallbackCount,
            selectorMatchMs = selectorStats.selectorMatchMs,
            highCostSelectorCategories = selectorStats.highCostSelectorCategories,
        )

        val optimizerStartNanos = System.nanoTime()
        val richContentDocument = runCatching {
            buildRichContentDocumentFromParsedHtml(html, document, analyzeRichHtml(html))
        }.recoverCatching {
            buildRichContentDocumentFromHtml(html)
        }.getOrNull()
        val astSubtreePlan = richContentDocument?.subtreeRoutePlan()
        val subtreePlan = if (astSubtreePlan?.allowsSnapshotIslandOptimization == true) {
            RichSubtreeRoutePlanner.plan(baseModel)
        } else {
            RichSubtreeRoutePlanner.skippedByAst(baseModel, astSubtreePlan)
        }
        val islandModel = RichSnapshotIslandOptimizer.optimize(baseModel, subtreePlan, astSubtreePlan)
        RichHtmlRenderTelemetry.recordSnapshotIslandPlan(
            id = baseModel.id,
            plan = subtreePlan,
            appliedCount = islandModel.snapshotIslandStats.appliedCount,
        )

        val astTextFlowPlan = richContentDocument?.textFlowPlan() ?: runCatching {
            buildRichContentTextFlowPlanFromHtml(html)
        }.getOrNull()
        val astDirectDocument = richContentDocument ?: runCatching {
            buildRichContentDocumentFromHtml(html)
        }.getOrNull()
        val optimized = astDirectDocument
            ?.let { RichTextFlowAstLowerer.lower(it, islandModel, html, parsedHtml = document) }
            ?: RichTextFlowOptimizer.optimize(islandModel, astTextFlowPlan)
        optimizerMs = (System.nanoTime() - optimizerStartNanos) / 1_000_000
        RichHtmlRenderTelemetry.recordCompilePhases(
            id = optimized.id,
            viewportWidthDp = options.viewportWidthDp,
            parseMs = parseMs,
            cascadeMs = cascadeMs,
            domCompileMs = domCompileMs,
            renderModelMs = renderModelMs,
            optimizerMs = optimizerMs,
            totalMs = (System.nanoTime() - totalStartNanos) / 1_000_000,
        )
        return optimized
    }

    private fun cacheKey(
        html: String,
        options: RichHtmlCompileOptions,
    ): String = "${renderTextCacheKey(html)}:$options"

    private fun cacheKeyFromId(
        id: String,
        options: RichHtmlCompileOptions,
    ): String = "$id:$options"

    private fun cacheFor(mode: RichHtmlCompileCacheMode): RenderLruCache<String, RichHtmlRenderModel> {
        return when (mode) {
            RichHtmlCompileCacheMode.Persistent -> cache
            RichHtmlCompileCacheMode.Transient -> transientCache
        }
    }
}

internal enum class RichHtmlCompileCacheMode {
    Persistent,
    Transient,
}

private data class InFlightCompile(
    val deferred: Deferred<RichHtmlRenderModel>,
    var waiters: Int,
)

private data class AnimationBudgetResult(
    val blocks: List<RichBlock>,
    val stats: RichAnimationStats,
    val visualHints: List<RichVisualHint>,
)

private fun applyAnimationBudget(
    blocks: List<RichBlock>,
    maxNativeAnimatedElements: Int,
): AnimationBudgetResult {
    val budget = AnimationBudgetRun(maxNativeAnimatedElements.coerceAtLeast(0))
    val mapped = blocks.map { budget.visit(it) }
    return AnimationBudgetResult(
        blocks = mapped,
        stats = budget.toStats(),
        visualHints = if (budget.budgetExceededCount > 0) listOf(RichVisualHint.AnimationBudgetExceeded) else emptyList(),
    )
}

private class AnimationBudgetRun(
    private val maxNativeAnimatedElements: Int,
) {
    private var nativeAllowed = 0
    var animatedElementCount = 0
        private set
    var nativeAnimatedCount = 0
        private set
    var staticizedCount = 0
        private set
    var infiniteCount = 0
        private set
    var layoutAnimationCount = 0
        private set
    var transitionCount = 0
        private set
    var dependentVisibilityCount = 0
        private set
    var budgetExceededCount = 0
        private set
    var snapshotCandidateCount = 0
        private set
    var multiKeyframeCount = 0
        private set
    var unsupportedPropertyCount = 0
        private set

    fun visit(block: RichBlock): RichBlock {
        val style = budgetStyle(block.style)
        return when (block) {
            is RichContainerBlock -> block.copy(
                style = style,
                children = block.children.map(::visit),
            )
            is RichTextBlock -> block.copy(style = style)
            is RichTextFlowBlock -> block.copy(style = style)
            is RichImageBlock -> block.copy(style = style)
            is RichTableBlock -> block.copy(style = style)
            is RichSvgBlock -> block.copy(style = style)
            is RichMathBlock -> block.copy(style = style)
            is RichButtonBlock -> block.copy(
                style = style,
                children = block.children.map(::visit),
            )
            is RichDetailsBlock -> block.copy(
                style = style,
                children = block.children.map(::visit),
            )
            is RichSnapshotIslandBlock -> block.copy(style = style)
            is RichUnsupportedBlock -> block.copy(style = style)
        }
    }

    private fun budgetStyle(style: ComputedStyle): ComputedStyle {
        if (style.transition.isDeclared) transitionCount += 1
        val animation = style.animation
        if (!animation.isDeclared) return style
        animatedElementCount += 1
        if (animation.isInfinite) infiniteCount += 1
        if (animation.hasLayoutProperty) layoutAnimationCount += 1
        if (animation.mayHideStaticContent) dependentVisibilityCount += 1
        multiKeyframeCount += animation.multiKeyframeCount
        unsupportedPropertyCount += animation.unsupportedPropertyCount
        if (animation.hasLayoutProperty || animation.unsupportedPropertyCount > 0 ||
            (animation.mayHideStaticContent && animation.nativeAnimation == null)
        ) {
            snapshotCandidateCount += 1
        }
        if (animation.nativeAnimation == null) {
            staticizedCount += 1
            return style
        }
        if (nativeAllowed < maxNativeAnimatedElements) {
            nativeAllowed += 1
            nativeAnimatedCount += 1
            return style
        }
        budgetExceededCount += 1
        snapshotCandidateCount += 1
        staticizedCount += 1
        return style.copy(animation = animation.copy(nativeAnimation = null))
    }

    fun toStats(): RichAnimationStats {
        val strategy = when {
            animatedElementCount == 0 && transitionCount == 0 -> RichAnimationStrategy.None
            budgetExceededCount > 0 -> RichAnimationStrategy.BudgetExceededStaticized
            nativeAnimatedCount > 0 -> RichAnimationStrategy.NativeAnimated
            else -> RichAnimationStrategy.Staticized
        }
        return RichAnimationStats(
            strategy = strategy,
            animatedElementCount = animatedElementCount,
            nativeAnimatedCount = nativeAnimatedCount,
            staticizedCount = staticizedCount,
            infiniteCount = infiniteCount,
            layoutAnimationCount = layoutAnimationCount,
            transitionCount = transitionCount,
            dependentVisibilityCount = dependentVisibilityCount,
            budgetExceededCount = budgetExceededCount,
            snapshotCandidateCount = snapshotCandidateCount,
            multiKeyframeCount = multiKeyframeCount,
            unsupportedPropertyCount = unsupportedPropertyCount,
        )
    }
}

private class CompilerRun(
    private val resolver: StyleResolver,
    private val cancellationCheck: () -> Unit,
) {
    val unsupported = linkedSetOf<RichUnsupportedReason>()
    val visualHints = linkedSetOf<RichVisualHint>()
    val sourceHtmlByBlockId = linkedMapOf<String, String>()

    fun compileElement(
        element: Element,
        parentStyle: ComputedStyle,
        parentVars: Map<String, String>,
        blockId: String,
        forceContainer: Boolean = false,
    ): RichBlock? {
        cancellationCheck()
        val tag = element.tagName().lowercase()
        if (tag == "style" || isDangerousRichHtmlTag(tag)) return null

        val resolved = resolver.resolve(element, parentStyle, parentVars)
        visualHints += resolved.visualHints
        val style = resolved.style
        if (style.display == RichDisplay.None || style.visibility == RichVisibility.Hidden) return null

        val block = when (tag) {
            "img" -> compileImage(element, style, blockId)
            "svg" -> compileSvg(element, style, blockId)
            "table" -> compileTable(element, style, blockId, resolved.variables)
            "button" -> compileButton(element, style, blockId, resolved.variables)
            "details" -> compileDetails(element, style, blockId, resolved.variables)
            "pre" -> RichTextBlock(blockId, style, AnnotatedString(element.selectFirst("code")?.wholeText() ?: element.wholeText()))
            "hr" -> RichContainerBlock(blockId, style.copy(minHeight = 1.dp, backgroundColor = style.color ?: Color.Gray), emptyList(), tag)
            "br" -> RichTextBlock(blockId, style, AnnotatedString("\n"))
            "ul", "ol" -> compileList(element, style, blockId, resolved.variables, ordered = tag == "ol")
            else -> {
                if (isMathFormulaContainer(element)) {
                    val latex = extractLatex(element)
                    if (latex.isNotBlank()) {
                        RichMathBlock(blockId, style, latex, inline = false)
                    } else if (
                        !forceContainer &&
                        !style.display.isFlexContainer() &&
                        !style.display.isGridContainer() &&
                        !style.display.needsInlineContainerDisplay() &&
                        isInlineOnly(element) &&
                        element.hasInlineRenderableContent(style) &&
                        !hasInlineChildRequiringOwnBlock(element, style, resolved.variables)
                    ) {
                        compileTextBlock(element, style, blockId, resolved.variables)
                    } else {
                        val children = compileChildren(element, style, resolved.variables, blockId)
                        if (children.isEmpty() && element.ownText().isNotBlank()) {
                            compileTextBlock(element, style, blockId, resolved.variables)
                        } else if (children.isEmpty() && !style.hasVisualBox()) {
                            null
                        } else {
                            RichContainerBlock(blockId, style, children, tag)
                        }
                    }
                } else if (
                    !forceContainer &&
                    !style.display.isFlexContainer() &&
                    !style.display.isGridContainer() &&
                    !style.display.needsInlineContainerDisplay() &&
                    isInlineOnly(element) &&
                    element.hasInlineRenderableContent(style) &&
                    !hasInlineChildRequiringOwnBlock(element, style, resolved.variables)
                ) {
                    compileTextBlock(element, style, blockId, resolved.variables)
                } else {
                    val children = compileChildren(element, style, resolved.variables, blockId)
                    if (children.isEmpty() && element.ownText().isNotBlank()) {
                        compileTextBlock(element, style, blockId, resolved.variables)
                    } else if (children.isEmpty() && !style.hasVisualBox()) {
                        null
                    } else {
                        RichContainerBlock(blockId, style, children, tag)
                    }
                }
            }
        }
        return block?.also {
            if (it.mayNeedSnapshotIslandSource()) {
                sourceHtmlByBlockId[blockId] = element.outerHtml()
            }
        }
    }

    private fun RichBlock.mayNeedSnapshotIslandSource(): Boolean {
        if (style.hasSnapshotIslandStyle()) return true
        return when (this) {
            is RichSvgBlock -> model.visualHints.isNotEmpty() || model.commands.size > 96
            is RichUnsupportedBlock -> reason == RichUnsupportedReason.SvgTooComplex
            is RichContainerBlock,
            is RichTextBlock,
            is RichTextFlowBlock,
            is RichImageBlock,
            is RichTableBlock,
            is RichMathBlock,
            is RichButtonBlock,
            is RichDetailsBlock,
            is RichSnapshotIslandBlock -> false
        }
    }

    private fun ComputedStyle.hasSnapshotIslandStyle(): Boolean {
        return maskImage != null ||
            clipPath != null ||
            backdropFilter != RichCssFilter.None ||
            cssFilter.requiresVisualApproximation ||
            mixBlendMode ||
            backgroundLayers.size > 1 ||
            extraBackgroundLayers > 0 ||
            backgroundUrl != null ||
            backgroundImage is RichBackgroundImage.ConicGradient
    }

    private fun compileChildren(
        element: Element,
        style: ComputedStyle,
        variables: Map<String, String>,
        blockId: String,
    ): List<RichBlock> {
        val result = mutableListOf<RichBlock>()
        val inlineBuffer = mutableListOf<Node>()

        fun flushInline() {
            cancellationCheck()
            if (inlineBuffer.isEmpty()) return
            val content = buildInlineContent(inlineBuffer, style, variables, "$blockId-t${result.size}")
            if (content.text.text.isNotBlank()) {
                result += RichTextBlock(
                    blockId = "$blockId-t${result.size}",
                    style = style.anonymousInlineTextStyle(),
                    content = content.text,
                    inlineMath = content.inlineMath,
                    inlinePaints = content.inlinePaints,
                    inlineBoxes = content.inlineBoxes,
                )
            }
            inlineBuffer.clear()
        }

        element.childNodes().forEachIndexed { index, node ->
            cancellationCheck()
            when (node) {
                is TextNode -> {
                    if (normalizeText(node.wholeText, style.whiteSpace).isNotBlank()) inlineBuffer += node
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "style" || isDangerousRichHtmlTag(tag)) return@forEachIndexed
                    val childStyle = resolver.resolve(node, style, variables).style
                    if (childStyle.participatesInParentInlineFlow(tag, parentStyle = style) && !isMathFormulaContainer(node)) {
                        inlineBuffer.add(node)
                    } else {
                        flushInline()
                        compileElement(
                            element = node,
                            parentStyle = style,
                            parentVars = variables,
                            blockId = "$blockId-$index",
                        )?.let(result::add)
                    }
                }
            }
        }
        flushInline()
        return result
    }

    private fun hasInlineChildRequiringOwnBlock(
        element: Element,
        style: ComputedStyle,
        variables: Map<String, String>,
    ): Boolean {
        return element.children().any { child ->
            val tag = child.tagName().lowercase()
            if (!isInlineTag(tag) || isMathFormulaContainer(child)) return@any false
            val childStyle = resolver.resolve(child, style, variables).style
            (childStyle.display.needsInlineContainerDisplay() && !childStyle.canRenderAsInlineRichBox()) ||
                hasInlineChildRequiringOwnBlock(child, childStyle, variables)
        }
    }

    private fun compileTextBlock(
        element: Element,
        style: ComputedStyle,
        blockId: String,
        variables: Map<String, String>,
    ): RichTextBlock {
        val content = buildInlineContent(element.childNodes(), style, variables, blockId)
        return RichTextBlock(blockId, style, content.text, content.inlineMath, content.inlinePaints, content.inlineBoxes)
    }

    private fun compileImage(element: Element, style: ComputedStyle, blockId: String): RichBlock? {
        val src = element.attr("src")
        val safeSrc = safeRichMediaSource(src, RichMediaKind.Image) ?: return null
        val imageStyle = style.withImagePresentationAttributes(element)
        return RichImageBlock(blockId, imageStyle, safeSrc, element.attr("alt").takeIf { it.isNotBlank() })
    }

    private fun ComputedStyle.withImagePresentationAttributes(element: Element): ComputedStyle {
        val attrWidth = element.attr("width").parseHtmlImageDimension()
        val attrHeight = element.attr("height").parseHtmlImageDimension()
        if (attrWidth == null && attrHeight == null) return this

        return copy(
            width = if (width == RichSize.Auto && attrWidth != null) attrWidth else width,
            height = if (height == RichSize.Auto && attrHeight != null) attrHeight else height,
        )
    }

    private fun String.parseHtmlImageDimension(): RichSize? {
        val value = trim().takeIf { it.isNotBlank() } ?: return null
        if (value.endsWith("%")) {
            val percent = value.dropLast(1).trim().toFloatOrNull() ?: return null
            return RichSize.Fraction((percent / 100f).coerceIn(0f, 1f))
        }
        val px = value.removeSuffix("px").trim().toFloatOrNull() ?: return null
        return px.takeIf { it > 0f }?.dp?.let(RichSize::DpSize)
    }

    private fun compileButton(
        element: Element,
        style: ComputedStyle,
        blockId: String,
        variables: Map<String, String>,
    ): RichButtonBlock {
        val action = extractInputActionFromElement(
            dataSend = element.attr("data-send"),
            dataInput = element.attr("data-input"),
            value = element.attr("value"),
            onclick = element.attr("onclick"),
            fallbackText = element.text(),
        )
        val content = buildInlineContent(element.childNodes(), style, variables, "$blockId-label")
        val hasLabel = content.text.text.isNotBlank()
        val label = content.text
            .takeIf { hasLabel }
            ?: AnnotatedString(action.ifBlank { "继续" })
        val children = if (style.display.isFlexContainer() || style.display.isGridContainer()) {
            compileChildren(element, style, variables, "$blockId-content")
        } else {
            emptyList()
        }
        return RichButtonBlock(
            blockId = blockId,
            style = style,
            label = label,
            action = action,
            inlineMath = if (hasLabel) content.inlineMath else emptyList(),
            inlinePaints = if (hasLabel) content.inlinePaints else emptyList(),
            inlineBoxes = if (hasLabel) content.inlineBoxes else emptyList(),
            children = children,
        )
    }

    private fun compileDetails(
        element: Element,
        style: ComputedStyle,
        blockId: String,
        variables: Map<String, String>,
    ): RichDetailsBlock {
        val summary = element.children().firstOrNull { it.tagName().equals("summary", ignoreCase = true) }
        val summaryText = summary?.let { buildInlineContent(it.childNodes(), style, variables).text }
            ?.takeIf { it.text.isNotBlank() }
            ?: AnnotatedString("Details")
        val body = element.children()
            .filterNot { it.tagName().equals("summary", ignoreCase = true) }
            .mapIndexedNotNull { index, child ->
                compileElement(child, style, variables, "$blockId-d$index")
            }
        return RichDetailsBlock(blockId, style, summaryText, body, element.hasAttr("open"))
    }

    private fun compileList(
        element: Element,
        style: ComputedStyle,
        blockId: String,
        variables: Map<String, String>,
        ordered: Boolean,
    ): RichContainerBlock {
        val listItems = element.children().filter { it.tagName().equals("li", ignoreCase = true) }
        val reversed = ordered && element.hasAttr("reversed")
        val start = if (ordered) {
            element.attr("start").toIntOrNull() ?: if (reversed) listItems.size else 1
        } else {
            1
        }
        val attrStyleType = if (ordered) parseOrderedListTypeAttribute(element.attr("type")) else null
        val items = listItems
            .mapIndexed { index, item ->
                val itemStyle = resolver.resolve(item, style, variables)
                val listDepth = item.parents().count { parent ->
                    parent.tagName().equals("ul", ignoreCase = true) || parent.tagName().equals("ol", ignoreCase = true)
                }.coerceAtLeast(1)
                val itemIndent = if (itemStyle.style.listStylePosition == RichListStylePosition.Inside) {
                    4.dp
                } else {
                    10.dp * listDepth.toFloat()
                }
                val itemBlockStyle = itemStyle.style.copy(
                    margin = itemStyle.style.margin.copy(left = itemStyle.style.margin.left + itemIndent),
                )
                val ordinal = if (reversed) start - index else start + index
                val sectionOrdinal = index + 1
                val styleType = if (itemStyle.style.listStyleType == RichListStyleType.Default) {
                    attrStyleType ?: itemStyle.style.listStyleType
                } else {
                    itemStyle.style.listStyleType
                }
                val effectiveItemStyle = itemBlockStyle.copy(listStyleType = styleType)
                    .resolveCounterPlaceholders(ordinal, sectionOrdinal)
                val marker = listMarker(ordinal, ordered, styleType)
                val inlineNodes = item.childNodes().filterNot { child ->
                    child is Element && child.tagName().lowercase() in setOf("ul", "ol")
                }
                val inlineContent = buildInlineContent(inlineNodes, effectiveItemStyle, itemStyle.variables, "$blockId-li$index")
                val markerOffset = marker.length
                val content = buildAnnotatedString {
                    if (marker.isNotBlank()) append(marker)
                    append(inlineContent.text)
                }
                val textBlock = RichTextBlock(
                    blockId = "$blockId-li$index",
                    style = effectiveItemStyle,
                    content = content,
                    inlineMath = inlineContent.inlineMath.shiftInlineMath(markerOffset),
                    inlinePaints = inlineContent.inlinePaints.shiftInlinePaints(markerOffset),
                    inlineBoxes = inlineContent.inlineBoxes.shiftInlineBoxes(markerOffset),
                    listMarker = marker.takeIf { it.isNotBlank() },
                )
                val nestedLists = item.children()
                    .filter { child -> child.tagName().lowercase() in setOf("ul", "ol") }
                    .mapIndexedNotNull { childIndex, child ->
                        compileElement(child, effectiveItemStyle, itemStyle.variables, "$blockId-li$index-n$childIndex")
                    }
                if (nestedLists.isEmpty()) {
                    textBlock
                } else {
                    RichContainerBlock(
                        blockId = "$blockId-li$index",
                        style = ComputedStyle.Initial.copy(display = RichDisplay.Block),
                        children = listOf(textBlock) + nestedLists,
                        tagName = "li",
                    )
                }
            }
        return RichContainerBlock(blockId, style.copy(display = RichDisplay.Block), items, if (ordered) "ol" else "ul")
    }

    private fun compileTable(
        element: Element,
        style: ComputedStyle,
        blockId: String,
        variables: Map<String, String>,
    ): RichTableBlock {
        val captionElement = element.children().firstOrNull { it.tagName().equals("caption", ignoreCase = true) }
        val captionResolved = captionElement?.let { resolver.resolve(it, style, variables) }
        val caption = captionElement
            ?.let { buildInlineContent(it.childNodes(), captionResolved?.style ?: style, captionResolved?.variables ?: variables).text }
            ?.takeIf { it.text.isNotBlank() }
        val explicitSections = element.children()
            .filter { it.tagName().lowercase() in setOf("thead", "tbody", "tfoot") }
            .mapNotNull { section ->
                val sectionStyle = resolver.resolve(section, style, variables)
                val rows = section.children()
                    .filter { it.tagName().equals("tr", ignoreCase = true) }
                    .mapIndexed { rowIndex, row ->
                        compileTableRow(row, sectionStyle.style, sectionStyle.variables, "$blockId-${section.tagName()}-$rowIndex")
                    }
                    .filter { it.isNotEmpty() }
                rows.takeIf { it.isNotEmpty() }?.let {
                    RichTableSection(
                        type = when (section.tagName().lowercase()) {
                            "thead" -> RichTableSectionType.Head
                            "tfoot" -> RichTableSectionType.Foot
                            else -> RichTableSectionType.Body
                        },
                        rows = it,
                    )
                }
            }
        val fallbackRows = if (explicitSections.isEmpty()) {
            element.children()
                .filter { it.tagName().equals("tr", ignoreCase = true) }
                .mapIndexed { rowIndex, row -> compileTableRow(row, style, variables, "$blockId-r$rowIndex") }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val sections = explicitSections.ifEmpty {
            listOfNotNull(RichTableSection(RichTableSectionType.Body, fallbackRows).takeIf { fallbackRows.isNotEmpty() })
        }
        val headRows = sections.firstOrNull { it.type == RichTableSectionType.Head }?.rows
        val firstHeaderRow = headRows?.firstOrNull()
            ?: fallbackRows.firstOrNull { row -> row.any { it.isHeader } }
        val bodyRows = sections
            .filterNot { it.type == RichTableSectionType.Head }
            .flatMap { it.rows }
            .ifEmpty { fallbackRows.filterNot { it === firstHeaderRow } }
        return RichTableBlock(
            blockId = blockId,
            style = style,
            headers = firstHeaderRow.orEmpty(),
            rows = bodyRows,
            caption = caption,
            captionStyle = captionResolved?.style,
            sections = sections,
        )
    }

    private fun compileTableRow(
        row: Element,
        parentStyle: ComputedStyle,
        variables: Map<String, String>,
        blockId: String,
    ): List<RichTableCell> {
        val resolved = resolver.resolve(row, parentStyle, variables)
        return row.children()
            .filter { it.tagName().lowercase() in setOf("th", "td") }
            .mapIndexed { cellIndex, cell ->
                compileTableCell(cell, resolved.style, resolved.variables, "$blockId-c$cellIndex")
            }
    }

    private fun compileTableCell(
        cell: Element,
        parentStyle: ComputedStyle,
        variables: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") blockId: String,
    ): RichTableCell {
        val resolved = resolver.resolve(cell, parentStyle, variables)
        val colspan = cell.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
        val rowspan = cell.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (colspan > 1 && rowspan > 1) visualHints += RichVisualHint.TableComplexSpan
        return RichTableCell(
            content = buildInlineContent(cell.childNodes(), resolved.style, resolved.variables).text,
            style = resolved.style,
            colspan = colspan,
            rowspan = rowspan,
            isHeader = cell.tagName().equals("th", ignoreCase = true),
        )
    }

    private fun compileSvg(element: Element, style: ComputedStyle, blockId: String): RichBlock {
        val svgSource = element.outerHtml()
        if (RichSvgFallbackPolicy.containsRuntimeSvg(svgSource)) {
            RichHtmlRenderTelemetry.recordSvgRoute(
                id = renderTextCacheKey(svgSource),
                route = RichSvgRoute.DynamicPreview.name,
                reason = "runtime-svg",
                commandCount = 0,
                visualHintCount = 0,
                androidSvgAvailable = RichAndroidSvgSpike.Available,
            )
            RichHtmlRenderTelemetry.recordFallback(
                stage = RichHtmlFallbackStage.Compile,
                reason = RichUnsupportedReason.DynamicRuntime.name,
            )
            unsupported += RichUnsupportedReason.DynamicRuntime
            return RichUnsupportedBlock(blockId, style, RichUnsupportedReason.DynamicRuntime, "SVG")
        }
        val svg = RichSvgCompiler.compile(element, cancellationCheck)
        return if (svg == null) {
            RichHtmlRenderTelemetry.recordFallback(
                stage = RichHtmlFallbackStage.Compile,
                reason = RichUnsupportedReason.SvgTooComplex.name,
            )
            unsupported += RichUnsupportedReason.SvgTooComplex
            RichUnsupportedBlock(blockId, style, RichUnsupportedReason.SvgTooComplex, element.text().ifBlank { "SVG" })
        } else {
            visualHints += svg.visualHints
            val svgReport = RichSvgFallbackPolicy.decide(svg, svgSource)
            RichHtmlRenderTelemetry.recordSvgRoute(
                id = renderTextCacheKey(svgSource),
                route = svgReport.route.name,
                reason = svgReport.reason,
                commandCount = svgReport.commandCount,
                visualHintCount = svgReport.visualHintCount,
                androidSvgAvailable = svgReport.androidSvgAvailable,
            )
            when (svgReport.route) {
                RichSvgRoute.DynamicPreview -> {
                    RichHtmlRenderTelemetry.recordFallback(
                        stage = RichHtmlFallbackStage.Compile,
                        reason = RichUnsupportedReason.DynamicRuntime.name,
                    )
                    unsupported += RichUnsupportedReason.DynamicRuntime
                    RichUnsupportedBlock(blockId, style, RichUnsupportedReason.DynamicRuntime, "SVG")
                }

                RichSvgRoute.NativeIr,
                RichSvgRoute.SnapshotIsland,
                RichSvgRoute.AndroidSvgSpike -> RichSvgBlock(blockId, style, svg)
            }
        }
    }

    private fun buildInlineContent(
        nodes: List<Node>,
        parentStyle: ComputedStyle,
        variables: Map<String, String>,
        ownerBlockId: String = "inline",
    ): InlineContent {
        val inlineMath = mutableListOf<InlineMathRun>()
        val inlinePaints = mutableListOf<InlineTextPaintRun>()
        val inlineBoxes = mutableListOf<InlineRichBoxRun>()
        val text = buildAnnotatedString {
            parentStyle.beforeContent?.let { append(it) }
            nodes.forEach {
                cancellationCheck()
                appendInlineNode(it, parentStyle, variables, inlineMath, inlinePaints, inlineBoxes, ownerBlockId)
            }
            parentStyle.afterContent?.let { append(it) }
        }
        return InlineContent(text, inlineMath, inlinePaints, inlineBoxes)
    }

    private fun AnnotatedString.Builder.appendInlineNode(
        node: Node,
        parentStyle: ComputedStyle,
        variables: Map<String, String>,
        inlineMath: MutableList<InlineMathRun>,
        inlinePaints: MutableList<InlineTextPaintRun>,
        inlineBoxes: MutableList<InlineRichBoxRun>,
        ownerBlockId: String,
    ) {
        cancellationCheck()
        when (node) {
            is TextNode -> appendTextWithMath(
                text = normalizeText(node.wholeText, parentStyle.whiteSpace),
                style = parentStyle,
                inlineMath = inlineMath,
            )

            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag == "style" || isDangerousRichHtmlTag(tag)) return
                if (tag == "a" && node.hasAttr("href") && !isSafeRichHtmlHref(node.attr("href"))) {
                    node.childNodes().forEach {
                        appendInlineNode(it, parentStyle, variables, inlineMath, inlinePaints, inlineBoxes, ownerBlockId)
                    }
                    return
                }
                val resolved = resolver.resolve(node, parentStyle, variables)
                val tagStyle = resolved.style.withTagInlineDefaults(tag)
                if (tag == "br") {
                    append("\n")
                    return
                }
                if (isMathFormulaContainer(node)) {
                    val latex = extractLatex(node)
                    val start = length
                    append(latex)
                    if (latex.isNotBlank()) inlineMath += InlineMathRun(start, length, latex)
                    return
                }
                if (tagStyle.shouldRenderAsInlineRichBox(tag)) {
                    appendInlineRichBox(
                        element = node,
                        style = tagStyle,
                        parentStyle = parentStyle,
                        parentVars = variables,
                        inlineBoxes = inlineBoxes,
                        ownerBlockId = ownerBlockId,
                    )
                    return
                }
                withStyle(tagStyle.textSpan().merge(tag.inlineSpanDefaults())) {
                    val start = length
                    if (tag == "code") {
                        append(node.text())
                    } else {
                        node.childNodes().forEach {
                            appendInlineNode(
                                it,
                                tagStyle,
                                resolved.variables,
                                inlineMath,
                                inlinePaints,
                                inlineBoxes,
                                ownerBlockId,
                            )
                        }
                    }
                    tagStyle.inlineTextPaint(start, length)?.let { inlinePaints.add(it) }
                    Unit
                }
            }
        }
    }

    private fun AnnotatedString.Builder.appendInlineRichBox(
        element: Element,
        style: ComputedStyle,
        parentStyle: ComputedStyle,
        parentVars: Map<String, String>,
        inlineBoxes: MutableList<InlineRichBoxRun>,
        ownerBlockId: String,
    ) {
        val blockId = "$ownerBlockId-inline-${inlineBoxes.size}"
        val block = compileElement(
            element = element,
            parentStyle = parentStyle,
            parentVars = parentVars,
            blockId = blockId,
            forceContainer = true,
        )?.withInlineFallbackDisplay() ?: return
        val start = length
        append(INLINE_RICH_BOX_PLACEHOLDER)
        inlineBoxes += InlineRichBoxRun(
            start = start,
            end = length,
            block = block,
            text = normalizeText(element.wholeText(), style.whiteSpace),
            width = style.estimatedInlineBoxWidth(element),
            height = style.estimatedInlineBoxHeight(),
        )
    }

    private fun AnnotatedString.Builder.appendTextWithMath(
        text: String,
        style: ComputedStyle,
        inlineMath: MutableList<InlineMathRun>,
    ) {
        var cursor = 0
        LATEX_INLINE_PATTERN.findAll(text).forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first).applyTextTransform(style.textTransform))
            }
            val latex = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val start = length
            withStyle(style.textSpan()) { append(latex.ifBlank { match.value }) }
            if (latex.isNotBlank()) inlineMath += InlineMathRun(start, length, latex)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor).applyTextTransform(style.textTransform))
    }
}

private data class InlineContent(
    val text: AnnotatedString,
    val inlineMath: List<InlineMathRun>,
    val inlinePaints: List<InlineTextPaintRun>,
    val inlineBoxes: List<InlineRichBoxRun>,
)

private const val INLINE_RICH_BOX_PLACEHOLDER = "\uFFFC"

private fun List<InlineMathRun>.shiftInlineMath(offset: Int): List<InlineMathRun> {
    if (offset == 0) return this
    return map { it.copy(start = it.start + offset, end = it.end + offset) }
}

private fun List<InlineTextPaintRun>.shiftInlinePaints(offset: Int): List<InlineTextPaintRun> {
    if (offset == 0) return this
    return map { it.copy(start = it.start + offset, end = it.end + offset) }
}

private fun List<InlineRichBoxRun>.shiftInlineBoxes(offset: Int): List<InlineRichBoxRun> {
    if (offset == 0) return this
    return map { it.copy(start = it.start + offset, end = it.end + offset) }
}

internal data class ResolvedStyle(
    val style: ComputedStyle,
    val variables: Map<String, String>,
    val visualHints: List<RichVisualHint> = emptyList(),
)

internal class StyleResolver private constructor(
    private val rules: List<CssRule>,
    private val keyframes: Map<String, RichKeyframesSummary>,
    val rootVariables: Map<String, String>,
    private val options: RichHtmlCompileOptions,
    private val cancellationCheck: () -> Unit,
    val visualHints: List<RichVisualHint>,
    private val parserFallbackCount: Int,
) {
    private val legacyVerificationRuleBudget = 64
    private val ruleIndex = CssRuleIndex.from(rules)
    private val selectorStats = CssSelectorTelemetryAccumulator(
        ruleCount = rules.size,
        idRuleCount = ruleIndex.idRules.values.sumOf { it.size },
        classRuleCount = ruleIndex.classRules.values.sumOf { it.size },
        tagRuleCount = ruleIndex.tagRules.values.sumOf { it.size },
        attrRuleCount = ruleIndex.attrRules.values.sumOf { it.size },
        pseudoRuleCount = ruleIndex.pseudoRules.values.sumOf { it.size },
        universalRuleCount = ruleIndex.universalRules.size,
        complexRuleCount = ruleIndex.complexRules.size,
        unsupportedSelectorCount = ruleIndex.unsupportedRules.size,
        highCostSelectorCategories = rules
            .flatMap { rule -> rule.highCostCategories }
            .groupingBy { it }
            .eachCount(),
        parserFallbackCount = parserFallbackCount,
    )

    fun resolve(
        element: Element,
        parentStyle: ComputedStyle,
        parentVars: Map<String, String>,
    ): ResolvedStyle {
        cancellationCheck()
        val matchStartNanos = System.nanoTime()
        val indexedCandidates = ruleIndex.candidateRules(element)
        val indexedMatched = indexedCandidates
            .filter { rule ->
                cancellationCheck()
                rule.selector.matches(element)
            }
            .sortedWith(compareBy<CssRule> { it.specificity }.thenBy { it.order })
        val shouldVerifyLegacy = rules.size <= legacyVerificationRuleBudget
        val legacyMatched = if (shouldVerifyLegacy) {
            rules
                .filter { rule ->
                    cancellationCheck()
                    rule.selector.matches(element)
                }
                .sortedWith(compareBy<CssRule> { it.specificity }.thenBy { it.order })
        } else {
            emptyList()
        }
        val indexMismatch = shouldVerifyLegacy && indexedMatched.map { it.order } != legacyMatched.map { it.order }
        selectorStats.record(
            candidateCount = indexedCandidates.size,
            legacyScanCount = if (shouldVerifyLegacy) rules.size else 0,
            legacyVerificationSkipped = !shouldVerifyLegacy,
            indexMismatch = indexMismatch,
            selectorMatchNanos = System.nanoTime() - matchStartNanos,
        )
        val matched = if (indexMismatch) legacyMatched else indexedMatched
        val declarations = linkedMapOf<String, String>()
        val variables = parentVars.toMutableMap()

        uaDeclarationsFor(element.tagName().lowercase()).forEach { (key, value) ->
            declarations.putCssDeclaration(key, value)
        }
        matched.forEach { rule ->
            cancellationCheck()
            rule.declarations.forEach { (key, value) ->
                if (key.startsWith("--")) {
                    variables[key] = resolveVars(value, variables)
                } else {
                    declarations.putCssDeclaration(key, resolveVars(value, variables))
                }
            }
        }
        parseCssDeclarations(element.attr("style")).forEach { (key, value) ->
            if (key.startsWith("--")) {
                variables[key] = resolveVars(value, variables)
            } else {
                declarations.putCssDeclaration(key, resolveVars(value, variables))
            }
        }

        val style = computeStyle(parentStyle, declarations, options, keyframes).copy(
            beforeContent = declarations["__before-content"]?.let { parsePseudoContent(it, element) },
            afterContent = declarations["__after-content"]?.let { parsePseudoContent(it, element) },
        )
        return ResolvedStyle(style = style, variables = variables, visualHints = visualHintsForDeclarations(declarations).toList())
    }

    fun telemetryStats(): CssSelectorTelemetryStats = selectorStats.snapshot()

    companion object {
        fun from(
            document: Document,
            options: RichHtmlCompileOptions,
            cancellationCheck: () -> Unit = {},
        ): StyleResolver {
            val parsed = CssParser.parse(
                css = document.select("style").joinToString("\n") { styleElement ->
                    styleElement.data().ifBlank { styleElement.html() }
                },
                options = options,
                cancellationCheck = cancellationCheck,
            )
            val rootVars = linkedMapOf<String, String>()
            parsed.rules.filter { it.selector.raw == ":root" }.forEach { rule ->
                cancellationCheck()
                rule.declarations.forEach { (key, value) ->
                    if (key.startsWith("--")) rootVars[key] = value
                }
            }
            return StyleResolver(
                rules = parsed.rules,
                keyframes = parsed.keyframes,
                rootVariables = rootVars,
                options = options,
                cancellationCheck = cancellationCheck,
                visualHints = parsed.visualHints,
                parserFallbackCount = parsed.parserFallbackCount,
            )
        }
    }
}

private fun MutableMap<String, String>.putCssDeclaration(key: String, value: String) {
    when (key) {
        "font" -> removeAll(
            "font-size",
            "font-weight",
            "font-style",
            "font-family",
            "line-height",
        )
        "margin" -> removeAll("margin-top", "margin-right", "margin-bottom", "margin-left")
        "padding" -> removeAll("padding-top", "padding-right", "padding-bottom", "padding-left")
        "border" -> removeAll(
            "border-top",
            "border-right",
            "border-bottom",
            "border-left",
            "border-width",
            "border-style",
            "border-color",
        )
        "background" -> removeAll(
            "background-color",
            "background-image",
            "background-size",
            "background-position",
            "background-repeat",
            "background-origin",
            "background-clip",
        )
    }
    this[key] = value
}

private fun MutableMap<String, String>.removeAll(vararg keys: String) {
    keys.forEach(::remove)
}

private fun visualHintsForDeclarations(declarations: Map<String, String>): Set<RichVisualHint> {
    val hints = RichVisualHintAnalyzer.analyzeDeclarations(declarations).toMutableSet()
    val background = declarations["background-image"] ?: declarations["background"]
    if (background != null && countExtraBackgroundLayers(background) > 0) hints += RichVisualHint.BackgroundExtraLayer
    val colorDeclarations = listOf(
        "color",
        "background",
        "background-color",
        "border",
        "border-color",
        "border-top-color",
        "border-right-color",
        "border-bottom-color",
        "border-left-color",
        "box-shadow",
        "text-shadow",
    )
    if (colorDeclarations.any { key -> declarations[key]?.let(::containsUnresolvedCssColor) == true }) {
        hints += RichVisualHint.CssUnsupportedColor
    }
    declarations["filter"]?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        ?.let { hints += RichVisualHint.CssFilter }
    declarations["backdrop-filter"]?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        ?.let { hints += RichVisualHint.CssBackdropFilter }
    declarations["background-clip"]?.takeIf { it.equals("text", ignoreCase = true) }
        ?.let { hints += RichVisualHint.CssBackgroundClipText }
    declarations["-webkit-background-clip"]?.takeIf { it.equals("text", ignoreCase = true) }
        ?.let { hints += RichVisualHint.CssBackgroundClipText }
    declarations["mix-blend-mode"]?.takeIf { it.isNotBlank() && !it.equals("normal", ignoreCase = true) }
        ?.let { hints += RichVisualHint.CssMixBlendMode }
    listOf("mask", "mask-image", "-webkit-mask", "-webkit-mask-image").forEach { key ->
        declarations[key]?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
            ?.let { hints += RichVisualHint.CssMask }
    }
    declarations["clip-path"]?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        ?.let { hints += RichVisualHint.CssClipPath }
    val animationValues = declarations.filterKeys { it == "animation" || it.startsWith("animation-") }.values
    if (animationValues.any { it.isNotBlank() && !it.equals("none", ignoreCase = true) }) {
        hints += RichVisualHint.CssAnimation
    }
    if (animationValues.any { it.contains("infinite", ignoreCase = true) } ||
        declarations["animation-iteration-count"]?.contains("infinite", ignoreCase = true) == true
    ) {
        hints += RichVisualHint.CssInfiniteAnimation
    }
    val transitionValues = declarations.filterKeys { it == "transition" || it.startsWith("transition-") }.values
    if (transitionValues.any { it.isNotBlank() && !it.equals("none", ignoreCase = true) }) {
        hints += RichVisualHint.CssTransition
    }
    val animatedPropertyText = (animationValues + transitionValues).joinToString(" ").lowercase()
    if (ANIMATED_LAYOUT_PROPERTIES.any { Regex("""(^|[\s,])${Regex.escape(it)}($|[\s,])""").containsMatchIn(animatedPropertyText) }) {
        hints += RichVisualHint.CssLayoutAnimation
    }
    if (declarations["opacity"]?.trim()?.toFloatOrNull() == 0f &&
        animationValues.any { it.contains("forwards", ignoreCase = true) || it.contains("both", ignoreCase = true) }
    ) {
        hints += RichVisualHint.AnimationDependentVisibility
    }
    return hints
}

private data class CssRule(
    val selector: CssSelector,
    val declarations: Map<String, String>,
    val specificity: Int,
    val order: Int,
) {
    val highCostCategories: Set<String> =
        RichCssSelectorMatcher.highCostCategories(selector.raw).mapTo(linkedSetOf()) { it.name }
}

private data class CssRuleIndex(
    private val sharedIndex: RichCssRuleIndex,
    private val rulesByOrder: Map<Int, CssRule>,
    val idRules: Map<String, List<CssRule>>,
    val classRules: Map<String, List<CssRule>>,
    val tagRules: Map<String, List<CssRule>>,
    val attrRules: Map<String, List<CssRule>>,
    val pseudoRules: Map<String, List<CssRule>>,
    val universalRules: List<CssRule>,
    val complexRules: List<CssRule>,
    val unsupportedRules: List<CssRule>,
) {
    fun candidateRules(element: Element): List<CssRule> {
        val result = linkedSetOf<CssRule>()
        sharedIndex.candidateRules(element)
            .mapNotNull { rule -> rulesByOrder[rule.order] }
            .forEach(result::add)
        // Keep the pre-V5 conservative semantics for complex selectors: the shared cascade module may
        // prefilter them with Jsoup, while the compiler's custom matcher is still the render authority.
        result += complexRules
        return result.sortedBy { it.order }
    }

    companion object {
        fun from(rules: List<CssRule>): CssRuleIndex {
            val sharedIndex = RichCssCascade.index(
                rules.map { rule ->
                    RichCssRule(
                        selector = rule.selector.raw,
                        declarations = emptyMap(),
                        order = rule.order,
                    )
                }
            )
            val idRules = linkedMapOf<String, MutableList<CssRule>>()
            val classRules = linkedMapOf<String, MutableList<CssRule>>()
            val tagRules = linkedMapOf<String, MutableList<CssRule>>()
            val attrRules = linkedMapOf<String, MutableList<CssRule>>()
            val pseudoRules = linkedMapOf<String, MutableList<CssRule>>()
            val universalRules = mutableListOf<CssRule>()
            val complexRules = mutableListOf<CssRule>()
            val unsupportedRules = mutableListOf<CssRule>()
            rules.forEach { rule ->
                when (val key = RichCssSelectorMatcher.indexKey(rule.selector.raw)) {
                    is RichCssSelectorIndexKey.Id -> idRules.getOrPut(key.value) { mutableListOf() } += rule
                    is RichCssSelectorIndexKey.ClassName -> classRules.getOrPut(key.value) { mutableListOf() } += rule
                    is RichCssSelectorIndexKey.TagName -> tagRules.getOrPut(key.value) { mutableListOf() } += rule
                    is RichCssSelectorIndexKey.AttributeName -> attrRules.getOrPut(key.value) { mutableListOf() } += rule
                    is RichCssSelectorIndexKey.Pseudo -> pseudoRules.getOrPut(key.value) { mutableListOf() } += rule
                    RichCssSelectorIndexKey.Universal -> universalRules += rule
                    RichCssSelectorIndexKey.Complex -> complexRules += rule
                    RichCssSelectorIndexKey.Unsupported -> unsupportedRules += rule
                }
            }
            return CssRuleIndex(
                sharedIndex = sharedIndex,
                rulesByOrder = rules.associateBy { it.order },
                idRules = idRules,
                classRules = classRules,
                tagRules = tagRules,
                attrRules = attrRules,
                pseudoRules = pseudoRules,
                universalRules = universalRules,
                complexRules = complexRules,
                unsupportedRules = unsupportedRules,
            )
        }
    }
}

internal data class CssSelectorTelemetryStats(
    val ruleCount: Int,
    val idRuleCount: Int,
    val classRuleCount: Int,
    val tagRuleCount: Int,
    val attrRuleCount: Int,
    val pseudoRuleCount: Int,
    val universalRuleCount: Int,
    val complexRuleCount: Int,
    val unsupportedSelectorCount: Int,
    val elementCount: Int,
    val averageCandidateRules: Float,
    val p95CandidateRules: Int,
    val legacyScanCount: Int,
    val legacyVerificationSkippedCount: Int,
    val indexMismatchCount: Int,
    val parserFallbackCount: Int,
    val selectorMatchMs: Long,
    val highCostSelectorCategories: Map<String, Int>,
)

private class CssSelectorTelemetryAccumulator(
    private val ruleCount: Int,
    private val idRuleCount: Int,
    private val classRuleCount: Int,
    private val tagRuleCount: Int,
    private val attrRuleCount: Int,
    private val pseudoRuleCount: Int,
    private val universalRuleCount: Int,
    private val complexRuleCount: Int,
    private val unsupportedSelectorCount: Int,
    private val highCostSelectorCategories: Map<String, Int>,
    private val parserFallbackCount: Int,
) {
    private val candidateCounts = mutableListOf<Int>()
    private var legacyScanCount = 0
    private var legacyVerificationSkippedCount = 0
    private var indexMismatchCount = 0
    private var selectorMatchNanos = 0L

    fun record(
        candidateCount: Int,
        legacyScanCount: Int,
        legacyVerificationSkipped: Boolean,
        indexMismatch: Boolean,
        selectorMatchNanos: Long,
    ) {
        candidateCounts += candidateCount
        this.legacyScanCount += legacyScanCount
        if (legacyVerificationSkipped) legacyVerificationSkippedCount += 1
        if (indexMismatch) indexMismatchCount += 1
        this.selectorMatchNanos += selectorMatchNanos
    }

    fun snapshot(): CssSelectorTelemetryStats {
        val sorted = candidateCounts.sorted()
        return CssSelectorTelemetryStats(
            ruleCount = ruleCount,
            idRuleCount = idRuleCount,
            classRuleCount = classRuleCount,
            tagRuleCount = tagRuleCount,
            attrRuleCount = attrRuleCount,
            pseudoRuleCount = pseudoRuleCount,
            universalRuleCount = universalRuleCount,
            complexRuleCount = complexRuleCount,
            unsupportedSelectorCount = unsupportedSelectorCount,
            elementCount = candidateCounts.size,
            averageCandidateRules = candidateCounts.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f,
            p95CandidateRules = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * 0.95f).toInt()],
            legacyScanCount = legacyScanCount,
            legacyVerificationSkippedCount = legacyVerificationSkippedCount,
            indexMismatchCount = indexMismatchCount,
            parserFallbackCount = parserFallbackCount,
            selectorMatchMs = selectorMatchNanos / 1_000_000,
            highCostSelectorCategories = highCostSelectorCategories,
        )
    }
}

private data class CssParseResult(
    val rules: List<CssRule>,
    val keyframes: Map<String, RichKeyframesSummary>,
    val visualHints: List<RichVisualHint>,
    val parserFallbackCount: Int,
)

private data class RichKeyframesSummary(
    val name: String,
    val properties: Set<String>,
    val fromDeclarations: Map<String, String> = emptyMap(),
    val toDeclarations: Map<String, String> = emptyMap(),
    val hasIntermediateFrame: Boolean = false,
    val frames: List<ParsedKeyframeDeclarations> = emptyList(),
) {
    val hasOpacityOrTransform: Boolean
        get() = properties.any { it == "opacity" || it == "transform" }

    val hasLayoutProperty: Boolean
        get() = properties.any { it in ANIMATED_LAYOUT_PROPERTIES }
}

private data class CssSelector(
    val raw: String,
) {
    fun matches(element: Element): Boolean {
        val selector = raw.trim()
        if (selector == ":root") return element.parent()?.tagName()?.equals("body", ignoreCase = true) == true
        return matchCompoundSelector(element, selector)
    }

    val specificity: Int
        get() {
            val ids = Regex("""#[A-Za-z_][\w-]*""").findAll(raw).count()
            val classes = Regex("""(\.[A-Za-z_][\w-]*|\[[^]]+]|:[\w-]+(?:\([^)]*\))?)""").findAll(raw).count()
            val tags = raw.split(Regex("""[#.\[:\s>+~]+""")).count { it.matches(Regex("""[A-Za-z][\w-]*""")) }
            return ids * 10_000 + classes * 100 + tags
        }
}

private object CssParser {
    fun parse(
        css: String,
        options: RichHtmlCompileOptions,
        cancellationCheck: () -> Unit = {},
    ): CssParseResult {
        val rules = mutableListOf<CssRule>()
        val keyframes = linkedMapOf<String, RichKeyframesSummary>()
        val hints = linkedSetOf<RichVisualHint>()
        val parserFallbackCount = intArrayOf(0)
        parseInto(css.removeCssComments(), options, rules, keyframes, hints, parserFallbackCount, cancellationCheck)
        return CssParseResult(
            rules = rules,
            keyframes = keyframes,
            visualHints = hints.toList(),
            parserFallbackCount = parserFallbackCount[0],
        )
    }

    private fun parseInto(
        css: String,
        options: RichHtmlCompileOptions,
        output: MutableList<CssRule>,
        keyframes: MutableMap<String, RichKeyframesSummary>,
        visualHints: MutableSet<RichVisualHint>,
        parserFallbackCount: IntArray,
        cancellationCheck: () -> Unit,
    ) {
        var cursor = 0
        while (cursor < css.length) {
            cancellationCheck()
            val start = css.indexOf('{', cursor)
            if (start < 0) break
            val selector = css.substring(cursor, start).trim()
            val end = findMatchingBrace(css, start)
            if (end < 0) break
            val body = css.substring(start + 1, end)
            when {
                selector.startsWith("@media", ignoreCase = true) -> {
                    if (mediaMatches(selector, options)) {
                        parseInto(body, options, output, keyframes, visualHints, parserFallbackCount, cancellationCheck)
                    }
                }
                selector.startsWith("@font-face", ignoreCase = true) -> Unit
                selector.startsWith("@keyframes", ignoreCase = true) -> {
                    hintsForKeyframes(selector, body)?.let { summary ->
                        visualHints += RichVisualHint.CssKeyframes
                        if (summary.hasOpacityOrTransform) visualHints += RichVisualHint.CssAnimation
                        if (summary.hasLayoutProperty) visualHints += RichVisualHint.CssLayoutAnimation
                        keyframes[summary.name] = summary
                    }
                }
                selector.startsWith("@") -> Unit
                else -> {
                    val declarationResult = RichCssParser.parseDeclarationMapWithReport(body)
                    if (declarationResult.report.fallbackUsed) parserFallbackCount[0] += 1
                    val declarations = declarationResult.declarations
                    visualHints += visualHintsForDeclarations(declarations)
                    selector.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { raw ->
                        cancellationCheck()
                        val pseudoTarget = when {
                            raw.endsWith("::before", ignoreCase = true) || raw.endsWith(":before", ignoreCase = true) -> "__before-content"
                            raw.endsWith("::after", ignoreCase = true) || raw.endsWith(":after", ignoreCase = true) -> "__after-content"
                            else -> null
                        }
                        if (pseudoTarget == null && raw.contains(INTERACTIVE_PSEUDO_SELECTOR)) {
                            visualHints += RichVisualHint.CssInteractivePseudoClass
                            return@forEach
                        }
                        val selectorRaw = raw
                            .removeSuffix("::before")
                            .removeSuffix(":before")
                            .removeSuffix("::after")
                            .removeSuffix(":after")
                            .trim()
                            .ifBlank { "*" }
                        val effectiveDeclarations = if (pseudoTarget != null && declarations["content"] != null) {
                            mapOf(pseudoTarget to declarations.getValue("content"))
                        } else {
                            declarations
                        }
                        if (pseudoTarget != null && effectiveDeclarations.isEmpty()) return@forEach
                        val cssSelector = CssSelector(selectorRaw)
                        output += CssRule(
                            selector = cssSelector,
                            declarations = effectiveDeclarations,
                            specificity = cssSelector.specificity,
                            order = output.size,
                        )
                    }
                }
            }
            cursor = end + 1
        }
    }
}

private fun computeStyle(
    parent: ComputedStyle,
    declarations: Map<String, String>,
    options: RichHtmlCompileOptions,
    keyframes: Map<String, RichKeyframesSummary> = emptyMap(),
): ComputedStyle {
    val inherited = parent.inheritedCssStyle()
    val fontShorthand = declarations["font"]?.let { parseCssFontShorthand(it, inherited.fontSize) }
    val inheritedFontContext = CssLengthContext(
        viewportWidth = options.viewportWidthDp.dp,
        fontSize = inherited.fontSize,
    )
    val fontSize = declarations["font-size"]?.let { parseCssFontSize(it, inherited.fontSize, inheritedFontContext) }
        ?: fontShorthand?.fontSize
        ?: inherited.fontSize
    val declaredLineHeight = declarations["line-height"]?.let { parseCssLineHeightValue(it, fontSize) }
    val shorthandNormalLineHeight = if (fontShorthand != null && fontShorthand.lineHeight == null) {
        parseCssLineHeightValue("normal", fontSize)
    } else {
        null
    }
    val inheritedLineHeight = inherited.lineHeightMultiplier
        ?.let { multiplier -> (fontSize.cssReferenceFontSize().value * multiplier).sp.takeIfFiniteCssTextUnit() }
        ?: inherited.lineHeight
    val lineHeight = declaredLineHeight?.value
        ?: fontShorthand?.lineHeight?.value
        ?: shorthandNormalLineHeight?.value
        ?: inheritedLineHeight
    val lineHeightMultiplier = declaredLineHeight?.multiplier
        ?: fontShorthand?.lineHeight?.multiplier
        ?: shorthandNormalLineHeight?.multiplier
        ?: inherited.lineHeightMultiplier
    val lengthContext = CssLengthContext(
        viewportWidth = options.viewportWidthDp.dp,
        fontSize = fontSize,
    )
    val gapShorthand = (declarations["gap"] ?: declarations["grid-gap"])
        ?.let { parseCssGap(it, lengthContext) }
    val rowGap = (declarations["row-gap"] ?: declarations["grid-row-gap"])
        ?.let { parseCssDp(it, lengthContext) }
        ?: gapShorthand?.rowGap
        ?: inherited.rowGap
    val columnGap = (declarations["column-gap"] ?: declarations["grid-column-gap"])
        ?.let { parseCssDp(it, lengthContext) }
        ?: gapShorthand?.columnGap
        ?: inherited.columnGap
    val gap = rowGap
    val backgroundShorthand = declarations["background"]?.let { parseBackgroundShorthand(it, lengthContext) }
    val flexShorthand = declarations["flex"]?.let { parseFlexShorthand(it, lengthContext) }
    val flexFlow = declarations["flex-flow"]?.let(::parseFlexFlow)
    val animation = parseAnimationStyle(declarations, keyframes, lengthContext)
        ?: inherited.animation
    val transition = parseTransitionStyle(declarations)
        ?: inherited.transition
    val declaredOpacity = declarations["opacity"]?.let(::parseCssFloat)?.coerceIn(0f, 1f)
    val opacity = if (declaredOpacity != null && declaredOpacity <= 0.001f && animation.shouldStaticizeVisible()) {
        1f
    } else if (animation.isInfinite && animation.staticOpacity != null) {
        maxOf(declaredOpacity ?: inherited.opacity, animation.staticOpacity)
    } else {
        declaredOpacity ?: inherited.opacity
    }
    val declaredColor = declarations["color"]?.let(::parseRichCssColor)
    val resolvedColor = declarations["color"]?.let { resolveRichCssColor(declaredColor, inherited.color) } ?: inherited.color
    val declaredBackgroundColor = declarations["background-color"]?.let(::parseRichCssColor)
        ?: backgroundShorthand?.declaredColor
    val resolvedBackgroundColor = when {
        declarations.containsKey("background-color") -> resolveRichCssColor(declaredBackgroundColor, resolvedColor)
        backgroundShorthand?.declaredColor != null -> resolveRichCssColor(backgroundShorthand.declaredColor, resolvedColor)
        declarations.containsKey("background") -> resolveRichCssColor(declaredBackgroundColor, resolvedColor)
        else -> inherited.backgroundColor
    }
    val gridColumnPlacement = parseGridPlacement(
        shorthand = declarations["grid-column"],
        start = declarations["grid-column-start"],
        end = declarations["grid-column-end"],
    )
    val gridRowPlacement = parseGridPlacement(
        shorthand = declarations["grid-row"],
        start = declarations["grid-row-start"],
        end = declarations["grid-row-end"],
    )
    val border = parseBorder(declarations, inherited.border, lengthContext, resolvedColor)
    return inherited.copy(
        display = declarations["display"]?.let(::parseDisplay) ?: inherited.display,
        position = declarations["position"]?.let(::parsePosition) ?: inherited.position,
        visibility = declarations["visibility"]?.let(::parseVisibility) ?: inherited.visibility,
        color = resolvedColor,
        declaredColor = declaredColor ?: inherited.declaredColor,
        backgroundColor = resolvedBackgroundColor,
        declaredBackgroundColor = declaredBackgroundColor ?: inherited.declaredBackgroundColor,
        backgroundImage = declarations["background-image"]?.let(::parseBackgroundImage)
            ?: backgroundShorthand?.image
            ?: declarations["background"]?.let(::parseBackgroundImage)
            ?: inherited.backgroundImage,
        backgroundUrl = declarations["background-image"]?.let {
            parseSafeBackgroundUrl(it, RichMediaKind.BackgroundImage)
        }
            ?: backgroundShorthand?.url
            ?: declarations["background"]?.let { parseSafeBackgroundUrl(it, RichMediaKind.BackgroundImage) }
            ?: inherited.backgroundUrl,
        backgroundLayers = parseSafeBackgroundLayers(
            imageValue = declarations["background-image"] ?: declarations["background"],
            sizeValue = declarations["background-size"],
            positionValue = declarations["background-position"],
            repeatValue = declarations["background-repeat"],
            originValue = declarations["background-origin"],
            clipValue = declarations["background-clip"] ?: declarations["-webkit-background-clip"],
            context = lengthContext,
            fallbackSize = declarations["background-size"]?.let { parseBackgroundSize(it, lengthContext) }
                ?: backgroundShorthand?.size
                ?: inherited.backgroundSize,
            fallbackPosition = declarations["background-position"]?.let { parseBackgroundPosition(it, lengthContext) }
                ?: backgroundShorthand?.position
                ?: inherited.backgroundPosition,
            fallbackRepeat = declarations["background-repeat"]?.let(::parseBackgroundRepeat)
                ?: backgroundShorthand?.repeat
                ?: inherited.backgroundRepeat,
            fallbackOrigin = declarations["background-origin"]?.let(::parseBackgroundBox)
                ?: backgroundShorthand?.origin
                ?: inherited.backgroundOrigin,
            fallbackClip = declarations["background-clip"]?.let(::parseBackgroundBox)
                ?: declarations["-webkit-background-clip"]?.let(::parseBackgroundBox)
                ?: backgroundShorthand?.clip
                ?: inherited.backgroundClip,
        ) ?: inherited.backgroundLayers,
        backgroundSize = declarations["background-size"]?.let { parseBackgroundSize(it, lengthContext) }
            ?: backgroundShorthand?.size
            ?: inherited.backgroundSize,
        backgroundPosition = declarations["background-position"]?.let { parseBackgroundPosition(it, lengthContext) }
            ?: backgroundShorthand?.position
            ?: inherited.backgroundPosition,
        backgroundRepeat = declarations["background-repeat"]?.let(::parseBackgroundRepeat)
            ?: backgroundShorthand?.repeat
            ?: inherited.backgroundRepeat,
        backgroundOrigin = declarations["background-origin"]?.let(::parseBackgroundBox)
            ?: backgroundShorthand?.origin
            ?: inherited.backgroundOrigin,
        backgroundClip = declarations["background-clip"]?.let(::parseBackgroundBox)
            ?: declarations["-webkit-background-clip"]?.let(::parseBackgroundBox)
            ?: backgroundShorthand?.clip
            ?: inherited.backgroundClip,
        extraBackgroundLayers = (declarations["background-image"] ?: declarations["background"])
            ?.let(::countExtraBackgroundLayers)
            ?: inherited.extraBackgroundLayers,
        objectFit = declarations["object-fit"]?.let(::parseObjectFit) ?: inherited.objectFit,
        opacity = opacity,
        animation = animation,
        transition = transition,
        cssFilter = declarations["filter"]?.let { parseCssFilter(it, lengthContext) } ?: inherited.cssFilter,
        backdropFilter = declarations["backdrop-filter"]?.let { parseCssFilter(it, lengthContext) } ?: inherited.backdropFilter,
        mixBlendMode = declarations["mix-blend-mode"]
            ?.let { it.isNotBlank() && !it.equals("normal", ignoreCase = true) }
            ?: inherited.mixBlendMode,
        clipPath = declarations["clip-path"]?.let { parseClipPath(it, lengthContext) } ?: inherited.clipPath,
        maskImage = (declarations["mask-image"]
            ?: declarations["-webkit-mask-image"]
            ?: declarations["mask"]
            ?: declarations["-webkit-mask"])?.let(::parseMaskImage) ?: inherited.maskImage,
        padding = parseCssSpacing(declarations, "padding", lengthContext) ?: inherited.padding,
        margin = parseCssSpacing(declarations, "margin", lengthContext) ?: inherited.margin,
        border = border,
        borderRadius = parseCssCornerRadius(declarations, lengthContext) ?: inherited.borderRadius,
        shadows = declarations["box-shadow"]?.let { parseShadows(it, lengthContext, resolvedColor) } ?: inherited.shadows,
        width = declarations["width"]?.let { parseRichSize(it, lengthContext) } ?: inherited.width,
        height = declarations["height"]?.let { parseRichSize(it, lengthContext) } ?: inherited.height,
        minWidth = declarations["min-width"]?.let { parseCssDp(it, lengthContext) } ?: inherited.minWidth,
        maxWidth = declarations["max-width"]?.let { parseCssDp(it, lengthContext) } ?: inherited.maxWidth,
        minHeight = declarations["min-height"]?.let { parseCssDp(it, lengthContext) } ?: inherited.minHeight,
        maxHeight = declarations["max-height"]?.let { parseCssDp(it, lengthContext) } ?: inherited.maxHeight,
        gap = gap,
        rowGap = rowGap,
        columnGap = columnGap,
        flexDirection = declarations["flex-direction"]?.let(::parseFlexDirection)
            ?: flexFlow?.direction
            ?: inherited.flexDirection,
        flexWrap = declarations["flex-wrap"]?.let(::parseFlexWrap)
            ?: flexFlow?.wrap
            ?: inherited.flexWrap,
        justifyContent = declarations["justify-content"]?.let(::parseJustify) ?: inherited.justifyContent,
        alignItems = declarations["align-items"]?.let(::parseAlign) ?: inherited.alignItems,
        alignSelf = declarations["align-self"]?.let(::parseAlign) ?: inherited.alignSelf,
        flexGrow = declarations["flex-grow"]?.let(::parseCssFloat) ?: flexShorthand?.grow ?: inherited.flexGrow,
        flexShrink = declarations["flex-shrink"]?.let(::parseCssFloat) ?: flexShorthand?.shrink ?: inherited.flexShrink,
        flexBasis = declarations["flex-basis"]?.let { parseRichSize(it, lengthContext) }
            ?: flexShorthand?.basis
            ?: inherited.flexBasis,
        gridColumns = declarations["grid-template-columns"]?.let { parseGridColumns(it, lengthContext, columnGap) } ?: inherited.gridColumns,
        order = declarations["order"]?.toIntOrNull() ?: inherited.order,
        alignContent = declarations["align-content"]?.let(::parseAlignContent) ?: inherited.alignContent,
        gridColumnStart = gridColumnPlacement.start,
        gridRowStart = gridRowPlacement.start,
        gridColumnSpan = gridColumnPlacement.span,
        gridRowSpan = gridRowPlacement.span,
        zIndex = declarations["z-index"]?.toFloatOrNull() ?: inherited.zIndex,
        offset = parseOffset(declarations, inherited.offset, lengthContext),
        transform = declarations["transform"]?.let { parseTransform(it, lengthContext) } ?: inherited.transform,
        overflow = (declarations["overflow"] ?: declarations["overflow-x"] ?: declarations["overflow-y"])?.let(::parseOverflow)
            ?: inherited.overflow,
        fontSize = fontSize,
        fontWeight = declarations["font-weight"]?.let(::parseCssFontWeight) ?: fontShorthand?.fontWeight ?: inherited.fontWeight,
        fontStyle = declarations["font-style"]?.let(::parseCssFontStyle) ?: fontShorthand?.fontStyle ?: inherited.fontStyle,
        fontFamily = declarations["font-family"]?.let(::parseCssFontFamily) ?: fontShorthand?.fontFamily ?: inherited.fontFamily,
        lineHeight = lineHeight,
        lineHeightMultiplier = lineHeightMultiplier,
        textAlign = declarations["text-align"]?.let(::parseCssTextAlign) ?: inherited.textAlign,
        letterSpacing = declarations["letter-spacing"]?.let { parseCssFontSize(it, fontSize) } ?: inherited.letterSpacing,
        textDecoration = declarations["text-decoration"]?.let(::parseTextDecoration) ?: inherited.textDecoration,
        textShadow = declarations["text-shadow"]?.let { parseTextShadow(it, resolvedColor) } ?: inherited.textShadow,
        textTransform = declarations["text-transform"]?.let(::parseTextTransform) ?: inherited.textTransform,
        verticalAlign = declarations["vertical-align"]?.let(::parseVerticalAlign) ?: inherited.verticalAlign,
        fontVariantNumeric = declarations["font-variant-numeric"]?.let(::parseFontVariantNumeric)
            ?: inherited.fontVariantNumeric,
        whiteSpace = declarations["white-space"]?.let(::parseWhiteSpace) ?: inherited.whiteSpace,
        wordBreak = (declarations["word-break"] ?: declarations["overflow-wrap"])?.let(::parseWordBreak) ?: inherited.wordBreak,
        textOverflow = declarations["text-overflow"]?.let(::parseTextOverflow) ?: inherited.textOverflow,
        listStyleType = (declarations["list-style-type"] ?: declarations["list-style"])?.let(::parseListStyleType)
            ?: inherited.listStyleType,
        listStylePosition = (declarations["list-style-position"] ?: declarations["list-style"])?.let(::parseListStylePosition)
            ?: inherited.listStylePosition,
        listStyleImage = (declarations["list-style-image"] ?: declarations["list-style"])?.let(::parseListStyleImage)
            ?: inherited.listStyleImage,
        borderCollapse = declarations["border-collapse"]?.let(::parseBorderCollapse) ?: inherited.borderCollapse,
        captionSide = declarations["caption-side"]?.let(::parseCaptionSide) ?: inherited.captionSide,
        beforeContent = null,
        afterContent = null,
        cursorPointer = declarations["cursor"]?.equals("pointer", ignoreCase = true) ?: inherited.cursorPointer,
    )
}

private fun ComputedStyle.inheritedCssStyle(): ComputedStyle {
    return ComputedStyle.Initial.copy(
        visibility = visibility,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        lineHeight = lineHeight,
        lineHeightMultiplier = lineHeightMultiplier,
        textAlign = textAlign,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textShadow = textShadow,
        textTransform = textTransform,
        verticalAlign = verticalAlign,
        fontVariantNumeric = fontVariantNumeric,
        whiteSpace = whiteSpace,
        wordBreak = wordBreak,
        listStyleType = listStyleType,
        listStylePosition = listStylePosition,
        listStyleImage = listStyleImage,
        borderCollapse = borderCollapse,
        captionSide = captionSide,
        cursorPointer = cursorPointer,
    )
}

private fun uaDeclarationsFor(tag: String): Map<String, String> = when (tag) {
    "p" -> mapOf("display" to "block", "margin-top" to "1em", "margin-bottom" to "1em")
    "h1" -> headingUa("2em", ".67em")
    "h2" -> headingUa("1.5em", ".83em")
    "h3" -> headingUa("1.17em", "1em")
    "h4" -> headingUa("1em", "1.33em")
    "h5" -> headingUa(".83em", "1.67em")
    "h6" -> headingUa(".67em", "2.33em")
    "ul", "ol" -> mapOf(
        "display" to "block",
        "margin-top" to "1em",
        "margin-bottom" to "1em",
        "padding-left" to "40px",
    )
    "pre" -> mapOf(
        "display" to "block",
        "font-family" to "monospace",
        "white-space" to "pre",
        "margin-top" to "1em",
        "margin-bottom" to "1em",
    )
    "code", "kbd", "samp" -> mapOf("font-family" to "monospace", "font-size" to ".92em")
    "button" -> mapOf(
        "display" to "inline-block",
        "font-size" to "1em",
        "line-height" to "normal",
        "text-align" to "center",
        "padding" to "2px 6px",
        "border" to "1px solid #767676",
        "border-radius" to "2px",
        "background-color" to "#f0f0f0",
        "color" to "#111111",
    )
    "details" -> mapOf("display" to "block")
    "summary" -> mapOf("display" to "block", "cursor" to "pointer")
    "table" -> mapOf("display" to "block", "margin-top" to "1em", "margin-bottom" to "1em")
    else -> emptyMap()
}

private fun headingUa(fontSize: String, marginBlock: String): Map<String, String> = mapOf(
    "display" to "block",
    "font-size" to fontSize,
    "font-weight" to "bold",
    "margin-top" to marginBlock,
    "margin-bottom" to marginBlock,
)

private fun ComputedStyle.withTagInlineDefaults(tag: String): ComputedStyle {
    return when (tag) {
        "strong", "b" -> copy(fontWeight = FontWeight.SemiBold)
        "em", "i" -> copy(fontStyle = FontStyle.Italic)
        "u", "ins" -> copy(textDecoration = TextDecoration.Underline)
        "s", "del", "strike" -> copy(textDecoration = TextDecoration.LineThrough)
        "code", "kbd" -> copy(fontFamily = FontFamily.Monospace, fontSize = if (fontSize == TextUnit.Unspecified) 0.92.em else fontSize)
        "mark" -> copy(backgroundColor = backgroundColor ?: Color(0xFFFFF3A3), color = color ?: Color(0xFF1F2937))
        "a" -> copy(textDecoration = TextDecoration.Underline)
        else -> this
    }
}

private fun ComputedStyle.isPositionedOverlay(): Boolean {
    return position == RichPosition.Absolute || position == RichPosition.Fixed || position == RichPosition.Sticky
}

private fun String.inlineSpanDefaults(): SpanStyle {
    return when (this) {
        "sub" -> SpanStyle(fontSize = 0.72.em, baselineShift = BaselineShift.Subscript)
        "sup" -> SpanStyle(fontSize = 0.72.em, baselineShift = BaselineShift.Superscript)
        else -> SpanStyle()
    }
}

private fun isInlineOnly(element: Element): Boolean {
    return element.children().all { child -> isInlineTag(child.tagName().lowercase()) || isMathFormulaContainer(child) }
}

private fun Element.hasInlineRenderableContent(style: ComputedStyle): Boolean {
    return normalizeText(wholeText(), style.whiteSpace).isNotBlank() ||
        children().any { child -> isInlineTag(child.tagName().lowercase()) || isMathFormulaContainer(child) }
}

private fun ComputedStyle.hasVisualBox(): Boolean {
    return position != RichPosition.Static ||
        backgroundColor != null ||
        backgroundImage != null ||
        backgroundUrl != null ||
        border != RichBorder.None ||
        borderRadius != RichCornerRadius.Zero ||
        shadows.isNotEmpty() ||
        width != RichSize.Auto ||
        height != RichSize.Auto ||
        minWidth != null ||
        maxWidth != null ||
        minHeight != null ||
        maxHeight != null ||
        padding != RichSpacing.Zero ||
        opacity != 1f ||
        cssFilter != RichCssFilter.None ||
        backdropFilter != RichCssFilter.None ||
        transform != RichTransform.None ||
        animation.isDeclared ||
        transition.isDeclared
}

private fun ComputedStyle.needsOwnInlineBlock(): Boolean {
    return display == RichDisplay.InlineBlock ||
        display == RichDisplay.InlineFlex ||
        display == RichDisplay.InlineGrid ||
        position != RichPosition.Static ||
        opacity != 1f ||
        cssFilter != RichCssFilter.None ||
        backdropFilter != RichCssFilter.None ||
        transform != RichTransform.None ||
        animation.isDeclared ||
        transition.isDeclared
}

private fun RichDisplay.needsInlineContainerDisplay(): Boolean {
    return this == RichDisplay.InlineBlock || this == RichDisplay.InlineFlex || this == RichDisplay.InlineGrid
}

private fun ComputedStyle.canRenderAsInlineRichBox(): Boolean {
    if (isPositionedOverlay()) return false
    val hasFullBoxBorder = border.hasNonDecorativeInlineBoxBorder()
    return display == RichDisplay.InlineBlock ||
        display == RichDisplay.InlineFlex ||
        display == RichDisplay.InlineGrid ||
        width != RichSize.Auto ||
        height != RichSize.Auto ||
        minWidth != null ||
        minHeight != null ||
        maxWidth != null ||
        maxHeight != null ||
        padding != RichSpacing.Zero ||
        margin != RichSpacing.Zero ||
        hasFullBoxBorder ||
        borderRadius != RichCornerRadius.Zero ||
        shadows.isNotEmpty()
}

private fun ComputedStyle.shouldRenderAsInlineRichBox(tag: String): Boolean {
    return canRenderAsInlineRichBox() && (isInlineTag(tag) || display.isInlineBox())
}

private fun ComputedStyle.participatesInParentInlineFlow(
    tag: String,
    parentStyle: ComputedStyle,
): Boolean {
    if (parentStyle.display.isFlexContainer() || parentStyle.display.isGridContainer()) return false
    if (isPositionedOverlay()) return false
    return when {
        shouldRenderAsInlineRichBox(tag) -> true
        isInlineTag(tag) && !needsOwnInlineBlock() -> true
        else -> false
    }
}

private fun RichBlock.withInlineFallbackDisplay(): RichBlock {
    if (style.display != RichDisplay.Block) return this
    val inlineStyle = style.copy(display = RichDisplay.Inline)
    return when (this) {
        is RichTextBlock -> copy(style = inlineStyle)
        is RichTextFlowBlock -> copy(style = inlineStyle)
        is RichContainerBlock -> copy(style = inlineStyle)
        is RichImageBlock -> copy(style = inlineStyle)
        is RichTableBlock -> copy(style = inlineStyle)
        is RichSvgBlock -> copy(style = inlineStyle)
        is RichMathBlock -> copy(style = inlineStyle)
        is RichButtonBlock -> copy(style = inlineStyle)
        is RichDetailsBlock -> copy(style = inlineStyle)
        is RichSnapshotIslandBlock -> copy(style = inlineStyle)
        is RichUnsupportedBlock -> copy(style = inlineStyle)
    }
}

private fun ComputedStyle.estimatedInlineBoxWidth(element: Element): Dp {
    width.dpOrNull()?.let { return it + padding.horizontal() + border.horizontalWidth() + margin.horizontal() }
    val rawText = normalizeText(element.wholeText(), whiteSpace)
    val textWidth = rawText.sumOf { char -> char.inlineWidthUnits().toDouble() }.toFloat() * inlineFontDp()
    val boxWidth = textWidth.dp + padding.horizontal() + border.horizontalWidth()
    return boxWidth
        .coerceAtLeast(minWidth ?: 0.dp)
        .let { width -> maxWidth?.let(width::coerceAtMost) ?: width }
        .let { it + margin.horizontal() }
}

private fun ComputedStyle.estimatedInlineBoxHeight(): Dp {
    height.dpOrNull()?.let { return it + padding.vertical() + border.verticalWidth() + margin.vertical() }
    val font = inlineFontDp()
    val line = when (lineHeight.type) {
        TextUnitType.Sp -> lineHeight.value.dp
        TextUnitType.Em -> (font * lineHeight.value).dp
        else -> (font * 1.25f).dp
    }
    val boxHeight = line + padding.vertical() + border.verticalWidth()
    return boxHeight
        .coerceAtLeast(minHeight ?: 0.dp)
        .let { height -> maxHeight?.let(height::coerceAtMost) ?: height }
        .let { it + margin.vertical() }
}

private fun ComputedStyle.inlineFontDp(): Float {
    return when (fontSize.type) {
        TextUnitType.Sp -> fontSize.value
        TextUnitType.Em -> 14f * fontSize.value
        else -> 14f
    }.coerceAtLeast(8f)
}

private fun Char.inlineWidthUnits(): Float = when {
    isWhitespace() -> 0.35f
    code in 0x2E80..0x9FFF -> 1.0f
    code >= 0x1F000 -> 1.1f
    isUpperCase() -> 0.68f
    isDigit() -> 0.58f
    else -> 0.56f
}

private fun RichSize.dpOrNull(): Dp? = (this as? RichSize.DpSize)?.value

private fun RichSpacing.horizontal(): Dp = left + right

private fun RichSpacing.vertical(): Dp = top + bottom

private fun RichBorder.horizontalWidth(): Dp = left.width + right.width

private fun RichBorder.verticalWidth(): Dp = top.width + bottom.width

private fun RichBorder.hasNonDecorativeInlineBoxBorder(): Boolean {
    return listOf(top, right, left).any { it.style != RichBorderStyle.None && it.width > 0.dp && it.color.alpha > 0f }
}

private fun ComputedStyle.anonymousInlineTextStyle(): ComputedStyle {
    val inherited = inheritedCssStyle().copy(display = RichDisplay.Block)
    return if (backgroundClip == RichBackgroundBox.Text) {
        inherited.copy(
            backgroundImage = backgroundImage,
            backgroundSize = backgroundSize,
            backgroundPosition = backgroundPosition,
            backgroundRepeat = backgroundRepeat,
            backgroundClip = backgroundClip,
        )
    } else {
        inherited
    }
}

private fun isInlineTag(tag: String): Boolean = tag in setOf(
    "span", "strong", "b", "em", "i", "u", "code", "a", "mark", "kbd", "del", "ins", "sub", "sup", "small", "label", "br"
)

private fun ComputedStyle.inlineTextPaint(start: Int, end: Int): InlineTextPaintRun? {
    if (end <= start) return null
    inlineHighlightPaint(start, end)?.let { return it }
    val bottomBorder = border.bottom
    return bottomBorder
        .takeIf { it.style != RichBorderStyle.None && it.width > 0.dp && it.color.alpha > 0f }
        ?.let {
            InlineTextPaintRun(
                start = start,
                end = end,
                color = it.color.copy(alpha = it.color.alpha * effectiveOpacity()),
                topFraction = 0.82f,
                heightFraction = 0.10f,
                minHeight = it.width,
                cornerRadius = it.width,
            )
        }
}

private fun ComputedStyle.inlineHighlightPaint(start: Int, end: Int): InlineTextPaintRun? {
    val gradient = backgroundImage as? RichBackgroundImage.LinearGradient ?: return null
    if (backgroundClip == RichBackgroundBox.Text) return null
    val coloredStops = gradient.stops.filter { it.color.alpha > 0.05f }
    val transparentStops = gradient.stops.filter { it.color.alpha <= 0.05f }
    if (coloredStops.isEmpty() || transparentStops.isEmpty()) return null
    val color = coloredStops.maxByOrNull { it.color.alpha }?.color ?: return null
    val firstColorOffset = coloredStops.mapNotNull { it.offset }.minOrNull() ?: 0.62f
    val top = firstColorOffset.coerceIn(0.45f, 0.9f)
    return InlineTextPaintRun(
        start = start,
        end = end,
        color = color.copy(alpha = color.alpha * effectiveOpacity()),
        topFraction = top,
        heightFraction = (1f - top).coerceIn(0.08f, 0.48f),
        minHeight = 2.dp,
        cornerRadius = 2.dp,
    )
}

private fun isMathFormulaContainer(element: Element): Boolean {
    val tag = element.tagName().lowercase()
    val className = element.className().lowercase()
    return tag == "math" ||
        className.contains("math") ||
        className.contains("formula-box") ||
        className.contains("katex") ||
        element.selectFirst("annotation[encoding*=application/x-tex], annotation[encoding*=latex]") != null
}

private fun extractLatex(element: Element): String {
    return element.selectFirst("annotation[encoding*=application/x-tex], annotation[encoding*=latex]")?.text()?.trim()
        ?: element.attr("data-latex").takeIf { it.isNotBlank() }?.trim()
        ?: element.text().trim()
}

private fun normalizeText(text: String, whiteSpace: RichWhiteSpace): String {
    return when (whiteSpace) {
        RichWhiteSpace.Pre, RichWhiteSpace.PreWrap -> text.replace('\r', '\n')
        RichWhiteSpace.PreLine -> text.replace('\r', '\n').replace(Regex("[ \\t]+"), " ")
        RichWhiteSpace.NoWrap, RichWhiteSpace.Normal -> text.replace(Regex("\\s+"), " ")
    }
}

private fun String.previewText(): String = replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(140)

private val LATEX_INLINE_PATTERN = Regex(
    """\$\$([\s\S]{1,800}?)\$\$|\\\[([\s\S]{1,800}?)\\\]|\\\(([\s\S]{1,400}?)\\\)|\$(?!\$)([^$\n]{1,400}?)\$"""
)

internal object RichCssDeclarationParser {
    var forceFallbackForTest: Boolean = false

    fun parse(style: String): Map<String, String> {
        return parseWithReport(style).declarations
    }

    fun parseWithReport(style: String): RichCssDeclarationParseResult {
        if (style.isBlank()) return RichCssDeclarationParseResult(emptyMap(), fallbackUsed = false)
        if (!forceFallbackForTest && !requiresManualParser(style)) {
            parseWithPhCss(style)?.let {
                return RichCssDeclarationParseResult(declarations = it, fallbackUsed = false)
            }
        }
        return RichCssDeclarationParseResult(declarations = parseManually(style), fallbackUsed = true)
    }

    private fun requiresManualParser(style: String): Boolean {
        return style.contains("var(", ignoreCase = true) ||
            style.contains("--") ||
            Regex("""(^|;)\s*font\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(style)
    }

    private fun parseWithPhCss(style: String): Map<String, String>? {
        return runCatching {
            val declarations = CSSReaderDeclarationList.readFromString(style) ?: return@runCatching null
            val result = linkedMapOf<String, String>()
            declarations.allDeclarations.forEach { declaration ->
                val property = declaration.property.trim().lowercase()
                val value = declaration.expressionAsCSSString.trim()
                if (property.isNotBlank() && value.isNotBlank()) {
                    result[property] = value
                }
            }
            result.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun parseManually(style: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        splitCssTopLevel(style, ';').forEach { declaration ->
            val index = declaration.indexOf(':')
            if (index > 0) {
                result[declaration.substring(0, index).trim().lowercase()] = declaration.substring(index + 1).trim()
            }
        }
        return result
    }
}

internal data class RichCssDeclarationParseResult(
    val declarations: Map<String, String>,
    val fallbackUsed: Boolean,
)

private fun parseCssDeclarations(style: String): Map<String, String> = RichCssParser.parseDeclarationMap(style)

private fun splitCssTopLevel(value: String, delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var quote: Char? = null
    var start = 0
    value.forEachIndexed { index, char ->
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '(' -> depth += 1
            quote == null && char == ')' -> depth = (depth - 1).coerceAtLeast(0)
            quote == null && depth == 0 && char == delimiter -> {
                parts += value.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    parts += value.substring(start).trim()
    return parts.filter { it.isNotBlank() }
}

private fun splitCssTopLevelWhitespace(value: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var quote: Char? = null
    var start = 0
    value.forEachIndexed { index, char ->
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '(' -> depth += 1
            quote == null && char == ')' -> depth = (depth - 1).coerceAtLeast(0)
            quote == null && depth == 0 && char.isWhitespace() -> {
                if (start < index) parts += value.substring(start, index).trim()
                start = index + 1
            }
        }
    }
    if (start < value.length) parts += value.substring(start).trim()
    return parts.filter { it.isNotBlank() }
}

private fun resolveVars(value: String, variables: Map<String, String>): String {
    return Regex("""var\(\s*(--[\w-]+)(?:\s*,\s*([^)]+))?\)""").replace(value) { match ->
        variables[match.groupValues[1]] ?: match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: match.value
    }
}

private fun String.removeCssComments(): String = replace(Regex("""/\*[\s\S]*?\*/"""), "")

private fun findMatchingBrace(text: String, openIndex: Int): Int {
    var depth = 0
    var quote: Char? = null
    for (index in openIndex until text.length) {
        val char = text[index]
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '{' -> depth += 1
            quote == null && char == '}' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }
    return -1
}

private fun mediaMatches(selector: String, options: RichHtmlCompileOptions): Boolean {
    val lower = selector.lowercase()
    if (lower.contains("prefers-color-scheme: dark") && !options.darkTheme) return false
    if (lower.contains("prefers-color-scheme: light") && options.darkTheme) return false
    Regex("""min-width\s*:\s*([0-9.]+)px""").find(lower)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
        if (options.viewportWidthDp < it) return false
    }
    Regex("""max-width\s*:\s*([0-9.]+)px""").find(lower)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
        if (options.viewportWidthDp > it) return false
    }
    return true
}

private fun hintsForKeyframes(selector: String, body: String): RichKeyframesSummary? {
    val name = selector.substringAfter("@keyframes", "").trim().takeIf { it.isNotBlank() } ?: return null
    val frames = parseKeyframeDeclarations(body)
    val properties = if (frames.isEmpty()) {
        CSS_PROPERTY_NAME.findAll(body)
            .map { it.groupValues[1].trim().lowercase() }
            .filterNot { it.matches(Regex("""\d+%|from|to""")) }
            .toSet()
    } else {
        frames.flatMap { it.declarations.keys }.map { it.lowercase() }.toSet()
    }
    val fromDeclarations = frames.firstOrNull { it.isFrom }?.declarations.orEmpty()
    val toDeclarations = frames.lastOrNull { it.isTo }?.declarations.orEmpty()
    val hasIntermediateFrame = frames.any { !it.isFrom && !it.isTo }
    return RichKeyframesSummary(
        name = name,
        properties = properties,
        fromDeclarations = fromDeclarations,
        toDeclarations = toDeclarations,
        hasIntermediateFrame = hasIntermediateFrame,
        frames = frames.sortedBy { it.progress },
    )
}

private data class ParsedKeyframeDeclarations(
    val isFrom: Boolean,
    val isTo: Boolean,
    val progress: Float,
    val declarations: Map<String, String>,
)

private fun parseKeyframeDeclarations(body: String): List<ParsedKeyframeDeclarations> {
    val frames = mutableListOf<ParsedKeyframeDeclarations>()
    var cursor = 0
    while (cursor < body.length) {
        val start = body.indexOf('{', cursor)
        if (start < 0) break
        val selector = body.substring(cursor, start).trim()
        val end = findMatchingBrace(body, start)
        if (end < 0) break
        val declarations = parseCssDeclarations(body.substring(start + 1, end))
        selector.split(",")
            .map { it.trim().lowercase() }
            .mapNotNull { label -> parseKeyframeProgress(label)?.let { progress -> label to progress } }
            .forEach { (label, progress) ->
                frames += ParsedKeyframeDeclarations(
                    isFrom = label == "from" || progress <= 0f,
                    isTo = label == "to" || progress >= 1f,
                    progress = progress.coerceIn(0f, 1f),
                    declarations = declarations,
                )
            }
        cursor = end + 1
    }
    return frames
}

private fun parseKeyframeProgress(label: String): Float? {
    return when {
        label == "from" -> 0f
        label == "to" -> 1f
        label.endsWith("%") -> label.removeSuffix("%").trim().toFloatOrNull()?.div(100f)
        else -> null
    }?.coerceIn(0f, 1f)
}

private fun matchCompoundSelector(element: Element, selector: String): Boolean {
    val normalized = selector.trim()
    val notMatch = Regex(""":not\(([^()]*)\)""").find(normalized)
    if (notMatch != null) {
        val without = normalized.removeRange(notMatch.range).trim().ifBlank { "*" }
        return matchCompoundSelector(element, without) && !matchCompoundSelector(element, notMatch.groupValues[1])
    }
    findTopLevelCombinator(normalized, '>')?.let { index ->
        return matchCompoundSelector(element, normalized.substring(index + 1)) &&
            element.parent()?.let { matchCompoundSelector(it, normalized.substring(0, index)) } == true
    }
    findTopLevelCombinator(normalized, '+')?.let { index ->
        return matchCompoundSelector(element, normalized.substring(index + 1)) &&
            element.previousElementSibling()?.let { matchCompoundSelector(it, normalized.substring(0, index)) } == true
    }
    findTopLevelCombinator(normalized, '~')?.let { index ->
        return matchCompoundSelector(element, normalized.substring(index + 1)) &&
            element.previousElementSiblings().any { matchCompoundSelector(it, normalized.substring(0, index)) }
    }
    splitDescendantSelector(normalized)?.let { (ancestor, child) ->
        return matchCompoundSelector(element, child) && element.parents().any { matchCompoundSelector(it, ancestor) }
    }
    return matchSimpleSelector(element, normalized)
}

private fun findTopLevelCombinator(selector: String, combinator: Char): Int? {
    var depth = 0
    for (index in selector.indices.reversed()) {
        when (selector[index]) {
            ')', ']' -> depth += 1
            '(', '[' -> depth -= 1
            combinator -> if (depth == 0) return index
        }
    }
    return null
}

private fun splitDescendantSelector(selector: String): Pair<String, String>? {
    var depth = 0
    for (index in selector.indices.reversed()) {
        val char = selector[index]
        when (char) {
            ')', ']' -> depth += 1
            '(', '[' -> depth -= 1
            ' ' -> if (depth == 0) {
                val left = selector.substring(0, index).trim()
                val right = selector.substring(index + 1).trim()
                if (left.isNotBlank() && right.isNotBlank()) return left to right
            }
        }
    }
    return null
}

private fun matchSimpleSelector(element: Element, selector: String): Boolean {
    val simple = selector.trim()
    if (simple == "*" || simple.isBlank()) return true
    Regex(""":(first-child|last-child|nth-child\(([^)]*)\))""").findAll(simple).forEach { pseudo ->
        when {
            pseudo.value.startsWith(":first-child") && element.elementSiblingIndex() != 0 -> return false
            pseudo.value.startsWith(":last-child") && element.elementSiblingIndex() != element.parent()?.children()?.lastIndex -> return false
            pseudo.value.startsWith(":nth-child") -> {
                val expected = pseudo.groupValues[2].trim()
                val oneBased = element.elementSiblingIndex() + 1
                val ok = when (expected) {
                    "odd" -> oneBased % 2 == 1
                    "even" -> oneBased % 2 == 0
                    else -> expected.toIntOrNull() == oneBased
                }
                if (!ok) return false
            }
        }
    }
    val stripped = simple.replace(Regex(""":[\w-]+(?:\([^)]*\))?"""), "")
    val tag = Regex("""^[A-Za-z][\w-]*""").find(stripped)?.value
    if (tag != null && !element.tagName().equals(tag, ignoreCase = true)) return false
    Regex("""#([A-Za-z_][\w-]*)""").findAll(stripped).forEach { if (element.id() != it.groupValues[1]) return false }
    Regex("""\.([A-Za-z_][\w-]*)""").findAll(stripped).forEach { if (!element.hasClass(it.groupValues[1])) return false }
    Regex("""\[([^\]=~|^$*]+)(?:([~|^$*]?=)['"]?([^'"]+)['"]?)?]""").findAll(stripped).forEach { match ->
        val name = match.groupValues[1].trim()
        val op = match.groupValues[2]
        val expected = match.groupValues[3]
        if (!element.hasAttr(name)) return false
        val actual = element.attr(name)
        val ok = when (op) {
            "" -> true
            "=" -> actual == expected
            "~=" -> actual.split(Regex("\\s+")).contains(expected)
            "^=" -> actual.startsWith(expected)
            "$=" -> actual.endsWith(expected)
            "*=" -> actual.contains(expected)
            "|=" -> actual == expected || actual.startsWith("$expected-")
            else -> true
        }
        if (!ok) return false
    }
    return true
}

private fun parseDisplay(value: String): RichDisplay? = when (value.trim().lowercase()) {
    "none" -> RichDisplay.None
    "inline" -> RichDisplay.Inline
    "inline-block" -> RichDisplay.InlineBlock
    "flex" -> RichDisplay.Flex
    "inline-flex" -> RichDisplay.InlineFlex
    "grid" -> RichDisplay.Grid
    "inline-grid" -> RichDisplay.InlineGrid
    "block", "flow-root", "table" -> RichDisplay.Block
    else -> null
}

private fun parsePosition(value: String): RichPosition? = when (value.trim().lowercase()) {
    "relative" -> RichPosition.Relative
    "absolute" -> RichPosition.Absolute
    "fixed" -> RichPosition.Fixed
    "sticky" -> RichPosition.Sticky
    "static" -> RichPosition.Static
    else -> null
}

private fun parseVisibility(value: String): RichVisibility? = when (value.trim().lowercase()) {
    "hidden", "collapse" -> RichVisibility.Hidden
    "visible" -> RichVisibility.Visible
    else -> null
}

private fun parseOverflow(value: String): RichOverflow? = when (value.trim().lowercase()) {
    "hidden", "clip" -> RichOverflow.Hidden
    "scroll" -> RichOverflow.Scroll
    "auto" -> RichOverflow.Auto
    "visible" -> RichOverflow.Visible
    else -> null
}

private data class ParsedCssGap(
    val rowGap: Dp,
    val columnGap: Dp,
)

private fun parseCssGap(value: String, context: CssLengthContext): ParsedCssGap? {
    val parts = splitCssTopLevelWhitespace(value.trim()).mapNotNull { parseCssDp(it, context) }
    return when (parts.size) {
        1 -> ParsedCssGap(parts[0], parts[0])
        2 -> ParsedCssGap(parts[0], parts[1])
        else -> null
    }
}

private data class ParsedFlexFlow(
    val direction: RichFlexDirection? = null,
    val wrap: RichFlexWrap? = null,
)

private fun parseFlexFlow(value: String): ParsedFlexFlow? {
    var direction: RichFlexDirection? = null
    var wrap: RichFlexWrap? = null
    splitCssTopLevelWhitespace(value.trim()).forEach { token ->
        parseFlexDirection(token)?.let { direction = it }
        parseFlexWrap(token)?.let { wrap = it }
    }
    return if (direction == null && wrap == null) null else ParsedFlexFlow(direction, wrap)
}

private fun parseFlexDirection(value: String): RichFlexDirection? = when (value.trim().lowercase()) {
    "column" -> RichFlexDirection.Column
    "column-reverse" -> RichFlexDirection.ColumnReverse
    "row" -> RichFlexDirection.Row
    "row-reverse" -> RichFlexDirection.RowReverse
    else -> null
}

private fun parseFlexWrap(value: String): RichFlexWrap? = when (value.trim().lowercase()) {
    "nowrap" -> RichFlexWrap.NoWrap
    "wrap" -> RichFlexWrap.Wrap
    "wrap-reverse" -> RichFlexWrap.WrapReverse
    else -> null
}

private data class ParsedFlexShorthand(
    val grow: Float? = null,
    val shrink: Float? = null,
    val basis: RichSize? = null,
)

private fun parseFlexShorthand(value: String, context: CssLengthContext): ParsedFlexShorthand? {
    val normalized = value.trim().lowercase()
    return when (normalized) {
        "none" -> ParsedFlexShorthand(grow = 0f, shrink = 0f, basis = RichSize.Auto)
        "auto" -> ParsedFlexShorthand(grow = 1f, shrink = 1f, basis = RichSize.Auto)
        "initial" -> ParsedFlexShorthand(grow = 0f, shrink = 1f, basis = RichSize.Auto)
        else -> {
            val parts = splitCssTopLevelWhitespace(normalized)
            if (parts.isEmpty()) return null
            val numbers = mutableListOf<Float>()
            var basis: RichSize? = null
            parts.forEach { part ->
                val number = parseCssFloat(part)
                if (number != null && !part.endsWith("%")) {
                    numbers += number
                } else {
                    parseRichSize(part, context)?.let { basis = it }
                }
            }
            when {
                numbers.isEmpty() && basis == null -> null
                numbers.size == 1 && basis == null -> ParsedFlexShorthand(grow = numbers[0], shrink = 1f, basis = RichSize.DpSize(0.dp))
                else -> ParsedFlexShorthand(
                    grow = numbers.getOrNull(0),
                    shrink = numbers.getOrNull(1),
                    basis = basis,
                )
            }
        }
    }
}

private fun parseJustify(value: String): RichJustify? = when (value.trim().lowercase()) {
    "center" -> RichJustify.Center
    "flex-end", "end", "right" -> RichJustify.End
    "space-between" -> RichJustify.SpaceBetween
    "space-around" -> RichJustify.SpaceAround
    "space-evenly" -> RichJustify.SpaceEvenly
    "flex-start", "start", "left", "normal" -> RichJustify.Start
    else -> null
}

private fun parseAlign(value: String): RichAlign? = when (value.trim().lowercase()) {
    "center" -> RichAlign.Center
    "flex-end", "end", "bottom" -> RichAlign.End
    "stretch" -> RichAlign.Stretch
    "baseline", "first baseline", "last baseline" -> RichAlign.Baseline
    "flex-start", "start", "top" -> RichAlign.Start
    else -> null
}

private fun parseAlignContent(value: String): RichAlignContent? = when (value.trim().lowercase()) {
    "center" -> RichAlignContent.Center
    "flex-end", "end", "bottom" -> RichAlignContent.End
    "space-between" -> RichAlignContent.SpaceBetween
    "space-around" -> RichAlignContent.SpaceAround
    "space-evenly" -> RichAlignContent.SpaceEvenly
    "stretch" -> RichAlignContent.Stretch
    "normal" -> RichAlignContent.Stretch
    "flex-start", "start", "top" -> RichAlignContent.Start
    else -> null
}

private fun parseWhiteSpace(value: String): RichWhiteSpace? = when (value.trim().lowercase()) {
    "nowrap" -> RichWhiteSpace.NoWrap
    "pre" -> RichWhiteSpace.Pre
    "pre-wrap" -> RichWhiteSpace.PreWrap
    "pre-line" -> RichWhiteSpace.PreLine
    "normal" -> RichWhiteSpace.Normal
    else -> null
}

private fun parseWordBreak(value: String): RichWordBreak? = when (value.trim().lowercase()) {
    "break-all" -> RichWordBreak.BreakAll
    "break-word", "anywhere" -> RichWordBreak.BreakWord
    "normal" -> RichWordBreak.Normal
    else -> null
}

private fun parseTextOverflow(value: String): RichTextOverflow? = when (value.trim().lowercase()) {
    "ellipsis" -> RichTextOverflow.Ellipsis
    "clip" -> RichTextOverflow.Clip
    else -> null
}

private fun parseTextDecoration(value: String): TextDecoration? = when {
    value.contains("underline", ignoreCase = true) -> TextDecoration.Underline
    value.contains("line-through", ignoreCase = true) -> TextDecoration.LineThrough
    value.contains("none", ignoreCase = true) -> TextDecoration.None
    else -> null
}

private data class RichGridPlacement(
    val start: Int? = null,
    val span: Int = 1,
)

private fun parseGridPlacement(
    shorthand: String?,
    start: String?,
    end: String?,
): RichGridPlacement {
    val shorthandPlacement = shorthand?.let(::parseGridPlacementShorthand)
    val startLine = start?.let(::parseGridLine)
    val endLine = end?.let(::parseGridLine)
    val startSpan = start?.let(::parseGridSpanToken)
    val endSpan = end?.let(::parseGridSpanToken)
    val explicitStart = startLine ?: shorthandPlacement?.start
    val explicitEnd = endLine ?: shorthandPlacement?.end
    val span = endSpan ?: startSpan ?: shorthandPlacement?.span ?: when {
        explicitStart != null && explicitEnd != null && explicitEnd > explicitStart -> explicitEnd - explicitStart
        else -> 1
    }
    val inferredStart = explicitStart ?: if (explicitEnd != null && span > 1) explicitEnd - span else null
    return RichGridPlacement(
        start = inferredStart?.takeIf { it > 0 }?.coerceAtMost(8),
        span = span.coerceIn(1, 8),
    )
}

private data class ParsedGridPlacement(
    val start: Int? = null,
    val end: Int? = null,
    val span: Int? = null,
)

private fun parseGridPlacementShorthand(value: String): ParsedGridPlacement {
    val parts = value.lowercase().split("/").map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return ParsedGridPlacement()
    if (parts.size == 1) {
        return parseGridSpanToken(parts[0])?.let { ParsedGridPlacement(span = it) }
            ?: ParsedGridPlacement(start = parseGridLine(parts[0]))
    }
    val first = parts[0]
    val second = parts[1]
    val firstSpan = parseGridSpanToken(first)
    val secondSpan = parseGridSpanToken(second)
    val firstLine = parseGridLine(first)
    val secondLine = parseGridLine(second)
    val span = secondSpan ?: firstSpan ?: if (firstLine != null && secondLine != null && secondLine > firstLine) {
        secondLine - firstLine
    } else {
        null
    }
    val start = firstLine ?: if (secondLine != null && firstSpan != null) secondLine - firstSpan else null
    val end = secondLine
    return ParsedGridPlacement(start = start, end = end, span = span)
}

private fun parseGridLine(value: String): Int? {
    val normalized = value.trim().lowercase()
    if (normalized == "auto" || normalized.startsWith("span")) return null
    return normalized.toIntOrNull()?.takeIf { it > 0 }
}

private fun parseGridSpanToken(value: String): Int? {
    return Regex("""\bspan\s+(\d+)""").find(value.lowercase())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.coerceIn(1, 8)
}

private fun parseBorderCollapse(value: String): RichBorderCollapse? = when (value.trim().lowercase()) {
    "collapse" -> RichBorderCollapse.Collapse
    "separate" -> RichBorderCollapse.Separate
    else -> null
}

private fun parseCaptionSide(value: String): RichCaptionSide? = when (value.trim().lowercase()) {
    "bottom" -> RichCaptionSide.Bottom
    "top" -> RichCaptionSide.Top
    else -> null
}

private data class CssLengthContext(
    val viewportWidth: Dp,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val rootFontSize: TextUnit = 16.sp,
)

private fun CssLengthContext.fontSizeDp(): Dp {
    return fontSize.cssReferenceFontSize().value.dp
}

private fun CssLengthContext.rootFontSizeDp(): Dp {
    return rootFontSize.cssReferenceFontSize().value.dp
}

private fun TextUnit.cssReferenceFontSize(): TextUnit {
    if (!isSpecified || !value.isFinite() || value <= 0f) return DEFAULT_CSS_FONT_SIZE
    return when (type) {
        TextUnitType.Sp -> this
        TextUnitType.Em -> (DEFAULT_CSS_FONT_SIZE.value * value).sp
        else -> DEFAULT_CSS_FONT_SIZE
    }
}

private fun TextUnit.takeIfFiniteCssTextUnit(): TextUnit? {
    return takeIf { it.isSpecified && it.value.isFinite() && it.value > 0f }
}

private fun parseGridColumns(value: String, context: CssLengthContext, gap: Dp = 0.dp): RichGridColumns? {
    val normalized = value.trim().lowercase()
    Regex("""repeat\(\s*(auto-fit|auto-fill)\s*,\s*minmax\(\s*([^,]+?)\s*,""")
        .find(normalized)?.let { match ->
            parseCssDp(match.groupValues[2], context)?.let { minColumnWidth ->
                val available = context.viewportWidth
                val estimatedColumns = (
                    (available.value + gap.value) /
                        (minColumnWidth.value + gap.value).coerceAtLeast(1f)
                    )
                    .toInt()
                    .coerceAtLeast(1)
                return if (estimatedColumns <= 1) {
                    RichGridColumns.Count(1)
                } else {
                    RichGridColumns.AutoFit(minColumnWidth)
                }
            }
        }
    Regex("""repeat\(\s*(\d+)\s*,""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return RichGridColumns.Count(it.coerceIn(1, 8))
    }
    val frCount = Regex("""(?:^|\s)[0-9.]*fr(?:\s|$)""").findAll(" $normalized ").count()
    return frCount.takeIf { it > 0 }?.let { RichGridColumns.Count(it.coerceIn(1, 8)) }
}

private fun parseRichSize(value: String, context: CssLengthContext): RichSize? {
    val normalized = value.trim().lowercase()
    if (normalized == "auto") return RichSize.Auto
    if (normalized.endsWith("%")) return normalized.removeSuffix("%").toFloatOrNull()?.let {
        RichSize.Fraction((it / 100f).coerceIn(0.01f, 1f))
    }
    return parseCssDp(normalized, context)?.let(RichSize::DpSize)
}

private fun parseOffset(css: Map<String, String>, parent: RichOffset, context: CssLengthContext): RichOffset {
    return parent.copy(
        left = css["left"]?.let { parseCssDp(it, context) } ?: parent.left,
        top = css["top"]?.let { parseCssDp(it, context) } ?: parent.top,
        right = css["right"]?.let { parseCssDp(it, context) } ?: parent.right,
        bottom = css["bottom"]?.let { parseCssDp(it, context) } ?: parent.bottom,
    )
}

private fun parseTransform(value: String, context: CssLengthContext): RichTransform {
    var transform = RichTransform.None
    Regex("""([a-zA-Z0-9]+)\(([^)]*)\)""").findAll(value).forEach { match ->
        val name = match.groupValues[1].lowercase()
        val args = splitCssTopLevel(match.groupValues[2], ',').ifEmpty {
            match.groupValues[2].split(Regex("\\s+")).filter { it.isNotBlank() }
        }
        when (name) {
            "translate", "translate3d" -> transform = transform.copy(
                translateX = args.getOrNull(0)?.let { parseCssDp(it, context) } ?: transform.translateX,
                translateY = args.getOrNull(1)?.let { parseCssDp(it, context) } ?: transform.translateY,
            )
            "translatex" -> transform = transform.copy(translateX = args.firstOrNull()?.let { parseCssDp(it, context) } ?: transform.translateX)
            "translatey" -> transform = transform.copy(translateY = args.firstOrNull()?.let { parseCssDp(it, context) } ?: transform.translateY)
            "scale" -> {
                val x = args.getOrNull(0)?.toFloatOrNull() ?: transform.scaleX
                transform = transform.copy(scaleX = x, scaleY = args.getOrNull(1)?.toFloatOrNull() ?: x)
            }
            "scalex" -> transform = transform.copy(scaleX = args.firstOrNull()?.toFloatOrNull() ?: transform.scaleX)
            "scaley" -> transform = transform.copy(scaleY = args.firstOrNull()?.toFloatOrNull() ?: transform.scaleY)
            "rotate", "rotatez" -> transform = transform.copy(rotateZ = parseDegrees(args.firstOrNull()) ?: transform.rotateZ)
            "skew" -> transform = transform.copy(
                skewX = parseDegrees(args.getOrNull(0)) ?: transform.skewX,
                skewY = parseDegrees(args.getOrNull(1)) ?: transform.skewY,
            )
            "skewx" -> transform = transform.copy(skewX = parseDegrees(args.firstOrNull()) ?: transform.skewX)
            "skewy" -> transform = transform.copy(skewY = parseDegrees(args.firstOrNull()) ?: transform.skewY)
        }
    }
    return transform
}

private fun parseDegrees(value: String?): Float? {
    val normalized = value?.trim()?.lowercase() ?: return null
    return when {
        normalized.endsWith("deg") -> normalized.removeSuffix("deg").toFloatOrNull()
        normalized.endsWith("rad") -> normalized.removeSuffix("rad").toFloatOrNull()?.times(57.29578f)
        else -> normalized.toFloatOrNull()
    }
}

private fun parseCssSpacing(css: Map<String, String>, prefix: String, context: CssLengthContext): RichSpacing? {
    val base = css[prefix]?.let { parseSpacingTokens(it, context) }
    val top = css["$prefix-top"]?.let { parseCssDp(it, context) } ?: base?.top
    val right = css["$prefix-right"]?.let { parseCssDp(it, context) } ?: base?.right
    val bottom = css["$prefix-bottom"]?.let { parseCssDp(it, context) } ?: base?.bottom
    val left = css["$prefix-left"]?.let { parseCssDp(it, context) } ?: base?.left
    return if (top == null && right == null && bottom == null && left == null) null else {
        RichSpacing(top ?: 0.dp, right ?: 0.dp, bottom ?: 0.dp, left ?: 0.dp)
    }
}

private fun parseSpacingTokens(value: String, context: CssLengthContext): RichSpacing? {
    val parsed = splitCssTopLevelWhitespace(value.trim()).mapNotNull { parseCssDp(it, context) }
    return when (parsed.size) {
        1 -> RichSpacing.all(parsed[0])
        2 -> RichSpacing(parsed[0], parsed[1], parsed[0], parsed[1])
        3 -> RichSpacing(parsed[0], parsed[1], parsed[2], parsed[1])
        4 -> RichSpacing(parsed[0], parsed[1], parsed[2], parsed[3])
        else -> null
    }
}

private fun parseCssCornerRadius(css: Map<String, String>, context: CssLengthContext): RichCornerRadius? {
    val base = css["border-radius"]?.let { parseCornerTokens(it, context) }
    val topStart = css["border-top-left-radius"]?.let { parseCssDp(it, context) } ?: base?.topStart
    val topEnd = css["border-top-right-radius"]?.let { parseCssDp(it, context) } ?: base?.topEnd
    val bottomEnd = css["border-bottom-right-radius"]?.let { parseCssDp(it, context) } ?: base?.bottomEnd
    val bottomStart = css["border-bottom-left-radius"]?.let { parseCssDp(it, context) } ?: base?.bottomStart
    return if (topStart == null && topEnd == null && bottomEnd == null && bottomStart == null) null else {
        RichCornerRadius(topStart ?: 0.dp, topEnd ?: 0.dp, bottomEnd ?: 0.dp, bottomStart ?: 0.dp)
    }
}

private fun parseCornerTokens(value: String, context: CssLengthContext): RichCornerRadius? {
    val firstLayer = value.substringBefore("/")
    val parsed = splitCssTopLevelWhitespace(firstLayer.trim()).mapNotNull { parseCssDp(it, context) }
    return when (parsed.size) {
        1 -> RichCornerRadius.all(parsed[0])
        2 -> RichCornerRadius(parsed[0], parsed[1], parsed[0], parsed[1])
        3 -> RichCornerRadius(parsed[0], parsed[1], parsed[2], parsed[1])
        4 -> RichCornerRadius(parsed[0], parsed[1], parsed[2], parsed[3])
        else -> null
    }
}

private fun parseBorder(
    css: Map<String, String>,
    parent: RichBorder,
    context: CssLengthContext,
    currentColor: Color?,
): RichBorder {
    val shorthand = css["border"]?.let { parseBorderSide(it, context, currentColor) }
    fun side(name: String, current: RichBorderSide): RichBorderSide {
        val parsed = css["border-$name"]?.let { parseBorderSide(it, context, currentColor) }
        val width = css["border-$name-width"]?.let { parseCssDp(it, context) }
        val color = css["border-$name-color"]?.let { resolveRichCssColor(parseRichCssColor(it), currentColor) }
        val style = css["border-$name-style"]?.let(::parseBorderStyle)
        return current.copy(
            width = width ?: parsed?.width ?: shorthand?.width ?: current.width,
            color = color ?: parsed?.color ?: shorthand?.color ?: current.color,
            style = style ?: parsed?.style ?: shorthand?.style ?: current.style,
        )
    }
    val baseWidth = css["border-width"]?.let { parseCssDp(it, context) }
    val baseColor = css["border-color"]?.let { resolveRichCssColor(parseRichCssColor(it), currentColor) }
    val baseStyle = css["border-style"]?.let(::parseBorderStyle)
    val base = shorthand?.let { RichBorder.all(it) } ?: parent
    val withBase = if (baseWidth != null || baseColor != null || baseStyle != null) {
        RichBorder.all(
            RichBorderSide(
                width = baseWidth ?: base.top.width,
                color = baseColor ?: base.top.color,
                style = baseStyle ?: base.top.style,
            )
        )
    } else {
        base
    }
    return RichBorder(
        top = side("top", withBase.top),
        right = side("right", withBase.right),
        bottom = side("bottom", withBase.bottom),
        left = side("left", withBase.left),
    )
}

private fun parseBorderSide(value: String, context: CssLengthContext, currentColor: Color?): RichBorderSide? {
    val parts = value.split(Regex("\\s+"))
    val width = parts.firstNotNullOfOrNull { parseCssDp(it, context) } ?: 1.dp
    val color = parts.firstNotNullOfOrNull { resolveRichCssColor(parseRichCssColor(it), currentColor) } ?: currentColor ?: Color.Gray
    val style = parts.firstNotNullOfOrNull(::parseBorderStyle) ?: RichBorderStyle.Solid
    return RichBorderSide(width, color, style)
}

private fun parseBorderStyle(value: String): RichBorderStyle? = when (value.trim().lowercase()) {
    "none", "hidden" -> RichBorderStyle.None
    "solid" -> RichBorderStyle.Solid
    "dashed" -> RichBorderStyle.Dashed
    "dotted" -> RichBorderStyle.Dotted
    "double" -> RichBorderStyle.Double
    else -> null
}

private fun parseShadows(value: String, context: CssLengthContext, currentColor: Color?): List<RichShadow> {
    if (value.equals("none", ignoreCase = true)) return emptyList()
    return splitCssTopLevel(value, ',').mapNotNull { shadow ->
        val inset = shadow.contains("inset", ignoreCase = true)
        val color = CSS_COLOR_TOKEN.find(shadow)?.value
            ?.let { resolveRichCssColor(parseRichCssColor(it), currentColor) }
            ?: Color.Black.copy(alpha = 0.24f)
        val lengths = CSS_LENGTH_TOKEN.findAll(shadow).mapNotNull { parseCssDp(it.value, context) }.toList()
        if (lengths.isEmpty()) null else RichShadow(
            offsetX = lengths.getOrNull(0) ?: 0.dp,
            offsetY = lengths.getOrNull(1) ?: 0.dp,
            blurRadius = lengths.getOrNull(2)?.coerceIn(0.dp, 96.dp) ?: 0.dp,
            spread = lengths.getOrNull(3)?.coerceIn((-48).dp, 48.dp) ?: 0.dp,
            color = color,
            inset = inset,
        )
    }
}

private fun parseTextShadow(value: String, currentColor: Color?): RichTextShadow? {
    if (value.equals("none", ignoreCase = true)) return null
    val color = CSS_COLOR_TOKEN.find(value)?.value
        ?.let { resolveRichCssColor(parseRichCssColor(it), currentColor) }
        ?: Color.Black.copy(alpha = 0.35f)
    val lengths = CSS_LENGTH_TOKEN.findAll(value).mapNotNull { parseCssLengthFloat(it.value) }.toList()
    return RichTextShadow(
        offsetX = lengths.getOrNull(0) ?: 0f,
        offsetY = lengths.getOrNull(1) ?: 1f,
        blurRadius = lengths.getOrNull(2) ?: 2f,
        color = color,
    )
}

private fun parseAnimationStyle(
    declarations: Map<String, String>,
    keyframes: Map<String, RichKeyframesSummary>,
    context: CssLengthContext,
): RichAnimationStyle? {
    val shorthand = declarations["animation"]
    val nameDeclaration = declarations["animation-name"]
    if (shorthand == null && nameDeclaration == null && declarations.keys.none { it.startsWith("animation-") }) return null
    if (shorthand?.trim()?.equals("none", ignoreCase = true) == true || nameDeclaration?.trim()?.equals("none", ignoreCase = true) == true) {
        return RichAnimationStyle.None
    }
    val shorthandParts = shorthand?.let { splitCssTopLevel(it, ',') }.orEmpty()
    val firstShorthand = shorthandParts.firstOrNull().orEmpty()
    val shorthandTokens = splitCssTopLevelWhitespace(firstShorthand)
    val explicitNames = nameDeclaration
        ?.let { splitCssTopLevel(it, ',') }
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        .orEmpty()
    val names = explicitNames.ifEmpty {
        shorthandParts.mapNotNull { part ->
            splitCssTopLevelWhitespace(part).firstOrNull { token -> token.isLikelyAnimationName() }
        }.ifEmpty {
            shorthandTokens.filter { token -> token.isLikelyAnimationName() }
        }
    }
    val timeTokens = shorthandTokens.mapNotNull(::parseCssTimeMs)
    val durationMs = declarations["animation-duration"]?.let(::firstCssCommaValue)?.let(::parseCssTimeMs)
        ?: timeTokens.getOrNull(0)
        ?: 0
    val delayMs = declarations["animation-delay"]?.let(::firstCssCommaValue)?.let(::parseCssTimeMs)
        ?: timeTokens.getOrNull(1)
        ?: 0
    val iterationCount = declarations["animation-iteration-count"]?.let(::firstCssCommaValue)?.let(::parseAnimationIterationCount)
        ?: shorthandTokens.firstNotNullOfOrNull(::parseAnimationIterationCount)
        ?: 1f
    val fillModeValue = declarations["animation-fill-mode"].orEmpty() + " " + shorthandTokens.joinToString(" ")
    val fillMode = declarations["animation-fill-mode"]?.let(::firstCssCommaValue)?.let(::parseAnimationFillMode)
        ?: shorthandTokens.firstNotNullOfOrNull(::parseAnimationFillMode)
        ?: RichAnimationFillMode.None
    val fillModeForwards = fillMode == RichAnimationFillMode.Forwards || fillMode == RichAnimationFillMode.Both ||
        fillModeValue.contains("forwards", ignoreCase = true) || fillModeValue.contains("both", ignoreCase = true)
    val direction = declarations["animation-direction"]?.let(::firstCssCommaValue)?.let(::parseAnimationDirection)
        ?: shorthandTokens.firstNotNullOfOrNull(::parseAnimationDirection)
        ?: RichAnimationDirection.Normal
    val easing = declarations["animation-timing-function"]?.let(::firstCssCommaValue)?.let(::parseAnimationEasing)
        ?: shorthandTokens.firstNotNullOfOrNull(::parseAnimationEasing)
        ?: RichAnimationEasing.Ease
    val summaries = names.mapNotNull { keyframes[it] }
    val hasOpacityOrTransform = summaries.any { it.hasOpacityOrTransform } ||
        shorthand.orEmpty().contains("opacity", ignoreCase = true) ||
        declarations.values.any { it.contains("transform", ignoreCase = true) }
    val hasLayoutProperty = summaries.any { it.hasLayoutProperty } ||
        declarations["animation-property"]?.let(::containsLayoutAnimationProperty) == true
    val declaredAnimationCount = maxOf(1, shorthandParts.size, explicitNames.size)
    val unsupportedPropertyCount = summaries.sumOf { summary ->
        summary.properties.count { it !in NATIVE_ANIMATION_PROPERTIES }
    } + (declaredAnimationCount - 1).coerceAtLeast(0)
    val multiKeyframeCount = summaries.count { it.hasIntermediateFrame }
    val firstSummary = names.firstNotNullOfOrNull { keyframes[it] }
    val firstHasLayoutProperty = firstSummary?.hasLayoutProperty == true ||
        declarations["animation-property"]?.let(::containsLayoutAnimationProperty) == true
    val nativeAnimation = buildNativeAnimation(
        summaries = firstSummary?.let(::listOf).orEmpty(),
        names = names,
        durationMs = durationMs,
        delayMs = delayMs,
        iterationCount = iterationCount,
        fillMode = fillMode,
        direction = direction,
        easing = easing,
        hasLayoutProperty = firstHasLayoutProperty,
        context = context,
    )
    val staticOpacity = summaries.firstOrNull()?.staticOpacityForSafeInfinite()
        ?: if (iterationCount.isInfinite() && summaries.any { it.hasOpacityOrTransform }) 1f else null
    return RichAnimationStyle(
        names = names,
        durationMs = durationMs.coerceIn(0, 10_000),
        delayMs = delayMs.coerceIn(0, 10_000),
        iterationCount = iterationCount,
        fillModeForwards = fillModeForwards,
        fillMode = fillMode,
        direction = direction,
        easing = easing,
        hasLayoutProperty = hasLayoutProperty,
        hasOpacityOrTransform = hasOpacityOrTransform || names.isNotEmpty(),
        nativeAnimation = nativeAnimation,
        staticOpacity = if (iterationCount.isInfinite()) staticOpacity else null,
        declaredAnimationCount = declaredAnimationCount,
        multiKeyframeCount = multiKeyframeCount,
        unsupportedPropertyCount = unsupportedPropertyCount,
    )
}

private fun buildNativeAnimation(
    summaries: List<RichKeyframesSummary>,
    names: List<String>,
    durationMs: Int,
    delayMs: Int,
    iterationCount: Float,
    fillMode: RichAnimationFillMode,
    direction: RichAnimationDirection,
    easing: RichAnimationEasing,
    hasLayoutProperty: Boolean,
    context: CssLengthContext,
): RichNativeAnimation? {
    val summary = summaries.firstOrNull() ?: return null
    if (names.isEmpty()) return null
    if (iterationCount.isInfinite() || hasLayoutProperty) return null
    if (durationMs !in 1..MAX_NATIVE_ANIMATION_DURATION_MS) return null
    if (delayMs !in 0..MAX_NATIVE_ANIMATION_DELAY_MS) return null
    if (summary.properties.any { it !in NATIVE_ANIMATION_PROPERTIES }) return null
    val iterationRounds = iterationCount.toInt().takeIf { it >= 1 && it <= MAX_NATIVE_ANIMATION_ITERATIONS } ?: return null
    if (kotlin.math.abs(iterationCount - iterationRounds.toFloat()) > 0.001f) return null
    val totalDurationMs = durationMs * iterationRounds
    if (totalDurationMs > MAX_NATIVE_ANIMATION_TOTAL_MS) return null
    val keyframeStops = summary.frames
        .groupBy { it.progress }
        .map { (progress, frames) ->
            val declarations = frames.last().declarations
            RichNativeAnimationStop(
                progress = progress,
                opacity = declarations["opacity"]?.let(::parseCssFloat)?.coerceIn(0f, 1f),
                transform = declarations["transform"]?.let { parseTransform(it, context) } ?: RichTransform.None,
            )
        }
        .sortedBy { it.progress }
    if (keyframeStops.none { it.progress <= 0f } || keyframeStops.none { it.progress >= 1f }) return null
    if (keyframeStops.all { it.opacity == null && it.transform == RichTransform.None }) {
        return null
    }
    val directedStops = when (direction) {
        RichAnimationDirection.Normal -> keyframeStops
        RichAnimationDirection.Reverse -> keyframeStops.map { it.copy(progress = 1f - it.progress) }.sortedBy { it.progress }
    }
    val fillAwareStops = if (fillMode == RichAnimationFillMode.None || fillMode == RichAnimationFillMode.Backwards) {
        directedStops.dropLast(1) + RichNativeAnimationStop(1f, opacity = 1f, transform = RichTransform.None)
    } else {
        directedStops
    }
    val from = fillAwareStops.first()
    val to = fillAwareStops.last()
    return RichNativeAnimation(
        fromOpacity = from.opacity,
        toOpacity = to.opacity,
        fromTransform = from.transform,
        toTransform = to.transform,
        durationMs = durationMs,
        delayMs = delayMs,
        totalDurationMs = totalDurationMs,
        iterationCount = iterationRounds,
        fillMode = fillMode,
        direction = direction,
        easing = easing,
        stops = fillAwareStops,
    )
}

private fun firstCssCommaValue(value: String): String {
    return splitCssTopLevel(value, ',').firstOrNull()?.trim().orEmpty()
}

private fun parseAnimationFillMode(value: String): RichAnimationFillMode? {
    return when (value.trim().lowercase()) {
        "none" -> RichAnimationFillMode.None
        "forwards" -> RichAnimationFillMode.Forwards
        "backwards" -> RichAnimationFillMode.Backwards
        "both" -> RichAnimationFillMode.Both
        else -> null
    }
}

private fun parseAnimationDirection(value: String): RichAnimationDirection? {
    return when (value.trim().lowercase()) {
        "normal" -> RichAnimationDirection.Normal
        "reverse" -> RichAnimationDirection.Reverse
        else -> null
    }
}

private fun parseAnimationEasing(value: String): RichAnimationEasing? {
    val normalized = value.trim().lowercase()
    return when (normalized) {
        "linear" -> RichAnimationEasing.Linear
        "ease" -> RichAnimationEasing.Ease
        "ease-in" -> RichAnimationEasing.EaseIn
        "ease-out" -> RichAnimationEasing.EaseOut
        "ease-in-out" -> RichAnimationEasing.EaseInOut
        else -> {
            val args = Regex("""cubic-bezier\(([^)]*)\)""", RegexOption.IGNORE_CASE)
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { splitCssTopLevel(it, ',') }
                ?.mapNotNull { it.trim().toFloatOrNull() }
            if (args?.size == 4) {
                RichAnimationEasing.CubicBezier(
                    x1 = args[0].coerceIn(0f, 1f),
                    y1 = args[1],
                    x2 = args[2].coerceIn(0f, 1f),
                    y2 = args[3],
                )
            } else {
                null
            }
        }
    }
}

private fun RichKeyframesSummary.staticOpacityForSafeInfinite(): Float? {
    if (properties.any { it != "opacity" }) return null
    return frames.mapNotNull { it.declarations["opacity"]?.let(::parseCssFloat)?.coerceIn(0f, 1f) }
        .maxOrNull()
}

private fun parseTransitionStyle(declarations: Map<String, String>): RichTransitionStyle? {
    val shorthand = declarations["transition"]
    val propertyDeclaration = declarations["transition-property"]
    if (shorthand == null && propertyDeclaration == null && declarations.keys.none { it.startsWith("transition-") }) return null
    if (shorthand?.trim()?.equals("none", ignoreCase = true) == true || propertyDeclaration?.trim()?.equals("none", ignoreCase = true) == true) {
        return RichTransitionStyle.None
    }
    val shorthandTokens = shorthand
        ?.let { splitCssTopLevel(it, ',').firstOrNull().orEmpty() }
        ?.let(::splitCssTopLevelWhitespace)
        .orEmpty()
    val properties = propertyDeclaration
        ?.split(",")
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotBlank() }
        ?: shorthandTokens.filter { token -> token.isLikelyTransitionProperty() }.map { it.lowercase() }
    val durationMs = declarations["transition-duration"]?.let(::parseCssTimeMs)
        ?: shorthandTokens.firstNotNullOfOrNull(::parseCssTimeMs)
        ?: 0
    return RichTransitionStyle(
        properties = properties.ifEmpty { listOf("all") },
        durationMs = durationMs.coerceIn(0, 10_000),
    )
}

private fun RichAnimationStyle.shouldStaticizeVisible(): Boolean {
    return isDeclared && fillModeForwards && !isInfinite && (hasOpacityOrTransform || names.isNotEmpty())
}

private fun String.isLikelyAnimationName(): Boolean {
    val lower = lowercase()
    if (parseCssTimeMs(this) != null) return false
    if (parseAnimationIterationCount(this) != null) return false
    if (lower in ANIMATION_SHORTHAND_KEYWORDS) return false
    if (lower.startsWith("cubic-bezier") || lower.startsWith("steps(")) return false
    return lower.matches(Regex("""-?[_a-zA-Z][\w-]*"""))
}

private fun String.isLikelyTransitionProperty(): Boolean {
    val lower = lowercase()
    if (parseCssTimeMs(this) != null) return false
    if (lower in TRANSITION_SHORTHAND_KEYWORDS) return false
    if (lower.startsWith("cubic-bezier") || lower.startsWith("steps(")) return false
    return lower.matches(Regex("""-?[_a-zA-Z][\w-]*"""))
}

private fun parseCssTimeMs(value: String): Int? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.endsWith("ms") -> normalized.removeSuffix("ms").toFloatOrNull()?.roundToInt()
        normalized.endsWith("s") -> normalized.removeSuffix("s").toFloatOrNull()?.times(1000f)?.roundToInt()
        else -> null
    }?.coerceAtLeast(0)
}

private fun parseAnimationIterationCount(value: String): Float? {
    val normalized = value.trim().lowercase()
    return when (normalized) {
        "infinite" -> Float.POSITIVE_INFINITY
        else -> normalized.toFloatOrNull()?.takeIf { it >= 0f }
    }
}

private fun containsLayoutAnimationProperty(value: String): Boolean {
    val lower = value.lowercase()
    return ANIMATED_LAYOUT_PROPERTIES.any { Regex("""(^|[\s,])${Regex.escape(it)}($|[\s,])""").containsMatchIn(lower) }
}

private fun parseCssFilter(value: String, context: CssLengthContext): RichCssFilter {
    if (value.equals("none", ignoreCase = true)) return RichCssFilter.None
    var blurRadius: Dp? = null
    var brightness: Float? = null
    var opacity: Float? = null
    var grayscale: Float? = null
    var unsupported = 0
    CSS_FUNCTION_TOKEN.findAll(value).forEach { match ->
        val name = match.groupValues[1].lowercase()
        val argument = match.groupValues[2].trim()
        when (name) {
            "blur" -> blurRadius = parseCssDp(argument, context)
            "brightness" -> brightness = parseCssFloat(argument)?.coerceAtLeast(0f)
            "opacity" -> opacity = parseCssFloat(argument)?.coerceIn(0f, 1f)
            "grayscale" -> grayscale = parseCssFloat(argument)?.coerceIn(0f, 1f)
            else -> unsupported += 1
        }
    }
    return RichCssFilter(
        blurRadius = blurRadius,
        brightness = brightness,
        opacity = opacity,
        grayscale = grayscale,
        unsupportedFunctions = unsupported,
    )
}

private fun parseClipPath(value: String, context: CssLengthContext): RichClipPath? {
    val normalized = value.trim()
    if (normalized.equals("none", ignoreCase = true)) return null
    val function = Regex("""^([a-zA-Z-]+)\((.*)\)$""", RegexOption.IGNORE_CASE)
        .find(normalized)
        ?: return null
    val name = function.groupValues[1].lowercase()
    val body = function.groupValues[2].trim()
    return when (name) {
        "inset" -> parseInsetClipPath(body, context)
        "circle" -> parseCircleClipPath(body, context)
        "ellipse" -> parseEllipseClipPath(body, context)
        else -> null
    }
}

private fun parseInsetClipPath(body: String, context: CssLengthContext): RichClipPath.Inset? {
    val parts = body.split(Regex("""\s+round\s+""", RegexOption.IGNORE_CASE), limit = 2)
    val insets = splitCssTopLevelWhitespace(parts.getOrNull(0).orEmpty())
        .mapNotNull { parseRichSize(it, context) }
    if (insets.isEmpty()) return null
    val top = insets.getOrNull(0) ?: RichSize.DpSize(0.dp)
    val right = insets.getOrNull(1) ?: top
    val bottom = insets.getOrNull(2) ?: top
    val left = insets.getOrNull(3) ?: right
    val radius = parts.getOrNull(1)
        ?.let(::splitCssTopLevelWhitespace)
        ?.firstOrNull()
        ?.let { parseCssDp(it, context) }
        ?: 0.dp
    return RichClipPath.Inset(top, right, bottom, left, radius)
}

private fun parseCircleClipPath(body: String, context: CssLengthContext): RichClipPath.Circle? {
    val parts = splitCssAtKeyword(body)
    val radius = parts.before
        .firstOrNull()
        ?.takeUnless { it in CSS_BASIC_SHAPE_SIZE_KEYWORDS }
        ?.let { parseRichSize(it, context) }
        ?: RichSize.Fraction(0.5f)
    val center = parseShapeCenter(parts.after, context)
    return RichClipPath.Circle(radius = radius, centerX = center.first, centerY = center.second)
}

private fun parseEllipseClipPath(body: String, context: CssLengthContext): RichClipPath.Ellipse? {
    val parts = splitCssAtKeyword(body)
    val radii = parts.before.filterNot { it in CSS_BASIC_SHAPE_SIZE_KEYWORDS }
    val radiusX = radii.getOrNull(0)?.let { parseRichSize(it, context) } ?: RichSize.Fraction(0.5f)
    val radiusY = radii.getOrNull(1)?.let { parseRichSize(it, context) } ?: RichSize.Fraction(0.5f)
    val center = parseShapeCenter(parts.after, context)
    return RichClipPath.Ellipse(radiusX = radiusX, radiusY = radiusY, centerX = center.first, centerY = center.second)
}

private data class CssBasicShapeParts(
    val before: List<String>,
    val after: List<String>,
)

private fun splitCssAtKeyword(body: String): CssBasicShapeParts {
    val tokens = splitCssTopLevelWhitespace(body.lowercase())
    val atIndex = tokens.indexOf("at")
    return if (atIndex >= 0) {
        CssBasicShapeParts(tokens.take(atIndex), tokens.drop(atIndex + 1))
    } else {
        CssBasicShapeParts(tokens, emptyList())
    }
}

private fun parseShapeCenter(tokens: List<String>, context: CssLengthContext): Pair<RichSize, RichSize> {
    if (tokens.isEmpty()) return RichSize.Fraction(0.5f) to RichSize.Fraction(0.5f)
    fun parseAxis(token: String, horizontal: Boolean): RichSize? = when (token) {
        "left" -> RichSize.Fraction(0f).takeIf { horizontal }
        "right" -> RichSize.Fraction(1f).takeIf { horizontal }
        "top" -> RichSize.Fraction(0f).takeIf { !horizontal }
        "bottom" -> RichSize.Fraction(1f).takeIf { !horizontal }
        "center" -> RichSize.Fraction(0.5f)
        else -> parseRichSize(token, context)
    }
    val xToken = tokens.firstOrNull { it !in setOf("top", "bottom") } ?: tokens.firstOrNull()
    val yToken = tokens.firstOrNull { it !in setOf("left", "right") && it != xToken } ?: tokens.getOrNull(1)
    val x = xToken?.let { parseAxis(it, horizontal = true) } ?: RichSize.Fraction(0.5f)
    val y = yToken?.let { parseAxis(it, horizontal = false) } ?: RichSize.Fraction(0.5f)
    return x to y
}

private fun parseMaskImage(value: String): RichBackgroundImage? {
    return when (val image = parseBackgroundImage(splitCssTopLevel(value, ',').firstOrNull().orEmpty())) {
        is RichBackgroundImage.LinearGradient -> image
        is RichBackgroundImage.RadialGradient -> image
        else -> null
    }
}

private fun parseBackgroundUrl(value: String): String? {
    return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
}

private fun parseSafeBackgroundUrl(value: String, kind: RichMediaKind): String? {
    return parseBackgroundUrl(value)?.let { safeRichMediaSource(it, kind) }
}

private fun safeRichMediaSource(source: String, kind: RichMediaKind): String? {
    val request = RichMediaRequest.fromSource(source, kind = kind)
    val safety = RichMediaLoader.safety(request)
    val accepted = safety == RichMediaSafety.Safe
    RichHtmlRenderTelemetry.recordMediaRequest(
        id = request.sourceDigest,
        kind = kind.name,
        safety = safety.name,
        outcome = if (accepted) "compile-accepted" else "compile-rejected",
        oversizedRejected = false,
    )
    return source.takeIf { accepted }
}

private fun recordCompileMediaSafetyReport(html: String) {
    val document = runCatching { RichHtmlAstCompiler.parseBody(html) }.getOrNull() ?: return
    document.select("img[src]").forEach { element ->
        safeRichMediaSource(element.attr("src"), RichMediaKind.Image)
    }
    document.select("[style]").forEach { element ->
        recordCompileStyleMediaSafety(element.attr("style"))
    }
    document.select("style").forEach { style ->
        CSS_RULE_BODY.findAll(style.data().ifBlank { style.html() }).forEach { match ->
            recordCompileStyleMediaSafety(match.groupValues.getOrNull(1).orEmpty())
        }
    }
}

private fun recordCompileStyleMediaSafety(declarations: String) {
    BACKGROUND_DECLARATION.findAll(declarations).forEach { match ->
        parseBackgroundUrl(match.value)?.let { source ->
            safeRichMediaSource(source, RichMediaKind.BackgroundImage)
        }
    }
    LIST_STYLE_IMAGE_DECLARATION.findAll(declarations).forEach { match ->
        parseBackgroundUrl(match.value)?.let { source ->
            safeRichMediaSource(source, RichMediaKind.ListStyleImage)
        }
    }
}

private val BACKGROUND_DECLARATION = Regex("""background(?:-image)?\s*:[^;]*""", RegexOption.IGNORE_CASE)
private val LIST_STYLE_IMAGE_DECLARATION = Regex("""list-style-image\s*:[^;]*""", RegexOption.IGNORE_CASE)
private val CSS_RULE_BODY = Regex("""\{([^{}]*)\}""")

private data class ParsedBackgroundShorthand(
    val color: Color? = null,
    val declaredColor: RichCssColor? = null,
    val image: RichBackgroundImage? = null,
    val url: String? = null,
    val size: RichBackgroundSize? = null,
    val position: RichBackgroundPosition? = null,
    val repeat: RichBackgroundRepeat? = null,
    val origin: RichBackgroundBox? = null,
    val clip: RichBackgroundBox? = null,
)

private fun parseBackgroundShorthand(value: String, context: CssLengthContext): ParsedBackgroundShorthand {
    val firstLayer = splitCssTopLevel(value, ',').firstOrNull().orEmpty()
    return parseBackgroundLayerShorthand(firstLayer, context)
}

private fun parseBackgroundLayerShorthand(value: String, context: CssLengthContext): ParsedBackgroundShorthand {
    val slashParts = splitCssTopLevel(value, '/')
    val beforeSlash = slashParts.getOrNull(0).orEmpty()
    val afterSlash = slashParts.getOrNull(1).orEmpty()
    val tokens = splitCssTopLevelWhitespace(beforeSlash)
    val afterSlashTokens = splitCssTopLevelWhitespace(afterSlash)
    val boxTokens = tokens.mapNotNull(::parseBackgroundBox)
    val afterSlashBoxTokens = afterSlashTokens.mapNotNull(::parseBackgroundBox)
    val sizeTokens = afterSlashTokens.filter { parseBackgroundBox(it) == null }
    val repeat = tokens.firstNotNullOfOrNull(::parseBackgroundRepeat)
    val positionTokens = tokens.filter { token ->
            parseBackgroundRepeat(token) == null &&
            parseBackgroundBox(token) == null &&
            parseRichCssColor(token) == null &&
            !token.contains("gradient", ignoreCase = true) &&
            !token.startsWith("url", ignoreCase = true)
    }
    val declaredColor = tokens
        .filterNot(::isBackgroundImageToken)
        .firstNotNullOfOrNull(::parseRichCssColor)
    return ParsedBackgroundShorthand(
        color = resolveRichCssColor(declaredColor, null),
        declaredColor = declaredColor,
        image = parseBackgroundImage(value),
        url = parseSafeBackgroundUrl(value, RichMediaKind.BackgroundImage),
        size = sizeTokens.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { parseBackgroundSize(it, context) },
        position = positionTokens.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { parseBackgroundPosition(it, context) },
        repeat = repeat,
        origin = afterSlashBoxTokens.getOrNull(0) ?: boxTokens.getOrNull(0),
        clip = afterSlashBoxTokens.getOrNull(1) ?: boxTokens.getOrNull(1) ?: afterSlashBoxTokens.getOrNull(0) ?: boxTokens.getOrNull(0),
    )
}

private fun parseSafeBackgroundLayers(
    imageValue: String?,
    sizeValue: String?,
    positionValue: String?,
    repeatValue: String?,
    originValue: String?,
    clipValue: String?,
    context: CssLengthContext,
    fallbackSize: RichBackgroundSize,
    fallbackPosition: RichBackgroundPosition,
    fallbackRepeat: RichBackgroundRepeat,
    fallbackOrigin: RichBackgroundBox,
    fallbackClip: RichBackgroundBox,
): List<RichBackgroundLayer>? {
    if (imageValue.isNullOrBlank()) return null
    val sourceLayers = splitCssTopLevel(imageValue, ',')
    if (sourceLayers.isEmpty()) return null
    val sizes = sizeValue?.let { splitCssTopLevel(it, ',') }.orEmpty()
    val positions = positionValue?.let { splitCssTopLevel(it, ',') }.orEmpty()
    val repeats = repeatValue?.let { splitCssTopLevel(it, ',') }.orEmpty()
    val origins = originValue?.let { splitCssTopLevel(it, ',') }.orEmpty()
    val clips = clipValue?.let { splitCssTopLevel(it, ',') }.orEmpty()
    val parsed = sourceLayers.mapIndexedNotNull { index, rawLayer ->
        val shorthand = parseBackgroundLayerShorthand(rawLayer, context)
        val image = parseBackgroundImage(rawLayer)
        val url = parseSafeBackgroundUrl(rawLayer, RichMediaKind.BackgroundImage)
        if (image == null && url == null) {
            null
        } else {
            RichBackgroundLayer(
                image = image,
                url = url,
                size = sizes.layerValue(index)?.let { parseBackgroundSize(it, context) }
                    ?: shorthand.size
                    ?: fallbackSize,
                position = positions.layerValue(index)?.let { parseBackgroundPosition(it, context) }
                    ?: shorthand.position
                    ?: fallbackPosition,
                repeat = repeats.layerValue(index)?.let(::parseBackgroundRepeat)
                    ?: shorthand.repeat
                    ?: fallbackRepeat,
                origin = origins.layerValue(index)?.let(::parseBackgroundBox)
                    ?: shorthand.origin
                    ?: fallbackOrigin,
                clip = clips.layerValue(index)?.let(::parseBackgroundBox)
                    ?: shorthand.clip
                    ?: fallbackClip,
            )
        }
    }
    return selectSafeBackgroundLayers(parsed).takeIf { it.isNotEmpty() }
}

private fun List<String>.layerValue(index: Int): String? = when {
    isEmpty() -> null
    index < size -> this[index]
    else -> last()
}

private fun selectSafeBackgroundLayers(layers: List<RichBackgroundLayer>): List<RichBackgroundLayer> {
    return layers
        .filter { layer -> layer.image != null || layer.url != null }
        .take(MAX_SAFE_BACKGROUND_LAYERS)
}

private fun isBackgroundImageToken(token: String): Boolean {
    return token.contains("gradient", ignoreCase = true) ||
        token.startsWith("url", ignoreCase = true)
}

private fun parseBackgroundSize(value: String, context: CssLengthContext): RichBackgroundSize? {
    val normalized = value.trim().lowercase()
    return when (normalized) {
        "cover" -> RichBackgroundSize.Cover
        "contain" -> RichBackgroundSize.Contain
        "auto" -> RichBackgroundSize.Auto
        else -> {
            val parts = splitCssTopLevelWhitespace(normalized)
            if (parts.isEmpty()) null else RichBackgroundSize.Explicit(
                width = parts.getOrNull(0)?.let { parseRichSize(it, context) } ?: RichSize.Auto,
                height = parts.getOrNull(1)?.let { parseRichSize(it, context) } ?: RichSize.Auto,
            )
        }
    }
}

private fun parseBackgroundPosition(value: String, context: CssLengthContext): RichBackgroundPosition? {
    val parts = splitCssTopLevelWhitespace(value.trim().lowercase())
    if (parts.isEmpty()) return null
    fun keywordFraction(part: String): Float? = when (part) {
        "left", "top" -> 0f
        "center" -> 0.5f
        "right", "bottom" -> 1f
        else -> null
    }
    fun percentFraction(part: String): Float? = if (part.endsWith("%")) {
        part.removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
    } else {
        null
    }
    data class AxisPosition(val fraction: Float = 0.5f, val offset: Dp = 0.dp)
    fun edgeOffset(edge: String, rawOffset: String?): AxisPosition {
        val edgeFraction = keywordFraction(edge) ?: 0.5f
        val parsedOffset = rawOffset?.let { parseCssDp(it, context) } ?: 0.dp
        val signedOffset = if (edge == "right" || edge == "bottom") -parsedOffset else parsedOffset
        return AxisPosition(edgeFraction, signedOffset)
    }

    if (parts.size >= 4) {
        var x = AxisPosition()
        var y = AxisPosition()
        var cursor = 0
        while (cursor < parts.size - 1) {
            val edge = parts[cursor]
            val offset = parts.getOrNull(cursor + 1)
            when (edge) {
                "left", "right" -> x = edgeOffset(edge, offset)
                "top", "bottom" -> y = edgeOffset(edge, offset)
            }
            cursor += 2
        }
        return RichBackgroundPosition(x.fraction, y.fraction, x.offset, y.offset)
    }

    if (parts.size == 2 && parts[0] in setOf("left", "right") && parts[1] !in setOf("top", "bottom", "center", "left", "right")) {
        val x = edgeOffset(parts[0], parts[1])
        return RichBackgroundPosition(x.fraction, 0.5f, x.offset, 0.dp)
    }
    if (parts.size == 2 && parts[0] in setOf("top", "bottom") && parts[1] !in setOf("left", "right", "center", "top", "bottom")) {
        val y = edgeOffset(parts[0], parts[1])
        return RichBackgroundPosition(0.5f, y.fraction, 0.dp, y.offset)
    }

    val x = parts.firstNotNullOfOrNull { part ->
        if (part in setOf("top", "bottom")) null else keywordFraction(part) ?: percentFraction(part)
    } ?: 0.5f
    val y = parts.firstNotNullOfOrNull { part ->
        if (part in setOf("left", "right")) null else keywordFraction(part) ?: percentFraction(part)
    } ?: if (parts.size == 1 && parts[0] in setOf("top", "bottom")) keywordFraction(parts[0]) ?: 0.5f else 0.5f
    return RichBackgroundPosition(x, y)
}

private fun parseBackgroundRepeat(value: String): RichBackgroundRepeat? = when (value.trim().lowercase()) {
    "repeat" -> RichBackgroundRepeat.Repeat
    "no-repeat" -> RichBackgroundRepeat.NoRepeat
    "repeat-x" -> RichBackgroundRepeat.RepeatX
    "repeat-y" -> RichBackgroundRepeat.RepeatY
    "round" -> RichBackgroundRepeat.Round
    "space" -> RichBackgroundRepeat.Space
    else -> null
}

private fun parseBackgroundBox(value: String): RichBackgroundBox? = when (value.trim().lowercase()) {
    "border-box" -> RichBackgroundBox.BorderBox
    "padding-box" -> RichBackgroundBox.PaddingBox
    "content-box" -> RichBackgroundBox.ContentBox
    "text" -> RichBackgroundBox.Text
    else -> null
}

private fun countExtraBackgroundLayers(value: String): Int {
    val layers = splitCssTopLevel(value, ',')
    return (layers.size - 1).coerceAtLeast(0)
}

private fun parseObjectFit(value: String): RichObjectFit? = when (value.trim().lowercase()) {
    "cover" -> RichObjectFit.Cover
    "contain", "scale-down" -> RichObjectFit.Contain
    "fill" -> RichObjectFit.Fill
    "none" -> RichObjectFit.None
    else -> null
}

private fun parseBackgroundImage(value: String): RichBackgroundImage? {
    val normalized = value.trim()
    val function = when {
        normalized.contains("linear-gradient", ignoreCase = true) -> "linear-gradient"
        normalized.contains("radial-gradient", ignoreCase = true) -> "radial-gradient"
        normalized.contains("conic-gradient", ignoreCase = true) -> "conic-gradient"
        else -> return null
    }
    val body = normalized.substringAfter("$function(", "").substringBeforeLast(")", "")
    val stops = parseGradientStops(body).take(MAX_SAFE_GRADIENT_STOPS)
    if (stops.size < 2) return null
    return when (function) {
        "linear-gradient" -> RichBackgroundImage.LinearGradient(parseGradientAngle(body), stops)
        "radial-gradient" -> RichBackgroundImage.RadialGradient(stops)
        else -> RichBackgroundImage.ConicGradient(stops)
    }
}

private fun parseListStyleType(value: String): RichListStyleType? {
    splitCssTopLevelWhitespace(value.trim().lowercase()).forEach { token ->
        when (token) {
            "disc" -> return RichListStyleType.Disc
            "circle" -> return RichListStyleType.Circle
            "square" -> return RichListStyleType.Square
            "decimal" -> return RichListStyleType.Decimal
            "lower-alpha", "lower-latin" -> return RichListStyleType.LowerAlpha
            "upper-alpha", "upper-latin" -> return RichListStyleType.UpperAlpha
            "none" -> return RichListStyleType.None
        }
    }
    return null
}

private fun parseListStylePosition(value: String): RichListStylePosition? {
    splitCssTopLevelWhitespace(value.trim().lowercase()).forEach { token ->
        when (token) {
            "inside" -> return RichListStylePosition.Inside
            "outside" -> return RichListStylePosition.Outside
        }
    }
    return null
}

private fun parseListStyleImage(value: String): String? {
    val normalized = value.trim()
    if (normalized.equals("none", ignoreCase = true)) return null
    return parseBackgroundUrl(normalized)
        ?.let { safeRichMediaSource(it, RichMediaKind.ListStyleImage) }
}

private fun parsePseudoContent(value: String, element: Element): String? {
    val trimmed = value.trim()
    if (trimmed.equals("none", ignoreCase = true) || trimmed.equals("normal", ignoreCase = true)) return null
    val tokens = Regex(
        """(['"])([\s\S]*?)\1|attr\(\s*([^)]+)\s*\)|counter\(\s*([^)]+)\s*\)|counters\(\s*([^,]+)\s*,\s*(['"])([\s\S]*?)\6\s*\)""",
        RegexOption.IGNORE_CASE,
    )
        .findAll(trimmed)
        .toList()
    if (tokens.isEmpty()) return null
    return tokens.joinToString(separator = "") { match ->
        val quoted = match.groupValues.getOrNull(2).orEmpty()
        val attrName = match.groupValues.getOrNull(3).orEmpty().trim().trim('"', '\'')
        val counterName = match.groupValues.getOrNull(4).orEmpty().trim()
        val countersName = match.groupValues.getOrNull(5).orEmpty().trim()
        val countersSeparator = match.groupValues.getOrNull(7).orEmpty()
        when {
            attrName.isNotBlank() -> element.attr(attrName)
            counterName.isNotBlank() -> counterPlaceholder(counterName)
            countersName.isNotBlank() -> countersPlaceholder(countersName, decodeCssContentString(countersSeparator))
            else -> decodeCssContentString(quoted)
        }
    }
}

private fun counterPlaceholder(name: String): String = "$COUNTER_PLACEHOLDER_START${name.trim()}$COUNTER_PLACEHOLDER_END"

private fun countersPlaceholder(name: String, separator: String): String {
    return "$COUNTERS_PLACEHOLDER_START${name.trim()}|$separator$COUNTER_PLACEHOLDER_END"
}

private fun ComputedStyle.resolveCounterPlaceholders(ordinal: Int, sectionOrdinal: Int): ComputedStyle {
    fun String.resolve(): String {
        val single = Regex("$COUNTER_PLACEHOLDER_START([^$COUNTER_PLACEHOLDER_END]+)$COUNTER_PLACEHOLDER_END")
            .replace(this) { match ->
                when (match.groupValues.getOrNull(1)?.trim()?.lowercase()) {
                    "section" -> sectionOrdinal.toString()
                    else -> ordinal.toString()
                }
            }
        return Regex("$COUNTERS_PLACEHOLDER_START([^|$COUNTER_PLACEHOLDER_END]+)(?:\\|([^$COUNTER_PLACEHOLDER_END]*))?$COUNTER_PLACEHOLDER_END")
            .replace(single) { match ->
                when (match.groupValues.getOrNull(1)?.trim()?.lowercase()) {
                    "section" -> sectionOrdinal.toString()
                    else -> ordinal.toString()
                }
            }
    }
    return copy(
        beforeContent = beforeContent?.resolve(),
        afterContent = afterContent?.resolve(),
    )
}

private fun decodeCssContentString(value: String): String {
    val unicodeDecoded = Regex("""\\([0-9a-fA-F]{1,6})\s?""").replace(value) { match ->
        val codePoint = match.groupValues[1].toIntOrNull(radix = 16)
        codePoint?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: match.value
    }
    return unicodeDecoded
        .replace("\\A", "\n")
        .replace("\\a", "\n")
        .replace("\\\"", "\"")
        .replace("\\'", "'")
        .replace("\\\\", "\\")
}

private fun parseOrderedListTypeAttribute(value: String): RichListStyleType? = when (value.trim()) {
    "1" -> RichListStyleType.Decimal
    "a" -> RichListStyleType.LowerAlpha
    "A" -> RichListStyleType.UpperAlpha
    else -> null
}

private fun listMarker(ordinal: Int, ordered: Boolean, styleType: RichListStyleType): String {
    val effective = if (styleType == RichListStyleType.Default) {
        if (ordered) RichListStyleType.Decimal else RichListStyleType.Disc
    } else {
        styleType
    }
    val markerIndex = (ordinal - 1).coerceAtLeast(0)
    return when (effective) {
        RichListStyleType.None -> ""
        RichListStyleType.Disc -> "• "
        RichListStyleType.Circle -> "◦ "
        RichListStyleType.Square -> "▪ "
        RichListStyleType.Decimal -> "$ordinal. "
        RichListStyleType.LowerAlpha -> "${alphaMarker(markerIndex, uppercase = false)}. "
        RichListStyleType.UpperAlpha -> "${alphaMarker(markerIndex, uppercase = true)}. "
        RichListStyleType.Default -> if (ordered) "$ordinal. " else "• "
    }
}

private fun alphaMarker(index: Int, uppercase: Boolean): String {
    var value = index + 1
    val builder = StringBuilder()
    while (value > 0) {
        value -= 1
        builder.insert(0, ('a'.code + value % 26).toChar())
        value /= 26
    }
    val result = builder.toString()
    return if (uppercase) result.uppercase() else result
}

private fun parseGradientStops(body: String): List<RichColorStop> {
    return splitCssTopLevel(body, ',').mapNotNull { rawStop ->
        val colorMatch = CSS_COLOR_TOKEN.find(rawStop) ?: return@mapNotNull null
        val color = parseCssColor(colorMatch.value) ?: return@mapNotNull null
        val afterColor = rawStop.substring(colorMatch.range.last + 1)
        RichColorStop(color = color, offset = parseGradientStopOffset(afterColor))
    }
}

private fun parseGradientStopOffset(value: String): Float? {
    val token = Regex("""-?[0-9]*\.?[0-9]+%?""").find(value)?.value ?: return null
    return if (token.endsWith("%")) {
        token.removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
    } else {
        token.toFloatOrNull()?.takeIf { it in 0f..1f }
    }
}

private fun parseGradientAngle(body: String): Float {
    val first = splitCssTopLevel(body, ',').firstOrNull().orEmpty().trim().lowercase()
    return when {
        first.endsWith("deg") -> first.removeSuffix("deg").toFloatOrNull() ?: 180f
        first.contains("to right") -> 0f
        first.contains("to bottom") -> 90f
        first.contains("to left") -> 180f
        first.contains("to top") -> 270f
        else -> 180f
    }
}

private fun parseCssDp(value: String): Dp? = parseCssDp(value, CssLengthContext(360.dp))

private fun parseCssDp(value: String, context: CssLengthContext): Dp? {
    val normalized = value.trim().lowercase()
    if (normalized.isEmpty() || normalized == "auto") return null
    parseCssLengthFunction(normalized, context)?.let { return it }
    return when {
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.dp
        normalized.endsWith("dp") -> normalized.removeSuffix("dp").trim().toFloatOrNull()?.dp
        normalized.endsWith("vw") -> normalized.removeSuffix("vw").trim().toFloatOrNull()?.let { context.viewportWidth * (it / 100f) }
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let { context.rootFontSizeDp() * it }
        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.let { context.fontSizeDp() * it }
        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let { context.viewportWidth * (it / 100f) }
        else -> normalized.toFloatOrNull()?.dp
    }
}

private fun parseCssLengthFunction(value: String, context: CssLengthContext): Dp? {
    return CssLengthExpressionParser(value, context).parse()
}

private class CssLengthExpressionParser(
    private val source: String,
    private val context: CssLengthContext,
) {
    private var cursor = 0

    fun parse(): Dp? {
        val result = parseExpression() ?: return null
        skipWhitespace()
        return result.takeIf { cursor == source.length }
    }

    private fun parseExpression(): Dp? {
        var total = parseTerm() ?: return null
        while (true) {
            skipWhitespace()
            val operator = source.getOrNull(cursor)?.takeIf { it == '+' || it == '-' } ?: return total
            cursor += 1
            val rhs = parseTerm() ?: return null
            total = if (operator == '+') total + rhs else total - rhs
        }
    }

    private fun parseTerm(): Dp? {
        skipWhitespace()
        var sign = 1f
        when (source.getOrNull(cursor)) {
            '+' -> cursor += 1
            '-' -> {
                cursor += 1
                sign = -1f
            }
        }
        skipWhitespace()
        val result = when {
            source.getOrNull(cursor) == '(' -> {
                cursor += 1
                val nested = parseExpression()
                skipWhitespace()
                if (source.getOrNull(cursor) == ')') cursor += 1 else return null
                nested
            }
            source.getOrNull(cursor)?.isLetter() == true -> parseFunction()
            else -> parseLengthToken()
        } ?: return null
        return result * sign
    }

    private fun parseFunction(): Dp? {
        val nameStart = cursor
        while (source.getOrNull(cursor)?.isLetter() == true || source.getOrNull(cursor) == '-') cursor += 1
        val name = source.substring(nameStart, cursor).lowercase()
        skipWhitespace()
        if (source.getOrNull(cursor) != '(') return null
        val open = cursor
        val close = findMatchingParen(source, open) ?: return null
        cursor = close + 1
        val body = source.substring(open + 1, close)
        return when (name) {
            "calc" -> CssLengthExpressionParser(body, context).parse()
            "min" -> splitCssTopLevel(body, ',').mapNotNull { CssLengthExpressionParser(it, context).parse() }.minByOrNull { it.value }
            "max" -> splitCssTopLevel(body, ',').mapNotNull { CssLengthExpressionParser(it, context).parse() }.maxByOrNull { it.value }
            "clamp" -> {
                val args = splitCssTopLevel(body, ',').mapNotNull { CssLengthExpressionParser(it, context).parse() }
                if (args.size == 3) args[1].coerceIn(args[0], args[2]) else null
            }
            else -> null
        }
    }

    private fun parseLengthToken(): Dp? {
        val start = cursor
        while (cursor < source.length) {
            val char = source[cursor]
            if (char.isLetterOrDigit() || char == '.' || char == '%') {
                cursor += 1
            } else {
                break
            }
        }
        if (start == cursor) return null
        return parseSimpleCssDp(source.substring(start, cursor), context)
    }

    private fun skipWhitespace() {
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor += 1
    }
}

private fun parseSimpleCssDp(value: String, context: CssLengthContext): Dp? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.dp
        normalized.endsWith("dp") -> normalized.removeSuffix("dp").trim().toFloatOrNull()?.dp
        normalized.endsWith("vw") -> normalized.removeSuffix("vw").trim().toFloatOrNull()?.let { context.viewportWidth * (it / 100f) }
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let { context.rootFontSizeDp() * it }
        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.let { context.fontSizeDp() * it }
        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let { context.viewportWidth * (it / 100f) }
        else -> normalized.toFloatOrNull()?.dp
    }
}

private fun findMatchingParen(text: String, openIndex: Int): Int? {
    var depth = 0
    var quote: Char? = null
    for (index in openIndex until text.length) {
        val char = text[index]
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '(' -> depth += 1
            quote == null && char == ')' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }
    return null
}

private fun parseCssFontSize(
    value: String,
    baseFontSize: TextUnit = 16.sp,
    context: CssLengthContext? = null,
): TextUnit? {
    val normalized = value.trim().lowercase()
    if (context != null) {
        parseCssLengthFunction(normalized, context)?.let { return it.value.sp.takeIfFiniteCssTextUnit() }
    }
    val base = baseFontSize.cssReferenceFontSize()
    return when {
        normalized.endsWith("sp") -> normalized.removeSuffix("sp").trim().toFloatOrNull()?.sp
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.sp
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let { (16f * it).sp }
        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.let { (base.value * it).sp }
        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let { (base.value * it / 100f).sp }
        normalized == "xx-small" -> (base.value * 0.6f).sp
        normalized == "x-small" -> (base.value * 0.75f).sp
        normalized == "small" -> (base.value * 0.89f).sp
        normalized == "medium" -> base
        normalized == "large" -> (base.value * 1.2f).sp
        normalized == "x-large" -> (base.value * 1.5f).sp
        normalized == "xx-large" -> (base.value * 2f).sp
        normalized == "smaller" -> (base.value * 0.833f).sp
        normalized == "larger" -> (base.value * 1.2f).sp
        else -> normalized.toFloatOrNull()?.sp
    }?.takeIfFiniteCssTextUnit()
}

private data class CssLineHeightValue(
    val value: TextUnit,
    val multiplier: Float? = null,
)

private fun parseCssLineHeightValue(value: String, baseFontSize: TextUnit): CssLineHeightValue? {
    val normalized = value.trim().lowercase()
    val base = baseFontSize.cssReferenceFontSize()
    if (normalized == "normal") {
        return (base.value * DEFAULT_CSS_NORMAL_LINE_HEIGHT).sp
            .takeIfFiniteCssTextUnit()
            ?.let { CssLineHeightValue(it, DEFAULT_CSS_NORMAL_LINE_HEIGHT) }
    }
    if (normalized.matches(Regex("[0-9]*\\.?[0-9]+"))) {
        return normalized.toFloatOrNull()
            ?.let { multiplier -> CssLineHeightValue((base.value * multiplier).sp, multiplier) }
            ?.takeIf { it.value.isSpecified && it.value.value.isFinite() && it.value.value > 0f }
    }
    return parseCssFontSize(normalized, baseFontSize)
        ?.let { CssLineHeightValue(it) }
}

private fun parseTextTransform(value: String): RichTextTransform? = when (value.trim().lowercase()) {
    "uppercase" -> RichTextTransform.Uppercase
    "lowercase" -> RichTextTransform.Lowercase
    "capitalize" -> RichTextTransform.Capitalize
    "none", "initial", "unset" -> RichTextTransform.None
    else -> null
}

private fun parseVerticalAlign(value: String): RichVerticalAlign? = when (value.trim().lowercase()) {
    "baseline", "initial", "unset" -> RichVerticalAlign.Baseline
    "middle" -> RichVerticalAlign.Middle
    "top", "text-top" -> RichVerticalAlign.Top
    "bottom", "text-bottom" -> RichVerticalAlign.Bottom
    "sub" -> RichVerticalAlign.Sub
    "super" -> RichVerticalAlign.Super
    else -> null
}

private fun parseFontVariantNumeric(value: String): String? {
    val normalized = splitCssTopLevelWhitespace(value.trim().lowercase())
        .filter { token ->
            token in setOf(
                "normal",
                "ordinal",
                "slashed-zero",
                "lining-nums",
                "oldstyle-nums",
                "proportional-nums",
                "tabular-nums",
                "diagonal-fractions",
                "stacked-fractions",
            )
        }
        .joinToString(" ")
    return normalized.takeIf { it.isNotBlank() }
}

private fun String.applyTextTransform(transform: RichTextTransform): String {
    return when (transform) {
        RichTextTransform.None -> this
        RichTextTransform.Uppercase -> uppercase()
        RichTextTransform.Lowercase -> lowercase()
        RichTextTransform.Capitalize -> replace(Regex("""(^|[\s\p{Punct}])(\p{L})""")) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
    }
}

private data class CssFontShorthand(
    val fontSize: TextUnit,
    val lineHeight: CssLineHeightValue? = null,
    val fontWeight: FontWeight? = null,
    val fontStyle: FontStyle? = null,
    val fontFamily: FontFamily? = null,
)

private fun parseCssFontShorthand(value: String, inheritedFontSize: TextUnit): CssFontShorthand? {
    val normalized = value.trim()
    if (normalized.isBlank()) return null
    if (normalized.equals("inherit", ignoreCase = true) ||
        normalized.equals("initial", ignoreCase = true) ||
        normalized.equals("unset", ignoreCase = true)
    ) {
        return null
    }
    val tokens = splitCssTopLevelWhitespace(normalized)
    if (tokens.isEmpty()) return null

    val sizeMatch = CSS_FONT_SIZE_IN_SHORTHAND.find(normalized) ?: return null
    val parsedSize = parseCssFontSize(sizeMatch.groupValues[2], inheritedFontSize) ?: return null
    val lineHeight = sizeMatch.groupValues.getOrNull(3)
        ?.takeIf { it.isNotBlank() }
        ?.let { parseCssLineHeightValue(it, parsedSize) }
    val leadingTokens = splitCssTopLevelWhitespace(normalized.substring(0, sizeMatch.range.first))
    var fontStyle: FontStyle? = null
    var fontWeight: FontWeight? = null
    leadingTokens.forEach { token ->
        fontStyle = parseCssFontStyle(token) ?: fontStyle
        fontWeight = parseCssFontWeight(token) ?: fontWeight
    }

    val family = normalized.substring(sizeMatch.range.last + 1)
        .trim()
        .takeIf { it.isNotBlank() }
        ?.let(::parseCssFontFamily)

    return CssFontShorthand(
        fontSize = parsedSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = family,
    )
}

private val CSS_FONT_SIZE_IN_SHORTHAND = Regex(
    """(?i)(^|\s)(xx-small|x-small|small|medium|large|x-large|xx-large|smaller|larger|[+-]?(?:\d+\.?\d*|\.\d+)(?:px|sp|rem|em|%))(?:\s*/\s*(normal|[+-]?(?:\d+\.?\d*|\.\d+)(?:px|sp|rem|em|%)?))?(?=\s|$)"""
)

private fun parseCssFloat(value: String): Float? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.startsWith(".") -> "0$normalized".toFloatOrNull()
        normalized.endsWith("%") -> normalized.removeSuffix("%").toFloatOrNull()?.div(100f)
        else -> normalized.toFloatOrNull()
    }
}

private fun parseCssLengthFloat(value: String): Float? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()
        normalized.endsWith("dp") -> normalized.removeSuffix("dp").trim().toFloatOrNull()
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.times(16f)
        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.times(16f)
        else -> normalized.toFloatOrNull()
    }
}

private fun parseCssFontWeight(value: String): FontWeight? = when (value.trim().lowercase()) {
    "normal" -> FontWeight.Normal
    "bold", "bolder" -> FontWeight.SemiBold
    "lighter" -> FontWeight.Light
    "100" -> FontWeight.W100
    "200" -> FontWeight.W200
    "300" -> FontWeight.W300
    "400" -> FontWeight.W400
    "500" -> FontWeight.W500
    "600" -> FontWeight.W600
    "700" -> FontWeight.Bold
    "800" -> FontWeight.ExtraBold
    "900" -> FontWeight.Black
    else -> null
}

private fun parseCssFontStyle(value: String): FontStyle? = when (value.trim().lowercase()) {
    "italic", "oblique" -> FontStyle.Italic
    "normal" -> FontStyle.Normal
    else -> null
}

private fun parseCssFontFamily(value: String): FontFamily? {
    return splitCssTopLevel(value, ',').firstNotNullOfOrNull { rawFamily ->
        val family = rawFamily.trim().trim('"', '\'').lowercase()
        when {
            family.contains("mono") ||
                family.contains("courier") ||
                family.contains("consolas") ||
                family.contains("fira code") ||
                family.contains("jetbrains mono") -> FontFamily.Monospace

            family == "serif" ||
                family.contains("times") ||
                family.contains("georgia") ||
                family.contains("garamond") -> FontFamily.Serif

            family.contains("cursive") -> FontFamily.Cursive

            family.contains("sans") ||
                family.contains("arial") ||
                family.contains("helvetica") ||
                family.contains("system") ||
                family.contains("roboto") ||
                family.contains("segoe") -> FontFamily.SansSerif

            else -> null
        }
    }
}

private fun parseCssTextAlign(value: String): TextAlign? = when (value.trim().lowercase()) {
    "left", "start" -> TextAlign.Start
    "right", "end" -> TextAlign.End
    "center" -> TextAlign.Center
    "justify" -> TextAlign.Justify
    else -> null
}

private val CSS_LENGTH_TOKEN = Regex("""-?[0-9]*\.?[0-9]+(?:px|dp|rem|em)?""")
private val CSS_FUNCTION_TOKEN = Regex("""([a-zA-Z-]+)\(([^()]*)\)""")
private val CSS_PROPERTY_NAME = Regex("""([A-Za-z-]+)\s*:""")
private val CSS_BASIC_SHAPE_SIZE_KEYWORDS = setOf("closest-side", "farthest-side", "closest-corner", "farthest-corner")
private val INTERACTIVE_PSEUDO_SELECTOR = Regex(""":(hover|active|focus|focus-visible|focus-within)\b""", RegexOption.IGNORE_CASE)
private val ANIMATED_LAYOUT_PROPERTIES = setOf(
    "width",
    "height",
    "min-width",
    "max-width",
    "min-height",
    "max-height",
    "margin",
    "margin-top",
    "margin-right",
    "margin-bottom",
    "margin-left",
    "padding",
    "padding-top",
    "padding-right",
    "padding-bottom",
    "padding-left",
    "top",
    "right",
    "bottom",
    "left",
    "gap",
    "row-gap",
    "column-gap",
    "grid-template-columns",
    "grid-template-rows",
    "flex-basis",
)
private val NATIVE_ANIMATION_PROPERTIES = setOf("opacity", "transform")
private const val MAX_NATIVE_ANIMATION_DURATION_MS = 1_200
private const val MAX_NATIVE_ANIMATION_TOTAL_MS = 1_800
private const val MAX_NATIVE_ANIMATION_DELAY_MS = 1_500
private const val MAX_NATIVE_ANIMATION_ITERATIONS = 3
private const val MAX_SAFE_BACKGROUND_LAYERS = 4
private const val MAX_SAFE_GRADIENT_STOPS = 16
private val ANIMATION_SHORTHAND_KEYWORDS = setOf(
    "none",
    "linear",
    "ease",
    "ease-in",
    "ease-out",
    "ease-in-out",
    "step-start",
    "step-end",
    "forwards",
    "backwards",
    "both",
    "normal",
    "reverse",
    "alternate",
    "alternate-reverse",
    "running",
    "paused",
    "infinite",
)
private val TRANSITION_SHORTHAND_KEYWORDS = setOf(
    "none",
    "all",
    "linear",
    "ease",
    "ease-in",
    "ease-out",
    "ease-in-out",
    "step-start",
    "step-end",
    "allow-discrete",
    "normal",
)
private val DEFAULT_CSS_FONT_SIZE = 16.sp
private const val DEFAULT_CSS_NORMAL_LINE_HEIGHT = 1.2f
private const val COUNTER_PLACEHOLDER_START = "\uE100"
private const val COUNTERS_PLACEHOLDER_START = "\uE101"
private const val COUNTER_PLACEHOLDER_END = "\uE102"
private const val RICH_HTML_COMPILE_TIMEOUT_MS = 2_500L
