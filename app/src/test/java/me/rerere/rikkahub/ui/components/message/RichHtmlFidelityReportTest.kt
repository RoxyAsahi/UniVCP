package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.ui.components.richtext.RichContainerBlock
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RichHtmlFidelityReportTest {
    @Test
    fun `render seed fidelity report is metadata only and stable`() {
        val reports = loadRenderSeedAssistantTexts()
            .flatMap { text -> parseMessageTextBlocks(text, streaming = false).filterIsInstance<MessageTextBlock.VcpHtml>() }
            .mapIndexed { index, block -> buildReport(index, block.html) }

        assertTrue("Expected representative rich html samples", reports.size >= 7)
        assertTrue("Expected at least one CSS property in report", reports.sumOf { it.cssProperties.values.sum() } > 0)
        assertTrue("Expected at least one tag in report", reports.sumOf { it.tags.values.sum() } > 0)
        assertTrue("Expected compiled blocks", reports.sumOf { it.blockCount } > 0)
        assertTrue("Expected plan source nodes", reports.sumOf { it.planSourceNodeCount } > 0)
        assertTrue("Expected plan route field", reports.any { it.planRoute.isNotBlank() })
        assertTrue("Expected AST nodes", reports.sumOf { it.astNodeCount } > 0)

        val serialized = reports.joinToString("\n") { it.toMetadataLine() }
        assertTrue("Report should include plan route", serialized.contains("planRoute="))
        assertTrue("Report should include plan estimated render blocks", serialized.contains("planEstimatedRenderBlocks="))
        assertTrue("Report should include AST nodes", serialized.contains("astNodes="))
        assertTrue("Report should include canonical document nodes", serialized.contains("documentNodes="))
        assertTrue("Report should include AST route", serialized.contains("documentRoute="))
        assertTrue("Report should include transform pipeline version", serialized.contains("transformPipeline="))
        assertTrue("Report should include transform route mismatch", serialized.contains("transformRouteMismatch="))
        assertTrue("Report should include route closure", serialized.contains("routeMismatch="))
        assertTrue("Report should include decision source", serialized.contains("actualDecisionSource="))
        assertTrue("Report should include TextFlow blocked reason count", serialized.contains("textFlowBlockedReasonCount="))
        assertTrue("Report should include sanitizer summary", serialized.contains("sanitizerRemovedTags="))
        assertTrue("Report should include compile phase summary", serialized.contains("compilePhaseSamples="))
        assertTrue("Report should include CSS cascade summary", serialized.contains("cssRuleCount="))
        assertTrue("Report should include SVG route summary", serialized.contains("svgRouteCount="))
        assertTrue("Report should include media source summary", serialized.contains("mediaSourceCount="))
        loadRenderSeedAssistantTexts().forEach { sourceText ->
            sourceText.lineSequence()
                .map { it.trim() }
                .filter { it.length > 24 }
                .take(3)
                .forEach { line ->
                    assertFalse("Report must not contain source body text", serialized.contains(line))
                }
        }
    }

    @Test
    fun `visual hints cover css svg table and background gaps`() {
        val html = """
            <div id="vcp-root" style="background:url(data:image/png;base64,AAAA) no-repeat right 8px bottom 4px / 20px 20px content-box padding-box,linear-gradient(#000,#fff);filter:blur(2px);backdrop-filter:brightness(.9);mix-blend-mode:multiply;clip-path:inset(0);mask-image:url(data:image/png;base64,BBBB);">
              <style>@keyframes fade{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}.x:hover{opacity:.8}</style>
              <div class="x" style="animation:fade .4s ease-out forwards;transition:transform .2s ease;">Animated</div>
              <table><tr><td rowspan="2" colspan="2">A</td></tr></table>
              <svg width="40" height="40" viewBox="0 0 40 40">
                <defs><clipPath id="c"><rect width="10" height="10"/></clipPath><marker id="m"/></defs>
                <use href="#x"/><path d="M0 0 L20 20" clip-path="url(#c)" marker-end="url(#m)"/>
              </svg>
            </div>
        """.trimIndent()

        val model = RichHtmlCompiler.compile(html)
        val hints = model.visualHints.map { it.name }.toSet()
        val root = model.blocks.single() as RichContainerBlock

        assertTrue("Background shorthand should parse URL", root.style.backgroundUrl?.startsWith("data:image/png") == true)
        assertTrue("Background shorthand should parse size", root.style.backgroundSize.toString().contains("Explicit"))
        assertTrue("CSS filter should be retained as structured model", root.style.cssFilter.blurRadius != null)
        assertTrue("Backdrop filter should be retained as structured model", root.style.backdropFilter.brightness != null)
        assertTrue("Background shorthand should parse extra layers", hints.contains("BackgroundExtraLayer"))
        assertTrue(hints.contains("CssFilter"))
        assertTrue(hints.contains("CssBackdropFilter"))
        assertTrue(hints.contains("CssMixBlendMode"))
        assertTrue(hints.contains("CssMask"))
        assertTrue(hints.contains("CssClipPath"))
        assertTrue(hints.contains("CssAnimation"))
        assertTrue(hints.contains("CssTransition"))
        assertTrue(hints.contains("CssKeyframes"))
        assertTrue(hints.contains("CssInteractivePseudoClass"))
        assertTrue(hints.contains("TableComplexSpan"))
        assertTrue(hints.contains("SvgClipPath"))
        assertTrue(hints.contains("SvgUse"))
        assertTrue(hints.contains("SvgMarker"))
    }

    private fun buildReport(index: Int, html: String): FidelityReport {
        RichHtmlRenderTelemetry.resetForTest()
        val start = System.nanoTime()
        val analysis = analyzeRichHtml(html)
        val risk = RenderRiskScore.fromHtml(html, analysis)
        val model = RichHtmlCompiler.compile(html)
        val sanitizer = RichHtmlSanitizer.inspect(html)
        val compilePhaseSummary = RichHtmlRenderTelemetry.compilePhaseSummary()
        val cssSummary = RichHtmlRenderTelemetry.cssCascadeSummary()
        val svgRouteSummary = RichHtmlRenderTelemetry.svgRouteSummary()
        val plan = buildRichRenderPlan(
            html = html,
            analysis = analysis,
            risk = risk,
            model = model,
        )
        val actualDecision = RichHtmlSnapshotPolicy.afterCompile(analysis, model)
        val actualRoute = actualDecision.route.toPlanRouteName()
        val routeClosure = buildRichRouteClosureReport(
            plannedRoute = plan.route.name,
            actualRoute = actualRoute,
            actualDecisionSource = RichActualDecisionSource.SnapshotPolicy,
        )
        RichHtmlRenderTelemetry.recordRouteClosure(plan.id, routeClosure)
        val compileMs = (System.nanoTime() - start) / 1_000_000
        val document = Jsoup.parseBodyFragment(html)
        val tags = document.body().select("*")
            .map { it.tagName().lowercase() }
            .groupingBy { it }
            .eachCount()
        val cssProperties = document.select("[style]")
            .flatMap { element -> element.attr("style").split(";") }
            .mapNotNull { declaration -> declaration.substringBefore(":", "").trim().lowercase().takeIf { it.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        val svgFeatures = document.select("svg *")
            .map { it.tagName().lowercase() }
            .groupingBy { "svg:$it" }
            .eachCount()
        val mediaSourceCount = document.select("img[src], [style]").count { element ->
            element.tagName().equals("img", ignoreCase = true) ||
                element.attr("style").contains("url(", ignoreCase = true)
        }
        return FidelityReport(
            sampleId = "seed-$index:${model.id}",
            tags = tags,
            cssProperties = cssProperties,
            svgFeatures = svgFeatures,
            visualHints = model.visualHints.groupingBy { it.name }.eachCount(),
            unsupported = model.unsupported.groupingBy { it.name }.eachCount(),
            animationStrategy = model.animationStats.strategy.name,
            animationCounts = mapOf(
                "animated" to model.animationStats.animatedElementCount,
                "native" to model.animationStats.nativeAnimatedCount,
                "staticized" to model.animationStats.staticizedCount,
                "infinite" to model.animationStats.infiniteCount,
                "layout" to model.animationStats.layoutAnimationCount,
                "transition" to model.animationStats.transitionCount,
                "dependentVisibility" to model.animationStats.dependentVisibilityCount,
                "budgetExceeded" to model.animationStats.budgetExceededCount,
                "playOnceSuppressed" to model.animationStats.playOnceSuppressedCount,
                "snapshotCandidate" to model.animationStats.snapshotCandidateCount,
                "multiKeyframe" to model.animationStats.multiKeyframeCount,
                "unsupportedProperty" to model.animationStats.unsupportedPropertyCount,
            ).filterValues { it > 0 },
            compileMs = compileMs,
            blockCount = model.blocks.sumOf(::countRichRenderBlocks),
            planRoute = plan.route.name,
            actualRoute = routeClosure.actualRoute,
            routeMismatchReason = routeClosure.mismatchReason.name,
            actualDecisionSource = routeClosure.actualDecisionSource.name,
            planConfidence = plan.nativeConfidence.name,
            planReason = plan.reason,
            planSourceNodeCount = plan.sourceNodeCount,
            planEstimatedRenderBlockCount = plan.estimatedRenderBlockCount,
            planTextFlowCandidateCount = plan.textFlowCandidateCount,
            planSnapshotIslandCandidateCount = plan.snapshotIslandCandidateCount,
            planInteractiveActionCount = plan.interactiveActionCount,
            planVisualHintCount = plan.visualHints.size,
            planUnsupportedCount = plan.unsupported.size,
            astNodeCount = plan.astStats?.astNodeCount ?: 0,
            astTextRunCount = plan.astStats?.textRunCount ?: 0,
            astParagraphCount = plan.astStats?.paragraphCount ?: 0,
            astTextFlowCandidateCount = plan.astStats?.textFlowCandidateCount ?: 0,
            astBlockedTextFlowCount = plan.astStats?.blockedTextFlowCount ?: 0,
            documentNodeCount = plan.documentStats?.canonicalNodeCount ?: 0,
            documentTextFlowEligibleCount = plan.documentStats?.textFlowEligibleSubtreeCount ?: 0,
            documentSnapshotEligibleCount = plan.documentStats?.snapshotIslandEligibleSubtreeCount ?: 0,
            documentInlineRequiredCount = plan.documentStats?.inlineWebViewRequiredCount ?: 0,
            documentRoute = plan.documentRoute?.name.orEmpty(),
            documentRouteReason = plan.documentRouteReason,
            transformPipelineVersion = plan.transformReport?.pipelineVersion ?: 0,
            transformPassCount = plan.transformReport?.passOrder?.size ?: 0,
            transformRouteMismatchReason = plan.transformReport?.routeMismatchReason?.name.orEmpty(),
            transformImportConversionLossCount = plan.transformReport?.importConversionLossCount ?: 0,
            transformWarningCount = plan.transformReport?.normalizationWarnings?.values?.sum() ?: 0,
            textFlowAppliedCount = plan.textFlowAppliedCount,
            renderNodeReductionEstimate = plan.renderNodeReductionEstimate,
            textFlowBlockedReasonCount = plan.textFlowBlockedReasons.size,
            sanitizerRemovedTagCount = sanitizer.removedTagCount,
            sanitizerRemovedAttributeCount = sanitizer.removedAttributeCount,
            sanitizerDangerousProtocolCount = sanitizer.dangerousProtocolCount,
            sanitizerEventHandlerCount = sanitizer.eventHandlerCount,
            sanitizerRuntimeReason = sanitizer.runtimeReason.orEmpty(),
            sanitizerExistingSafetyAgreed = sanitizer.existingSafetyAgreed,
            compilePhaseSampleCount = compilePhaseSummary.sampleCount,
            compilePhaseMaxTotalMs = compilePhaseSummary.maxTotalMs,
            cssRuleCount = cssSummary.totalRules,
            cssUnsupportedSelectorCount = cssSummary.unsupportedSelectors,
            cssParserFallbackCount = cssSummary.parserFallbacks,
            cssIndexMismatchCount = cssSummary.indexMismatches,
            svgRouteCount = svgRouteSummary.sampleCount,
            svgRoutes = svgRouteSummary.routes,
            mediaSourceCount = mediaSourceCount,
        )
    }

    private fun loadRenderSeedAssistantTexts(): List<String> {
        val seedFile = sequenceOf(
            File("src/debug/assets/render_seed/chat_render_seed.json"),
            File("app/src/debug/assets/render_seed/chat_render_seed.json"),
        ).first { it.exists() }
        val root = Json.parseToJsonElement(seedFile.readText()).jsonObject
        return root.getValue("conversations").jsonArray.flatMap { conversation ->
            conversation.jsonObject.getValue("messages").jsonArray
                .filter { message -> message.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
                .flatMap { message ->
                    message.jsonObject.getValue("parts").jsonArray
                        .filter { part -> part.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                        .map { part -> part.jsonObject.getValue("text").jsonPrimitive.content }
                }
        }
    }
}

private data class FidelityReport(
    val sampleId: String,
    val tags: Map<String, Int>,
    val cssProperties: Map<String, Int>,
    val svgFeatures: Map<String, Int>,
    val visualHints: Map<String, Int>,
    val unsupported: Map<String, Int>,
    val animationStrategy: String,
    val animationCounts: Map<String, Int>,
    val compileMs: Long,
    val blockCount: Int,
    val planRoute: String,
    val actualRoute: String,
    val routeMismatchReason: String,
    val actualDecisionSource: String,
    val planConfidence: String,
    val planReason: String,
    val planSourceNodeCount: Int,
    val planEstimatedRenderBlockCount: Int,
    val planTextFlowCandidateCount: Int,
    val planSnapshotIslandCandidateCount: Int,
    val planInteractiveActionCount: Int,
    val planVisualHintCount: Int,
    val planUnsupportedCount: Int,
    val astNodeCount: Int,
    val astTextRunCount: Int,
    val astParagraphCount: Int,
    val astTextFlowCandidateCount: Int,
    val astBlockedTextFlowCount: Int,
    val documentNodeCount: Int,
    val documentTextFlowEligibleCount: Int,
    val documentSnapshotEligibleCount: Int,
    val documentInlineRequiredCount: Int,
    val documentRoute: String,
    val documentRouteReason: String,
    val transformPipelineVersion: Int,
    val transformPassCount: Int,
    val transformRouteMismatchReason: String,
    val transformImportConversionLossCount: Int,
    val transformWarningCount: Int,
    val textFlowAppliedCount: Int,
    val renderNodeReductionEstimate: Int,
    val textFlowBlockedReasonCount: Int,
    val sanitizerRemovedTagCount: Int,
    val sanitizerRemovedAttributeCount: Int,
    val sanitizerDangerousProtocolCount: Int,
    val sanitizerEventHandlerCount: Int,
    val sanitizerRuntimeReason: String,
    val sanitizerExistingSafetyAgreed: Boolean,
    val compilePhaseSampleCount: Int,
    val compilePhaseMaxTotalMs: Long,
    val cssRuleCount: Int,
    val cssUnsupportedSelectorCount: Int,
    val cssParserFallbackCount: Int,
    val cssIndexMismatchCount: Int,
    val svgRouteCount: Int,
    val svgRoutes: Map<String, Int>,
    val mediaSourceCount: Int,
) {
    fun toMetadataLine(): String {
        return listOf(
            "id=$sampleId",
            "tags=${tags.toSortedMap()}",
            "css=${cssProperties.toSortedMap()}",
            "svg=${svgFeatures.toSortedMap()}",
            "hints=${visualHints.toSortedMap()}",
            "unsupported=${unsupported.toSortedMap()}",
            "animationStrategy=$animationStrategy",
            "animation=${animationCounts.toSortedMap()}",
            "compileMs=$compileMs",
            "blocks=$blockCount",
            "planRoute=$planRoute",
            "actualRoute=$actualRoute",
            "routeMismatch=$routeMismatchReason",
            "actualDecisionSource=$actualDecisionSource",
            "planConfidence=$planConfidence",
            "planReason=$planReason",
            "planSourceNodes=$planSourceNodeCount",
            "planEstimatedRenderBlocks=$planEstimatedRenderBlockCount",
            "planTextFlowCandidates=$planTextFlowCandidateCount",
            "planSnapshotIslandCandidates=$planSnapshotIslandCandidateCount",
            "planInteractiveActions=$planInteractiveActionCount",
            "planVisualHintCount=$planVisualHintCount",
            "planUnsupportedCount=$planUnsupportedCount",
            "astNodes=$astNodeCount",
            "astTextRuns=$astTextRunCount",
            "astParagraphs=$astParagraphCount",
            "astTextFlowCandidates=$astTextFlowCandidateCount",
            "astBlockedTextFlow=$astBlockedTextFlowCount",
            "documentNodes=$documentNodeCount",
            "documentTextFlowEligible=$documentTextFlowEligibleCount",
            "documentSnapshotEligible=$documentSnapshotEligibleCount",
            "documentInlineRequired=$documentInlineRequiredCount",
            "documentRoute=$documentRoute",
            "documentRouteReason=$documentRouteReason",
            "transformPipeline=$transformPipelineVersion",
            "transformPasses=$transformPassCount",
            "transformRouteMismatch=$transformRouteMismatchReason",
            "transformImportLoss=$transformImportConversionLossCount",
            "transformWarnings=$transformWarningCount",
            "textFlowApplied=$textFlowAppliedCount",
            "renderNodeReductionEstimate=$renderNodeReductionEstimate",
            "textFlowBlockedReasonCount=$textFlowBlockedReasonCount",
            "sanitizerRemovedTags=$sanitizerRemovedTagCount",
            "sanitizerRemovedAttrs=$sanitizerRemovedAttributeCount",
            "sanitizerDangerousProtocols=$sanitizerDangerousProtocolCount",
            "sanitizerEventHandlers=$sanitizerEventHandlerCount",
            "sanitizerRuntimeReason=$sanitizerRuntimeReason",
            "sanitizerExistingSafetyAgreed=$sanitizerExistingSafetyAgreed",
            "compilePhaseSamples=$compilePhaseSampleCount",
            "compilePhaseMaxTotalMs=$compilePhaseMaxTotalMs",
            "cssRuleCount=$cssRuleCount",
            "cssUnsupportedSelectors=$cssUnsupportedSelectorCount",
            "cssParserFallbacks=$cssParserFallbackCount",
            "cssIndexMismatches=$cssIndexMismatchCount",
            "svgRouteCount=$svgRouteCount",
            "svgRoutes=${svgRoutes.toSortedMap()}",
            "mediaSourceCount=$mediaSourceCount",
        ).joinToString(" ")
    }
}

private fun RichHtmlSnapshotRoute.toPlanRouteName(): String = when (this) {
    RichHtmlSnapshotRoute.Native -> RichRenderPlanRoute.Native.name
    RichHtmlSnapshotRoute.Snapshot -> RichRenderPlanRoute.Snapshot.name
    RichHtmlSnapshotRoute.DynamicPreview -> RichRenderPlanRoute.DynamicPreview.name
}
