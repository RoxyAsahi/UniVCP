package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.RichContentTransformPipeline
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichAggregateFidelityReport
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichBenchmarkEvidenceStatus
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichBenchmarkJourneyManifest
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFidelityCategory
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFidelityFixture
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFidelityFixtureSourceKind
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFidelityFixtures
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFidelityPrivacyMode
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFixtureReportBuilder
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFixtureRenderMetadata
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichFixtureReport
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichGapPriority
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichPixelBuffer
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichReferenceTheme
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichReferenceViewport
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichRenderPlatformVersions
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichScreenshotReferencePipeline
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichVisualDiff
import me.rerere.rikkahub.ui.components.richtext.fidelity.RichVisualDiffClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RichFidelityPlatformTest {
    @Test
    fun `fixture metadata protects metadata only privacy`() {
        try {
            RichFidelityFixture(
                id = "private",
                sourceKind = RichFidelityFixtureSourceKind.DeviceSeed,
                contentDigest = "sha256:test",
                category = RichFidelityCategory.MixedInteractive,
                privacyMode = RichFidelityPrivacyMode.MetadataOnly,
                html = "<div>secret</div>",
            )
            fail("metadata-only fixture accepted raw HTML")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        val device = RichFidelityFixtures.deviceSeedDescriptor()
        assertEquals(RichFidelityPrivacyMode.LocalDeviceOnly, device.privacyMode)
        assertFalse(device.id.contains("<"))
    }

    @Test
    fun `screenshot pipeline plans deterministic artifact paths`() {
        val fixture = RichFidelityFixtures.synthetic.first()
        val artifacts = RichScreenshotReferencePipeline.planArtifacts(
            fixtures = listOf(fixture),
            outputDir = File("build/rich-fidelity"),
            viewports = listOf(RichReferenceViewport.PhoneNormal),
            themes = listOf(RichReferenceTheme.Dark),
        )

        val artifact = artifacts.single()
        assertTrue(artifact.nativeScreenshotPath.endsWith("native.png"))
        assertTrue(artifact.webViewScreenshotPath.endsWith("webview.png"))
        assertTrue(artifact.metadataPath.endsWith("metadata.json"))
    }

    @Test
    fun `screenshot pipeline writes metadata only artifact without claiming screenshots`() {
        val secret = "screenshot-artifact-secret"
        val fixture = RichFidelityFixture(
            id = "artifact-fixture",
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = "sha256:artifact",
            category = RichFidelityCategory.CssVisual,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = """
                <div id="vcp-root">
                  <style>.visual { filter: blur(1px); }</style>
                  <div class="visual"><a href="javascript:$secret" onclick="evil()">link</a>$secret</div>
                </div>
            """.trimIndent(),
        )
        val outputDir = Files.createTempDirectory("rich-fidelity-metadata").toFile()

        val artifacts = RichScreenshotReferencePipeline.writeMetadataArtifacts(
            fixtures = listOf(fixture),
            outputDir = outputDir,
            viewports = listOf(RichReferenceViewport.PhoneNormal),
            themes = listOf(RichReferenceTheme.Dark),
        )

        val metadata = File(artifacts.single().metadataPath).readText()
        val aggregate = File(outputDir, "aggregate.json").readText()
        assertTrue(metadata.contains("\"status\": \"DeferredJvmMetadataOnly\""))
        assertTrue(metadata.contains("\"nativeExists\": false"))
        assertTrue(metadata.contains("\"webViewExists\": false"))
        assertTrue(metadata.contains("\"snapshotExists\": false"))
        assertTrue(metadata.contains("\"nativeStatus\": \"MissingDeferred\""))
        assertTrue(metadata.contains("\"webViewStatus\": \"MissingDeferred\""))
        assertTrue(metadata.contains("\"snapshotStatus\": \"MissingDeferred\""))
        assertTrue(metadata.contains("\"fixtureId\": \"artifact-fixture\""))
        assertTrue(metadata.contains("\"route\""))
        assertTrue(metadata.contains("\"plannedRoute\""))
        assertTrue(metadata.contains("\"actualRoute\""))
        assertTrue(metadata.contains("\"routeMismatch\""))
        assertTrue(metadata.contains("\"actualDecisionSource\""))
        assertTrue(metadata.contains("\"documentId\""))
        assertTrue(metadata.contains("\"documentSchemaVersion\""))
        assertTrue(metadata.contains("\"documentSourceKind\""))
        assertTrue(metadata.contains("\"astNodeCount\""))
        assertTrue(metadata.contains("\"cssRuleCount\""))
        assertTrue(metadata.contains("\"cssSelectorCount\""))
        assertTrue(metadata.contains("\"cssIdRuleCount\""))
        assertTrue(metadata.contains("\"cssClassRuleCount\""))
        assertTrue(metadata.contains("\"cssComplexRuleCount\""))
        assertTrue(metadata.contains("\"cssAverageCandidateRules\""))
        assertTrue(metadata.contains("\"cssParserFallbackCount\""))
        assertTrue(metadata.contains("\"cssIndexMismatchCount\""))
        assertTrue(metadata.contains("\"cssP95CandidateRules\""))
        assertTrue(metadata.contains("\"cssSelectorMatchMs\""))
        assertTrue(metadata.contains("\"cssEquivalenceFixtureCount\""))
        assertTrue(metadata.contains("\"cssEquivalenceMismatchCount\""))
        assertTrue(metadata.contains("\"cssEquivalenceFallbackUsedCount\""))
        assertTrue(metadata.contains("\"textFlowInlineFeaturePreservedCount\""))
        assertTrue(metadata.contains("\"subtreeDigestCacheHitRate\""))
        assertTrue(metadata.contains("\"snapshotIslandBitmapCacheHitCount\""))
        assertTrue(metadata.contains("\"snapshotIslandHeightCacheHitCount\""))
        assertTrue(metadata.contains("\"snapshotIslandStyleBoundaries\""))
        assertTrue(metadata.contains("\"sanitizerDangerousProtocolCount\""))
        assertTrue(metadata.contains("\"sanitizerEventHandlerCount\""))
        assertTrue(metadata.contains("\"mediaRequestCount\""))
        assertTrue(metadata.contains("\"svgRouteCount\""))
        assertTrue(metadata.contains("\"preparedDrawCacheHitCount\""))
        assertTrue(metadata.contains("\"compileTotalMs\""))
        assertTrue(metadata.contains("\"compileCascadeMs\""))
        assertTrue(metadata.contains("\"transformPassDurationTotalMs\""))
        assertTrue(metadata.contains("\"transformPassDurationsMs\""))
        assertTrue(metadata.contains("\"placeholderHeightPx\""))
        assertTrue(metadata.contains("\"measuredHeightPx\""))
        assertTrue(metadata.contains("\"heightCacheHit\""))
        assertTrue(metadata.contains("\"heightCacheConfidence\""))
        assertTrue(metadata.contains("\"heightCacheSourceRoute\""))
        assertTrue(metadata.contains("\"heightDeltaPx\""))
        assertTrue(metadata.contains("\"heightDeltaRatio\""))
        assertTrue(metadata.contains("\"visualDiffClass\""))
        assertTrue(metadata.contains("\"visualDiffHeightDeltaPx\""))
        assertTrue(metadata.contains("\"visualDiffMeanAbsolutePixelDifference\""))
        assertTrue(metadata.contains("\"visualDiffMismatchRatio\""))
        assertFalse(metadata.contains(secret))
        assertFalse(metadata.contains("<style"))
        assertFalse(metadata.contains(".visual"))
        assertTrue(aggregate.contains("\"fixtureCount\""))
        assertTrue(aggregate.contains("\"screenshotArtifactCount\""))
        assertTrue(aggregate.contains("\"screenshotMissingNativeCount\""))
        assertTrue(aggregate.contains("\"screenshotMissingWebViewCount\""))
        assertTrue(aggregate.contains("\"screenshotMissingSnapshotCount\""))
        assertTrue(aggregate.contains("\"referenceEvidenceStatus\": \"DeferredJvmMetadataOnly\""))
        assertTrue(aggregate.contains("\"routeDistribution\""))
        assertTrue(aggregate.contains("\"routeMismatchReasonRanking\""))
        assertTrue(aggregate.contains("\"actualDecisionSourceRanking\""))
        assertTrue(aggregate.contains("\"plannedActualRoutePairs\""))
        assertTrue(aggregate.contains("\"visualDiffClassRanking\""))
        assertTrue(aggregate.contains("\"visualDiffReferenceFailedCount\""))
        assertTrue(aggregate.contains("\"heightCacheHitCount\""))
        assertTrue(aggregate.contains("\"heightCacheConfidenceRanking\""))
        assertTrue(aggregate.contains("\"heightCacheSourceRouteRanking\""))
        assertTrue(aggregate.contains("\"averagePlaceholderHeightPx\""))
        assertTrue(aggregate.contains("\"averageCompileTotalMs\""))
        assertTrue(aggregate.contains("\"totalCssSelectorCount\""))
        assertTrue(aggregate.contains("\"totalCssComplexRuleCount\""))
        assertTrue(aggregate.contains("\"averageCssCandidateRules\""))
        assertTrue(aggregate.contains("\"averageCssP95CandidateRules\""))
        assertTrue(aggregate.contains("\"totalCssParserFallbackCount\""))
        assertTrue(aggregate.contains("\"totalCssUnsupportedSelectorCount\""))
        assertTrue(aggregate.contains("\"totalCssIndexMismatchCount\""))
        assertTrue(aggregate.contains("\"totalSanitizerDangerousProtocolCount\""))
        assertTrue(aggregate.contains("\"sanitizerRuntimeReasonRanking\""))
        assertTrue(aggregate.contains("\"sanitizerDisagreementCount\""))
        assertTrue(aggregate.contains("\"cssEquivalenceFixtureCount\""))
        assertTrue(aggregate.contains("\"cssEquivalenceMismatchCount\""))
        assertTrue(aggregate.contains("\"cssEquivalenceFallbackUsedCount\""))
        assertTrue(aggregate.contains("\"totalSnapshotIslandRenderCount\""))
        assertTrue(aggregate.contains("\"snapshotIslandStyleBoundaryRanking\""))
        assertTrue(aggregate.contains("\"totalMediaRequestCount\""))
        assertTrue(aggregate.contains("\"mediaFailureCount\""))
        assertTrue(aggregate.contains("\"mediaKindRanking\""))
        assertTrue(aggregate.contains("\"mediaSafetyRanking\""))
        assertTrue(aggregate.contains("\"totalSvgRouteCount\""))
        assertTrue(aggregate.contains("\"svgRouteRanking\""))
        assertTrue(aggregate.contains("\"svgRouteReasonRanking\""))
        assertTrue(aggregate.contains("\"totalPreparedDrawCacheHits\""))
        assertTrue(aggregate.contains("\"benchmarkEvidenceStatus\""))
        assertTrue(aggregate.contains("\"summaryLine\""))
        assertFalse(aggregate.contains(secret))
        assertFalse(aggregate.contains("<style"))
        assertFalse(aggregate.contains(".visual"))
    }

    @Test
    fun `visual diff classifies pass minor and needs review`() {
        val black = RichPixelBuffer(2, 1, intArrayOf(0xff000000.toInt(), 0xff000000.toInt()))
        val almostBlack = RichPixelBuffer(2, 1, intArrayOf(0xff080808.toInt(), 0xff090909.toInt()))
        val white = RichPixelBuffer(2, 1, intArrayOf(0xffffffff.toInt(), 0xffffffff.toInt()))

        assertEquals(RichVisualDiffClass.Pass, RichVisualDiff.compare(black, black).classification)
        assertEquals(RichVisualDiffClass.MinorDifference, RichVisualDiff.compare(black, almostBlack).classification)
        assertEquals(RichVisualDiffClass.NeedsReview, RichVisualDiff.compare(black, white).classification)
        assertEquals(RichVisualDiffClass.DynamicNotComparable, RichVisualDiff.compare(black, white, dynamic = true).classification)
    }

    @Test
    fun `frequency weighted report ranks high frequency high impact gaps`() {
        val reports = listOf(
            report("a", mapOf("CssFilter" to 1), RichVisualDiffClass.NeedsReview),
            report("b", mapOf("CssFilter" to 1), RichVisualDiffClass.NeedsReview),
            report("c", mapOf("SvgMask" to 1), RichVisualDiffClass.MinorDifference),
        )

        val aggregate = RichAggregateFidelityReport.from(reports)

        assertEquals(3, aggregate.fixtureCountByCategory.values.sum())
        assertEquals(RichGapPriority.HighFrequencyHighImpact, aggregate.topGaps.first().priority)
        assertEquals("CssFilter", aggregate.topGaps.first().key)
    }

    @Test
    fun `aggregate report ranks selector and snapshot island metadata`() {
        val reports = listOf(
            report("selector-heavy", emptyMap(), RichVisualDiffClass.MinorDifference).let {
                it.copy(
                    metadata = it.metadata.copy(
                        cssP95CandidateRules = 21,
                        cssSelectorCount = 8,
                        cssIdRuleCount = 1,
                        cssClassRuleCount = 2,
                        cssTagRuleCount = 1,
                        cssAttrRuleCount = 1,
                        cssPseudoRuleCount = 1,
                        cssUniversalRuleCount = 1,
                        cssComplexRuleCount = 1,
                        cssAverageCandidateRules = 3.5f,
                        cssSelectorMatchMs = 8,
                        cssParserFallbackCount = 2,
                        cssUnsupportedSelectorCount = 3,
                        cssIndexMismatchCount = 2,
                        cssLegacyVerificationSkippedCount = 12,
                        cssEquivalenceFixtureCount = 3,
                        cssEquivalenceMismatchCount = 1,
                        cssEquivalenceFallbackUsedCount = 1,
                        cssEquivalenceMismatchProperties = mapOf("background" to 1),
                        cssHighCostSelectorCategories = mapOf(
                            "SubstringAttribute" to 1,
                            "LongDescendantChain" to 2,
                        ),
                        snapshotIslandRenderCount = 2,
                        snapshotIslandBitmapCacheHitCount = 1,
                        snapshotIslandBitmapCacheMissCount = 1,
                        snapshotIslandHeightCacheHitCount = 1,
                        snapshotIslandFallbackReasons = mapOf("snapshot-failed" to 1),
                        snapshotIslandStyleBoundaries = mapOf("neutral-wrapper" to 2),
                        actualDecisionSource = "SnapshotPolicy",
                        routeMismatchReason = "PolicyOverride",
                    )
                )
            },
            report("island-benefit", emptyMap(), RichVisualDiffClass.Pass).let {
                it.copy(
                    metadata = it.metadata.copy(
                        cssP95CandidateRules = 4,
                        snapshotIslandAppliedCount = 1,
                        wholeSnapshotAvoided = true,
                    )
                )
            },
        )

        val aggregate = RichAggregateFidelityReport.from(reports)

        assertEquals(1, aggregate.routeMismatchCount)
        assertEquals(1, aggregate.nonOrchestratorDecisionCount)
        assertEquals(1, aggregate.routeMismatchReasonRanking["PolicyOverride"])
        assertEquals(1, aggregate.actualDecisionSourceRanking["SnapshotPolicy"])
        assertEquals(2, aggregate.plannedActualRoutePairs["Native->Native"])
        assertEquals(1, aggregate.snapshotIslandAppliedCount)
        assertEquals(1, aggregate.wholeSnapshotAvoidedCount)
        assertEquals(2, aggregate.totalSnapshotIslandRenderCount)
        assertEquals(1, aggregate.totalSnapshotIslandBitmapCacheHits)
        assertEquals(1, aggregate.totalSnapshotIslandBitmapCacheMisses)
        assertEquals(1, aggregate.totalSnapshotIslandHeightCacheHits)
        assertEquals(1, aggregate.snapshotIslandFallbackReasonRanking["snapshot-failed"])
        assertEquals(2, aggregate.snapshotIslandStyleBoundaryRanking["neutral-wrapper"])
        assertEquals(0, aggregate.totalSubtreeDigestCacheHits)
        assertEquals(0, aggregate.totalSubtreeDigestCacheMisses)
        assertEquals(8, aggregate.totalCssSelectorCount)
        assertEquals(1, aggregate.totalCssIdRuleCount)
        assertEquals(2, aggregate.totalCssClassRuleCount)
        assertEquals(1, aggregate.totalCssTagRuleCount)
        assertEquals(1, aggregate.totalCssAttrRuleCount)
        assertEquals(1, aggregate.totalCssPseudoRuleCount)
        assertEquals(1, aggregate.totalCssUniversalRuleCount)
        assertEquals(1, aggregate.totalCssComplexRuleCount)
        assertEquals(3.5f, aggregate.averageCssCandidateRules, 0.001f)
        assertEquals(2, aggregate.totalCssIndexMismatchCount)
        assertEquals(2, aggregate.totalCssParserFallbackCount)
        assertEquals(3, aggregate.totalCssUnsupportedSelectorCount)
        assertEquals(12, aggregate.totalCssLegacyVerificationSkippedCount)
        assertEquals(3, aggregate.totalCssEquivalenceFixtureCount)
        assertEquals(1, aggregate.totalCssEquivalenceMismatchCount)
        assertEquals(1, aggregate.totalCssEquivalenceFallbackUsedCount)
        assertEquals(1, aggregate.cssEquivalenceMismatchPropertyRanking["background"])
        assertEquals(listOf("selector-heavy"), aggregate.cssEquivalenceMismatchFixtures)
        assertEquals(1, aggregate.cssHighCostSelectorRanking["SubstringAttribute"])
        assertEquals(2, aggregate.cssHighCostSelectorRanking["LongDescendantChain"])
        assertEquals(0L, aggregate.totalPreparedDrawCacheHits)
        assertEquals(0L, aggregate.totalPreparedDrawCacheMisses)
        assertEquals("selector-heavy", aggregate.worstSelectorCandidateFixtures.first())
        assertEquals("selector-heavy", aggregate.selectorIndexMismatchFixtures.first())
        assertEquals("selector-heavy", aggregate.slowSelectorFixtures.first())
    }

    @Test
    fun `aggregate report ranks compile height route and v5 warning risks`() {
        val reports = listOf(
            report("slow-compile", emptyMap(), RichVisualDiffClass.MinorDifference).let {
                it.copy(
                    metadata = it.metadata.copy(
                        compileParseMs = 3,
                        compileCascadeMs = 14,
                        compileDomCompileMs = 21,
                        compileRenderModelMs = 5,
                        compileOptimizerMs = 8,
                        compileTotalMs = 51,
                        placeholderHeightPx = 360,
                        measuredNativeHeight = 420,
                        measuredWebViewHeight = 300,
                        heightCacheHit = true,
                        heightCachePersistent = true,
                        heightCacheConfidence = "MeasuredNative",
                        heightCacheSourceRoute = "Native",
                        mediaRequestCount = 2,
                        mediaKinds = mapOf("Image" to 1, "BackgroundImage" to 1),
                        mediaSafety = mapOf("Safe" to 1, "UnsupportedProtocol" to 1),
                        svgRouteCount = 1,
                        svgRoutes = mapOf("SnapshotIsland" to 1),
                        svgRouteReasons = mapOf("complex-static-snapshot" to 1),
                        sanitizerDangerousProtocolCount = 1,
                        sanitizerEventHandlerCount = 1,
                        sanitizerRuntimeReason = "Runtime",
                        sanitizerExistingSafetyAgreed = false,
                    )
                )
            },
            report("slow-optimizer", emptyMap(), RichVisualDiffClass.Pass).let {
                it.copy(
                    metadata = it.metadata.copy(
                        compileParseMs = 1,
                        compileCascadeMs = 2,
                        compileDomCompileMs = 4,
                        compileRenderModelMs = 6,
                        compileOptimizerMs = 30,
                        compileTotalMs = 43,
                        placeholderHeightPx = 100,
                        measuredNativeHeight = 90,
                        measuredWebViewHeight = 120,
                        heightCacheHit = false,
                        heightCacheConfidence = "MeasuredSnapshot",
                        heightCacheSourceRoute = "Snapshot",
                        actualDecisionSource = "RuntimeFailure",
                        routeMismatchReason = "RuntimeFallback",
                        snapshotIslandAppliedCount = 2,
                        wholeSnapshotAvoided = true,
                    )
                )
            },
        )

        val aggregate = RichAggregateFidelityReport.from(reports)
        val summary = aggregate.summaryLine()

        assertEquals("slow-compile", aggregate.slowCompileFixtures.first())
        assertEquals("slow-compile", aggregate.slowCascadeFixtures.first())
        assertEquals("slow-optimizer", aggregate.slowOptimizerFixtures.first())
        assertEquals("slow-compile", aggregate.worstHeightDeltaFixtures.first())
        assertEquals(listOf("slow-optimizer"), aggregate.routeMismatchFixtures)
        assertEquals(listOf("slow-optimizer"), aggregate.nonOrchestratorDecisionFixtures)
        assertEquals(1, aggregate.routeMismatchReasonRanking["RuntimeFallback"])
        assertEquals(1, aggregate.actualDecisionSourceRanking["RuntimeFailure"])
        assertEquals(2, aggregate.plannedActualRoutePairs["Native->Native"])
        assertEquals(1, aggregate.heightCacheHitCount)
        assertEquals(1, aggregate.persistentHeightCacheHitCount)
        assertEquals(1, aggregate.heightCacheConfidenceRanking["MeasuredNative"])
        assertEquals(1, aggregate.heightCacheConfidenceRanking["MeasuredSnapshot"])
        assertEquals(1, aggregate.heightCacheSourceRouteRanking["Native"])
        assertTrue(aggregate.averagePlaceholderHeightPx > 0f)
        assertEquals(listOf("slow-optimizer"), aggregate.snapshotIslandBenefitFixtures)
        assertTrue(aggregate.averageCompileTotalMs > 0f)
        assertTrue(aggregate.p95CompileTotalMs > 0L)
        assertEquals(4, aggregate.sanitizerWarningCount)
        assertEquals(1, aggregate.totalSanitizerDangerousProtocolCount)
        assertEquals(1, aggregate.totalSanitizerEventHandlerCount)
        assertEquals(1, aggregate.sanitizerRuntimeReasonRanking["Runtime"])
        assertEquals(1, aggregate.sanitizerDisagreementCount)
        assertEquals(2, aggregate.totalMediaRequestCount)
        assertEquals(1, aggregate.mediaFailureCount)
        assertEquals(1, aggregate.mediaKindRanking["Image"])
        assertEquals(1, aggregate.mediaKindRanking["BackgroundImage"])
        assertEquals(1, aggregate.mediaSafetyRanking["UnsupportedProtocol"])
        assertEquals(1, aggregate.totalSvgRouteCount)
        assertEquals(1, aggregate.svgRouteRanking["SnapshotIsland"])
        assertEquals(1, aggregate.svgRouteReasonRanking["complex-static-snapshot"])
        assertTrue(summary.contains("avgCompileTotalMs="))
        assertTrue(summary.contains("cssEquivalenceFixtures="))
        assertTrue(summary.contains("cssParserFallbacks="))
        assertTrue(summary.contains("cssUnsupportedSelectors="))
        assertTrue(summary.contains("routeMismatchReasons="))
        assertTrue(summary.contains("actualDecisionSources="))
        assertTrue(summary.contains("plannedActualRoutePairs="))
        assertTrue(summary.contains("heightCacheHits=1"))
        assertTrue(summary.contains("heightCacheConfidence="))
        assertTrue(summary.contains("sanitizerRuntimeReasons="))
        assertTrue(summary.contains("sanitizerDisagreements=1"))
        assertTrue(summary.contains("mediaRequests=2"))
        assertTrue(summary.contains("mediaKinds="))
        assertTrue(summary.contains("svgRoutes="))
        assertTrue(summary.contains("benchmarkEvidence=DeferredNoDevice"))
        assertTrue(summary.contains("slowCompileFixtures=[slow-compile"))
        assertFalse(summary.contains("<"))
        assertFalse(summary.contains("javascript:"))
    }

    @Test
    fun `benchmark journey manifest records v6e coverage without claiming device evidence`() {
        val report = RichBenchmarkJourneyManifest.deferredNoDeviceReport()
        val summary = report.summaryLine()

        assertEquals(RichBenchmarkEvidenceStatus.DeferredNoDevice, report.evidenceStatus)
        assertTrue(report.requiredJourneyCount >= 6)
        assertTrue(report.upwardHistoryJourneyCount >= 1)
        assertTrue(report.baselineProfileCoveredCount >= 1)
        assertTrue(report.missingRequiredJourneyNames.isEmpty())
        assertTrue(summary.contains("RichChatTextHeavyScrollBenchmark"))
        assertTrue(summary.contains("RichChatHistoryUpwardScrollBenchmark"))
        assertTrue(summary.contains("BaselineProfileRichChatJourney"))
        assertFalse(summary.contains("<"))
        assertFalse(summary.contains("文字游龙"))
    }

    @Test
    fun `fixture report builder joins plan compiler ast subtree and css metadata`() {
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlCompiler.clearCacheForTest()
        RichContentTransformPipeline.resetSubtreeDigestCacheForTest()
        val secret = "fidelity-report-secret"
        val fixture = RichFidelityFixture(
            id = "curated-css-fixture",
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = "digest-only",
            category = RichFidelityCategory.CssVisual,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = """
                <div id="vcp-root">
                  <style>
                    #target { color: red; }
                    [data-kind="visual"] { font-weight: 700; }
                    .visual { filter: blur(2px); width: 120px; height: 80px; }
                  </style>
                  <div id="target" class="visual" data-kind="visual">$secret</div>
                </div>
            """.trimIndent(),
        )

        val report = RichFixtureReportBuilder.fromFixture(fixture)
        val serialized = report.metadataLine() + "\n" + report.toString()

        assertEquals("curated-css-fixture", report.metadata.fixtureId)
        assertTrue(report.metadata.cssRuleCount >= 2)
        assertTrue(report.metadata.cssEquivalenceFixtureCount >= 3)
        assertEquals(0, report.metadata.cssEquivalenceMismatchCount)
        assertTrue(report.metadata.compileTotalMs >= 0L)
        assertTrue(report.metadata.metadataLine().contains("compileTotalMs="))
        assertTrue(report.metadata.metadataLine().contains("cssEquivalenceFixtures="))
        assertTrue(report.metadata.metadataLine().contains("cssEquivalenceMismatches=0"))
        assertTrue(report.metadata.metadataLine().contains("transformPassDurationTotalMs="))
        assertTrue(report.metadataLine().contains("visualDiffClass="))
        assertTrue(report.metadataLine().contains("visualDiffMismatchRatio="))
        assertTrue(report.metadata.transformPassDurationsMs.containsKey("TextFlowPlan"))
        assertTrue(report.metadata.cssAttrRuleCount >= 1)
        assertEquals(0, report.metadata.sanitizerRemovedTagCount)
        assertEquals(0, report.metadata.sanitizerDangerousProtocolCount)
        assertTrue(report.metadata.astNodeCount > 0)
        assertTrue(report.metadata.canonicalAstNodeCount > 0)
        assertTrue(report.metadata.documentId.isNotBlank())
        assertEquals(RichRenderPlatformVersions.RichContentDocument, report.metadata.documentSchemaVersion)
        assertEquals("Html", report.metadata.documentSourceKind)
        assertTrue(report.metadata.subtreeRouteCandidateNodeCount >= 1)
        assertTrue(report.metadata.subtreeDigestCacheMissCount > 0)
        assertEquals("Document", report.metadata.textFlowDecisionSource)
        assertEquals("Document", report.metadata.subtreeRouteDecisionSource)
        assertEquals("AstGatedRenderModel", report.metadata.textFlowLoweringMode)
        assertEquals("AstGatedRenderModel", report.metadata.subtreeRouteLoweringMode)
        assertEquals("Orchestrator", report.metadata.actualDecisionSource)
        assertTrue(serialized.contains("textFlowInlineFeaturesPreserved="))
        assertTrue(serialized.contains("documentId=${report.metadata.documentId}"))
        assertTrue(serialized.contains("documentSchema=${RichRenderPlatformVersions.RichContentDocument}"))
        assertTrue(serialized.contains("documentSourceKind=Html"))
        assertTrue(report.metadata.plannedRoute.isNotBlank())
        assertTrue(report.metadata.actualRoute.isNotBlank())
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<style"))
        assertFalse(serialized.contains("#target"))
    }

    @Test
    fun `canonical markdown and protocol fixtures emit v6h metadata without source leakage`() {
        RichContentTransformPipeline.resetSubtreeDigestCacheForTest()
        val markdown = RichFidelityFixtures.synthetic.single { it.id == "synthetic-markdown-textflow" }
        val protocol = RichFidelityFixtures.synthetic.single { it.id == "synthetic-protocol-action" }

        val markdownReport = RichFixtureReportBuilder.fromFixture(markdown)
        val protocolReport = RichFixtureReportBuilder.fromFixture(protocol)
        val serialized = markdownReport.metadata.metadataLine() + "\n" +
            protocolReport.metadata.metadataLine() + "\n" +
            markdownReport.toString() + "\n" +
            protocolReport.toString()

        assertEquals("Markdown", markdownReport.metadata.documentSourceKind)
        assertTrue(markdownReport.metadata.canonicalAstNodeCount > 0)
        assertTrue(markdownReport.metadata.textFlowEligibleNodeCount > 0)
        assertTrue(markdownReport.metadata.textFlowAppliedBlockCount > 0)
        assertTrue(markdownReport.metadata.textFlowInlineFeaturePreservedCount > 0)
        assertEquals("Document", markdownReport.metadata.textFlowDecisionSource)
        assertEquals("AstDirect", markdownReport.metadata.textFlowLoweringMode)
        assertTrue(markdownReport.metadata.renderBlockReductionRatio > 0f)
        assertEquals("Protocol", protocolReport.metadata.documentSourceKind)
        assertTrue(protocolReport.metadata.canonicalAstNodeCount > 0)
        assertEquals(1, protocolReport.metadata.subtreeNativePreservedActionCount)
        assertEquals("Native", protocolReport.metadata.route)
        assertFalse(serialized.contains("This is a"))
        assertFalse(serialized.contains("https://example.test"))
        assertFalse(serialized.contains("action:synthetic-send"))
    }

    @Test
    fun `fixture report includes sanitizer report only metadata without changing route`() {
        val secret = "fixture-sanitizer-secret"
        val fixture = RichFidelityFixture(
            id = "curated-sanitizer-fixture",
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = "digest-sanitizer",
            category = RichFidelityCategory.MixedInteractive,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = """
                <div id="vcp-root">
                  <p>$secret</p>
                  <a href="javascript:$secret" onclick="evil()">open</a>
                </div>
            """.trimIndent(),
        )

        val report = RichFixtureReportBuilder.fromFixture(fixture)
        val serialized = report.metadata.metadataLine() + "\n" + report.toString()

        assertTrue(report.metadata.sanitizerDangerousProtocolCount >= 1)
        assertTrue(report.metadata.sanitizerEventHandlerCount >= 1)
        assertTrue(report.metadata.sanitizerRuntimeReason.isNotBlank())
        assertTrue(report.metadata.route.isNotBlank())
        assertTrue(report.metadata.actualRoute.isNotBlank())
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("javascript:"))
        assertFalse(serialized.contains("onclick"))
    }

    @Test
    fun `fixture report includes media and svg route metadata without raw urls`() {
        RichHtmlRenderTelemetry.resetForTest()
        RichHtmlCompiler.clearCacheForTest()
        val secret = "fixture-media-secret"
        val fixture = RichFidelityFixture(
            id = "curated-media-svg-fixture",
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = "digest-media-svg",
            category = RichFidelityCategory.Media,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = """
                <div id="vcp-root">
                  <style>
                    .from-style { background-image:url('file:///sdcard/$secret-bg.png'); }
                    .list-style { list-style-image:url(data:text/html;base64,$secret); }
                  </style>
                  <img src="https://example.test/$secret-image.png" alt="safe label">
                  <div style="background-image:url('https://example.test/$secret.png')"></div>
                  <ul class="list-style"><li>one</li></ul>
                  <div class="from-style"></div>
                  <svg width="80" height="40"><filter id="f"></filter><rect width="80" height="40"/></svg>
                </div>
            """.trimIndent(),
        )

        val report = RichFixtureReportBuilder.fromFixture(fixture)
        val serialized = report.metadata.metadataLine() + "\n" + report.toString()

        assertTrue(report.metadata.mediaRequestCount >= 2)
        assertTrue(report.metadata.mediaKinds.containsKey("Image"))
        assertTrue(report.metadata.mediaKinds.containsKey("BackgroundImage"))
        assertTrue(report.metadata.mediaKinds.containsKey("ListStyleImage"))
        assertTrue(report.metadata.mediaSafety.containsKey("LocalFileRejected"))
        assertTrue(report.metadata.mediaSafety.containsKey("DataUriRejected"))
        assertTrue(report.metadata.mediaSafety.keys.any { it == "Safe" || it == "UnsupportedProtocol" })
        assertTrue(
            "metadata=${report.metadata} svgTelemetry=${RichHtmlRenderTelemetry.svgRouteSnapshot()}",
            report.metadata.svgRouteCount >= 1,
        )
        assertTrue(report.metadata.svgRoutes.isNotEmpty())
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("example.test"))
        assertFalse(serialized.contains("javascript:"))
        assertFalse(serialized.contains("<svg"))
    }

    @Test
    fun `fixture report includes prepared draw cache metadata`() {
        PreparedDrawCache.clearForTest()
        PreparedDrawCache.dashRecipe(listOf(2f, 1f))
        PreparedDrawCache.dashRecipe(listOf(2f, 1f))
        val fixture = RichFidelityFixture(
            id = "prepared-draw-fixture",
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = "digest-prepared",
            category = RichFidelityCategory.CssVisual,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = """<div id="vcp-root"><p>prepared draw</p></div>""",
        )

        val report = RichFixtureReportBuilder.fromFixture(fixture)
        val serialized = report.metadata.metadataLine()

        assertTrue(report.metadata.preparedDrawCacheHitCount >= 1L)
        assertTrue(report.metadata.preparedDrawCacheMissCount >= 1L)
        assertTrue(report.metadata.preparedDrawCacheHitRate > 0f)
        assertTrue(serialized.contains("preparedDrawCacheHitRate="))
    }

    @Test
    fun `fixture report observes subtree digest cache reuse without raw content`() {
        RichContentTransformPipeline.resetSubtreeDigestCacheForTest()
        val secret = "fixture-subtree-cache-secret"
        val fixture = RichFidelityFixture(
            id = "subtree-cache-fixture",
            sourceKind = RichFidelityFixtureSourceKind.Synthetic,
            contentDigest = "digest-subtree-cache",
            category = RichFidelityCategory.TextHeavy,
            privacyMode = RichFidelityPrivacyMode.CuratedPublicHtml,
            html = """<div id="vcp-root"><p>$secret</p><p><strong>again</strong></p></div>""",
        )

        val first = RichFixtureReportBuilder.fromFixture(fixture)
        val second = RichFixtureReportBuilder.fromFixture(fixture)
        val serialized = second.metadata.metadataLine()

        assertEquals(0, first.metadata.subtreeDigestCacheHitCount)
        assertTrue(first.metadata.subtreeDigestCacheMissCount > 0)
        assertTrue(second.metadata.subtreeDigestCacheHitCount > 0)
        assertTrue(second.metadata.subtreeDigestCacheHitRate > 0f)
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("<p>"))
    }

    @Test
    fun `renderer versions are explicit and cache related`() {
        assertTrue(RichRenderPlatformVersions.RichContentAst >= 1)
        assertTrue(RichRenderPlatformVersions.RichContentDocument >= 1)
        assertTrue(RichRenderPlatformVersions.RichContentTransformPipeline >= 1)
        assertTrue(RichRenderPlatformVersions.RichRenderPlan >= 1)
        assertTrue(RichRenderPlatformVersions.FidelityReportSchema >= 2)
        assertEquals(RichRenderHeightCache.RendererVersion, RichRenderPlatformVersions.heightCacheRendererVersion)
        assertEquals(RICH_HTML_SNAPSHOT_RENDERER_VERSION, RichRenderPlatformVersions.snapshotCacheRendererVersion)
    }

    @Test
    fun `fixture metadata exposes height delta fields`() {
        val metadata = report("height-delta", emptyMap(), RichVisualDiffClass.MinorDifference).metadata
        val serialized = metadata.metadataLine()

        assertEquals(-20, metadata.heightDeltaPx)
        assertTrue(metadata.heightDeltaRatio < 0f)
        assertTrue(serialized.contains("heightDeltaPx=-20"))
        assertTrue(serialized.contains("heightDeltaRatio="))
    }

    private fun report(
        id: String,
        hints: Map<String, Int>,
        diffClass: RichVisualDiffClass,
    ): RichFixtureReport {
        return RichFixtureReport(
            metadata = RichFixtureRenderMetadata(
                fixtureId = id,
                digest = "sha256:$id",
                category = RichFidelityCategory.CssVisual,
                route = "Native",
                planReason = "test",
                nativeConfidence = "Medium",
                visualHints = hints,
                unsupportedReasons = emptyMap(),
                snapshotIslandCount = 0,
                textFlowCount = 0,
                measuredNativeHeight = 100,
                measuredWebViewHeight = 120,
                renderTimeMs = 16,
            ),
            diff = RichVisualDiff.compare(null, null).copy(classification = diffClass, heightDeltaPx = 20),
        )
    }
}
