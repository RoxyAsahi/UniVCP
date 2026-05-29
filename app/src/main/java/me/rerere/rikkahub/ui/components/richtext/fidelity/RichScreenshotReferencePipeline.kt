package me.rerere.rikkahub.ui.components.richtext.fidelity

import java.io.File

internal enum class RichReferenceViewport(val widthDp: Int) {
    PhoneNarrow(320),
    PhoneNormal(390),
    TabletWide(768),
}

internal enum class RichReferenceTheme {
    Light,
    Dark,
}

internal data class RichScreenshotArtifact(
    val fixtureId: String,
    val viewport: RichReferenceViewport,
    val theme: RichReferenceTheme,
    val nativeScreenshotPath: String,
    val webViewScreenshotPath: String,
    val snapshotScreenshotPath: String,
    val metadataPath: String,
)

internal object RichScreenshotReferencePipeline {
    fun planArtifacts(
        fixtures: List<RichFidelityFixture>,
        outputDir: File,
        viewports: List<RichReferenceViewport> = listOf(RichReferenceViewport.PhoneNarrow, RichReferenceViewport.PhoneNormal),
        themes: List<RichReferenceTheme> = listOf(RichReferenceTheme.Light, RichReferenceTheme.Dark),
    ): List<RichScreenshotArtifact> {
        return fixtures.flatMap { fixture ->
            viewports.flatMap { viewport ->
                themes.map { theme ->
                    val base = File(outputDir, "${fixture.id}/${viewport.name}/${theme.name}")
                    RichScreenshotArtifact(
                        fixtureId = fixture.id,
                        viewport = viewport,
                        theme = theme,
                        nativeScreenshotPath = File(base, "native.png").path,
                        webViewScreenshotPath = File(base, "webview.png").path,
                        snapshotScreenshotPath = File(base, "snapshot.png").path,
                        metadataPath = File(base, "metadata.json").path,
                    )
                }
            }
        }
    }

    fun writeMetadataArtifacts(
        fixtures: List<RichFidelityFixture>,
        outputDir: File,
        viewports: List<RichReferenceViewport> = listOf(RichReferenceViewport.PhoneNarrow, RichReferenceViewport.PhoneNormal),
        themes: List<RichReferenceTheme> = listOf(RichReferenceTheme.Light, RichReferenceTheme.Dark),
        buildReport: (RichFidelityFixture) -> RichFixtureReport = { RichFixtureReportBuilder.fromFixture(it) },
    ): List<RichScreenshotArtifact> {
        val artifacts = planArtifacts(
            fixtures = fixtures,
            outputDir = outputDir,
            viewports = viewports,
            themes = themes,
        )
        val reports = fixtures.associate { fixture -> fixture.id to buildReport(fixture) }
        artifacts.forEach { artifact ->
            val report = reports.getValue(artifact.fixtureId)
            val metadataFile = File(artifact.metadataPath)
            metadataFile.parentFile?.mkdirs()
            metadataFile.writeText(artifactMetadataJson(artifact, report))
        }
        outputDir.mkdirs()
        File(outputDir, "aggregate.json").writeText(
            aggregateMetadataJson(
                aggregate = RichAggregateFidelityReport.from(reports.values.toList()),
                screenshotEvidence = ScreenshotEvidenceSummary.from(artifacts),
            )
        )
        return artifacts
    }

    private fun aggregateMetadataJson(
        aggregate: RichAggregateFidelityReport,
        screenshotEvidence: ScreenshotEvidenceSummary,
    ): String {
        return buildString {
            appendLine("{")
            appendJson("schemaVersion", RichRenderPlatformVersions.FidelityReportSchema, comma = true)
            appendJson("fixtureCount", aggregate.fixtureCountByCategory.values.sum(), comma = true)
            appendJson("screenshotArtifactCount", screenshotEvidence.artifactCount, comma = true)
            appendJson("screenshotMissingNativeCount", screenshotEvidence.missingNativeCount, comma = true)
            appendJson("screenshotMissingWebViewCount", screenshotEvidence.missingWebViewCount, comma = true)
            appendJson("screenshotMissingSnapshotCount", screenshotEvidence.missingSnapshotCount, comma = true)
            appendJson("referenceEvidenceStatus", screenshotEvidence.status, comma = true)
            appendJson("fixtureCountByCategory", aggregate.fixtureCountByCategory.toString(), comma = true)
            appendJson("routeDistribution", aggregate.routeDistribution.toString(), comma = true)
            appendJson("routeMismatchCount", aggregate.routeMismatchCount, comma = true)
            appendJson("nonOrchestratorDecisionCount", aggregate.nonOrchestratorDecisionCount, comma = true)
            appendJson("routeMismatchReasonRanking", aggregate.routeMismatchReasonRanking.toString(), comma = true)
            appendJson("actualDecisionSourceRanking", aggregate.actualDecisionSourceRanking.toString(), comma = true)
            appendJson("plannedActualRoutePairs", aggregate.plannedActualRoutePairs.toString(), comma = true)
            appendJson("visualDiffClassRanking", aggregate.visualDiffClassRanking.toString(), comma = true)
            appendJson("visualDiffReferenceFailedCount", aggregate.visualDiffReferenceFailedCount, comma = true)
            appendJson("snapshotIslandAppliedCount", aggregate.snapshotIslandAppliedCount, comma = true)
            appendJson("wholeSnapshotAvoidedCount", aggregate.wholeSnapshotAvoidedCount, comma = true)
            appendJson("totalSnapshotIslandRenderCount", aggregate.totalSnapshotIslandRenderCount, comma = true)
            appendJson("totalSnapshotIslandBitmapCacheHits", aggregate.totalSnapshotIslandBitmapCacheHits, comma = true)
            appendJson("totalSnapshotIslandBitmapCacheMisses", aggregate.totalSnapshotIslandBitmapCacheMisses, comma = true)
            appendJson("totalSnapshotIslandHeightCacheHits", aggregate.totalSnapshotIslandHeightCacheHits, comma = true)
            appendJson(
                "snapshotIslandFallbackReasonRanking",
                aggregate.snapshotIslandFallbackReasonRanking.toString(),
                comma = true,
            )
            appendJson(
                "snapshotIslandStyleBoundaryRanking",
                aggregate.snapshotIslandStyleBoundaryRanking.toString(),
                comma = true,
            )
            appendJson("averageHeightDelta", aggregate.averageHeightDelta, comma = true)
            appendJson("p95HeightDelta", aggregate.p95HeightDelta, comma = true)
            appendJson("heightCacheHitCount", aggregate.heightCacheHitCount, comma = true)
            appendJson("persistentHeightCacheHitCount", aggregate.persistentHeightCacheHitCount, comma = true)
            appendJson("heightCacheConfidenceRanking", aggregate.heightCacheConfidenceRanking.toString(), comma = true)
            appendJson("heightCacheSourceRouteRanking", aggregate.heightCacheSourceRouteRanking.toString(), comma = true)
            appendJson("averagePlaceholderHeightPx", aggregate.averagePlaceholderHeightPx, comma = true)
            appendJson("averageRenderTimeMs", aggregate.averageRenderTimeMs, comma = true)
            appendJson("p95RenderTimeMs", aggregate.p95RenderTimeMs, comma = true)
            appendJson("averageCompileTotalMs", aggregate.averageCompileTotalMs, comma = true)
            appendJson("p95CompileTotalMs", aggregate.p95CompileTotalMs, comma = true)
            appendJson("slowCompileFixtures", aggregate.slowCompileFixtures.toString(), comma = true)
            appendJson("worstHeightDeltaFixtures", aggregate.worstHeightDeltaFixtures.toString(), comma = true)
            appendJson("routeMismatchFixtures", aggregate.routeMismatchFixtures.toString(), comma = true)
            appendJson("nonOrchestratorDecisionFixtures", aggregate.nonOrchestratorDecisionFixtures.toString(), comma = true)
            appendJson("snapshotIslandBenefitFixtures", aggregate.snapshotIslandBenefitFixtures.toString(), comma = true)
            appendJson("visualHintRanking", aggregate.visualHintRanking.toString(), comma = true)
            appendJson("unsupportedReasonRanking", aggregate.unsupportedReasonRanking.toString(), comma = true)
            appendJson("totalCssSelectorCount", aggregate.totalCssSelectorCount, comma = true)
            appendJson("totalCssIdRuleCount", aggregate.totalCssIdRuleCount, comma = true)
            appendJson("totalCssClassRuleCount", aggregate.totalCssClassRuleCount, comma = true)
            appendJson("totalCssTagRuleCount", aggregate.totalCssTagRuleCount, comma = true)
            appendJson("totalCssAttrRuleCount", aggregate.totalCssAttrRuleCount, comma = true)
            appendJson("totalCssPseudoRuleCount", aggregate.totalCssPseudoRuleCount, comma = true)
            appendJson("totalCssUniversalRuleCount", aggregate.totalCssUniversalRuleCount, comma = true)
            appendJson("totalCssComplexRuleCount", aggregate.totalCssComplexRuleCount, comma = true)
            appendJson("averageCssCandidateRules", aggregate.averageCssCandidateRules, comma = true)
            appendJson("averageCssP95CandidateRules", aggregate.averageCssP95CandidateRules, comma = true)
            appendJson("totalCssParserFallbackCount", aggregate.totalCssParserFallbackCount, comma = true)
            appendJson("totalCssUnsupportedSelectorCount", aggregate.totalCssUnsupportedSelectorCount, comma = true)
            appendJson("totalCssIndexMismatchCount", aggregate.totalCssIndexMismatchCount, comma = true)
            appendJson(
                "totalCssLegacyVerificationSkippedCount",
                aggregate.totalCssLegacyVerificationSkippedCount,
                comma = true,
            )
            appendJson("cssHighCostSelectorRanking", aggregate.cssHighCostSelectorRanking.toString(), comma = true)
            appendJson("cssEquivalenceFixtureCount", aggregate.totalCssEquivalenceFixtureCount, comma = true)
            appendJson("cssEquivalenceMismatchCount", aggregate.totalCssEquivalenceMismatchCount, comma = true)
            appendJson("cssEquivalenceFallbackUsedCount", aggregate.totalCssEquivalenceFallbackUsedCount, comma = true)
            appendJson(
                "cssEquivalenceMismatchProperties",
                aggregate.cssEquivalenceMismatchPropertyRanking.toString(),
                comma = true,
            )
            appendJson(
                "cssEquivalenceMismatchFixtures",
                aggregate.cssEquivalenceMismatchFixtures.toString(),
                comma = true,
            )
            appendJson("topGaps", aggregate.topGaps.toString(), comma = true)
            appendJson("sanitizerWarningCount", aggregate.sanitizerWarningCount, comma = true)
            appendJson("totalSanitizerRemovedTagCount", aggregate.totalSanitizerRemovedTagCount, comma = true)
            appendJson(
                "totalSanitizerRemovedAttributeCount",
                aggregate.totalSanitizerRemovedAttributeCount,
                comma = true,
            )
            appendJson(
                "totalSanitizerDangerousProtocolCount",
                aggregate.totalSanitizerDangerousProtocolCount,
                comma = true,
            )
            appendJson("totalSanitizerEventHandlerCount", aggregate.totalSanitizerEventHandlerCount, comma = true)
            appendJson("sanitizerRuntimeReasonRanking", aggregate.sanitizerRuntimeReasonRanking.toString(), comma = true)
            appendJson("sanitizerDisagreementCount", aggregate.sanitizerDisagreementCount, comma = true)
            appendJson("totalMediaRequestCount", aggregate.totalMediaRequestCount, comma = true)
            appendJson("mediaFailureCount", aggregate.mediaFailureCount, comma = true)
            appendJson("mediaKindRanking", aggregate.mediaKindRanking.toString(), comma = true)
            appendJson("mediaSafetyRanking", aggregate.mediaSafetyRanking.toString(), comma = true)
            appendJson("totalSvgRouteCount", aggregate.totalSvgRouteCount, comma = true)
            appendJson("svgRouteRanking", aggregate.svgRouteRanking.toString(), comma = true)
            appendJson("svgRouteReasonRanking", aggregate.svgRouteReasonRanking.toString(), comma = true)
            appendJson("totalPreparedDrawCacheHits", aggregate.totalPreparedDrawCacheHits, comma = true)
            appendJson("totalPreparedDrawCacheMisses", aggregate.totalPreparedDrawCacheMisses, comma = true)
            appendJson("averagePreparedDrawCacheHitRate", aggregate.averagePreparedDrawCacheHitRate, comma = true)
            appendJson("benchmarkEvidenceStatus", aggregate.benchmarkJourneyReport.evidenceStatus.name, comma = true)
            appendJson("benchmarkJourneySummary", aggregate.benchmarkJourneyReport.summaryLine(), comma = true)
            appendJson("summaryLine", aggregate.summaryLine(), comma = false)
            appendLine("}")
        }
    }

    private fun artifactMetadataJson(
        artifact: RichScreenshotArtifact,
        report: RichFixtureReport,
    ): String {
        val metadata = report.metadata
        val diff = report.diff
        return buildString {
            appendLine("{")
            appendJson("schemaVersion", RichRenderPlatformVersions.FidelityReportSchema, comma = true)
            appendJson("fixtureId", artifact.fixtureId, comma = true)
            appendJson("digest", metadata.digest, comma = true)
            appendJson("documentId", metadata.documentId, comma = true)
            appendJson("documentSchemaVersion", metadata.documentSchemaVersion, comma = true)
            appendJson("documentSourceKind", metadata.documentSourceKind, comma = true)
            appendJson("viewport", artifact.viewport.name, comma = true)
            appendJson("viewportWidthDp", artifact.viewport.widthDp, comma = true)
            appendJson("theme", artifact.theme.name, comma = true)
            appendJson("sourceKind", metadata.sourceKind, comma = true)
            appendJson("privacyMode", metadata.privacyMode, comma = true)
            appendJson("route", metadata.route, comma = true)
            appendJson("plannedRoute", metadata.plannedRoute, comma = true)
            appendJson("actualRoute", metadata.actualRoute, comma = true)
            appendJson("routeMismatch", metadata.routeMismatchReason, comma = true)
            appendJson("actualDecisionSource", metadata.actualDecisionSource, comma = true)
            appendJson("renderPlanVersion", metadata.renderPlanVersion, comma = true)
            appendJson("renderModelVersion", metadata.renderModelVersion, comma = true)
            appendJson("heightCacheRendererVersion", metadata.heightCacheRendererVersion, comma = true)
            appendJson("snapshotCacheRendererVersion", metadata.snapshotCacheRendererVersion, comma = true)
            appendJson("nativeConfidence", metadata.nativeConfidence, comma = true)
            appendJson("astNodeCount", metadata.astNodeCount, comma = true)
            appendJson("canonicalAstNodeCount", metadata.canonicalAstNodeCount, comma = true)
            appendJson("textFlowEligibleNodeCount", metadata.textFlowEligibleNodeCount, comma = true)
            appendJson("textFlowAppliedBlockCount", metadata.textFlowAppliedBlockCount, comma = true)
            appendJson(
                "textFlowInlineFeaturePreservedCount",
                metadata.textFlowInlineFeaturePreservedCount,
                comma = true,
            )
            appendJson("textFlowDecisionSource", metadata.textFlowDecisionSource, comma = true)
            appendJson("textFlowLoweringMode", metadata.textFlowLoweringMode, comma = true)
            appendJson("transformPassDurationTotalMs", metadata.transformPassDurationTotalMs, comma = true)
            appendJson("transformPassDurationsMs", metadata.transformPassDurationsMs.toString(), comma = true)
            appendJson("subtreeRouteCandidateNodeCount", metadata.subtreeRouteCandidateNodeCount, comma = true)
            appendJson("subtreeRouteDecisionSource", metadata.subtreeRouteDecisionSource, comma = true)
            appendJson("subtreeRouteLoweringMode", metadata.subtreeRouteLoweringMode, comma = true)
            appendJson("subtreeDigestCacheHitCount", metadata.subtreeDigestCacheHitCount, comma = true)
            appendJson("subtreeDigestCacheMissCount", metadata.subtreeDigestCacheMissCount, comma = true)
            appendJson("subtreeDigestCacheHitRate", metadata.subtreeDigestCacheHitRate, comma = true)
            appendJson("snapshotIslandAppliedCount", metadata.snapshotIslandAppliedCount, comma = true)
            appendJson("wholeSnapshotAvoided", metadata.wholeSnapshotAvoided, comma = true)
            appendJson("snapshotIslandRenderCount", metadata.snapshotIslandRenderCount, comma = true)
            appendJson("snapshotIslandBitmapCacheHitCount", metadata.snapshotIslandBitmapCacheHitCount, comma = true)
            appendJson("snapshotIslandBitmapCacheMissCount", metadata.snapshotIslandBitmapCacheMissCount, comma = true)
            appendJson("snapshotIslandHeightCacheHitCount", metadata.snapshotIslandHeightCacheHitCount, comma = true)
            appendJson("snapshotIslandFallbackReasons", metadata.snapshotIslandFallbackReasons.toString(), comma = true)
            appendJson("snapshotIslandStyleBoundaries", metadata.snapshotIslandStyleBoundaries.toString(), comma = true)
            appendJson("cssRuleCount", metadata.cssRuleCount, comma = true)
            appendJson("cssSelectorCount", metadata.cssSelectorCount, comma = true)
            appendJson("cssIdRuleCount", metadata.cssIdRuleCount, comma = true)
            appendJson("cssClassRuleCount", metadata.cssClassRuleCount, comma = true)
            appendJson("cssTagRuleCount", metadata.cssTagRuleCount, comma = true)
            appendJson("cssAttrRuleCount", metadata.cssAttrRuleCount, comma = true)
            appendJson("cssPseudoRuleCount", metadata.cssPseudoRuleCount, comma = true)
            appendJson("cssUniversalRuleCount", metadata.cssUniversalRuleCount, comma = true)
            appendJson("cssComplexRuleCount", metadata.cssComplexRuleCount, comma = true)
            appendJson("cssUnsupportedSelectorCount", metadata.cssUnsupportedSelectorCount, comma = true)
            appendJson("cssAverageCandidateRules", metadata.cssAverageCandidateRules, comma = true)
            appendJson("cssParserFallbackCount", metadata.cssParserFallbackCount, comma = true)
            appendJson("cssIndexMismatchCount", metadata.cssIndexMismatchCount, comma = true)
            appendJson("cssLegacyVerificationSkippedCount", metadata.cssLegacyVerificationSkippedCount, comma = true)
            appendJson("cssP95CandidateRules", metadata.cssP95CandidateRules, comma = true)
            appendJson("cssSelectorMatchMs", metadata.cssSelectorMatchMs, comma = true)
            appendJson("cssHighCostSelectorCategories", metadata.cssHighCostSelectorCategories.toString(), comma = true)
            appendJson("cssEquivalenceFixtureCount", metadata.cssEquivalenceFixtureCount, comma = true)
            appendJson("cssEquivalenceMismatchCount", metadata.cssEquivalenceMismatchCount, comma = true)
            appendJson("cssEquivalenceFallbackUsedCount", metadata.cssEquivalenceFallbackUsedCount, comma = true)
            appendJson(
                "cssEquivalenceMismatchProperties",
                metadata.cssEquivalenceMismatchProperties.toString(),
                comma = true,
            )
            appendJson("sanitizerRemovedTagCount", metadata.sanitizerRemovedTagCount, comma = true)
            appendJson("sanitizerRemovedAttributeCount", metadata.sanitizerRemovedAttributeCount, comma = true)
            appendJson("sanitizerDangerousProtocolCount", metadata.sanitizerDangerousProtocolCount, comma = true)
            appendJson("sanitizerEventHandlerCount", metadata.sanitizerEventHandlerCount, comma = true)
            appendJson("sanitizerRuntimeReason", metadata.sanitizerRuntimeReason, comma = true)
            appendJson("sanitizerExistingSafetyAgreed", metadata.sanitizerExistingSafetyAgreed, comma = true)
            appendJson("mediaRequestCount", metadata.mediaRequestCount, comma = true)
            appendJson("mediaKinds", metadata.mediaKinds.toString(), comma = true)
            appendJson("mediaSafety", metadata.mediaSafety.toString(), comma = true)
            appendJson("svgRouteCount", metadata.svgRouteCount, comma = true)
            appendJson("svgRoutes", metadata.svgRoutes.toString(), comma = true)
            appendJson("svgRouteReasons", metadata.svgRouteReasons.toString(), comma = true)
            appendJson("preparedDrawCacheHitCount", metadata.preparedDrawCacheHitCount, comma = true)
            appendJson("preparedDrawCacheMissCount", metadata.preparedDrawCacheMissCount, comma = true)
            appendJson("preparedDrawCacheHitRate", metadata.preparedDrawCacheHitRate, comma = true)
            appendJson("preparedDrawCacheEvictionCount", metadata.preparedDrawCacheEvictionCount, comma = true)
            appendJson("compileParseMs", metadata.compileParseMs, comma = true)
            appendJson("compileCascadeMs", metadata.compileCascadeMs, comma = true)
            appendJson("compileDomCompileMs", metadata.compileDomCompileMs, comma = true)
            appendJson("compileRenderModelMs", metadata.compileRenderModelMs, comma = true)
            appendJson("compileOptimizerMs", metadata.compileOptimizerMs, comma = true)
            appendJson("compileTotalMs", metadata.compileTotalMs, comma = true)
            appendJson("placeholderHeightPx", metadata.placeholderHeightPx ?: 0, comma = true)
            appendJson("measuredHeightPx", metadata.measuredHeightPx ?: 0, comma = true)
            appendJson("heightCacheHit", metadata.heightCacheHit, comma = true)
            appendJson("heightCachePersistent", metadata.heightCachePersistent, comma = true)
            appendJson("heightCacheConfidence", metadata.heightCacheConfidence, comma = true)
            appendJson("heightCacheSourceRoute", metadata.heightCacheSourceRoute, comma = true)
            appendJson("measuredNativeHeight", metadata.measuredNativeHeight ?: 0, comma = true)
            appendJson("measuredWebViewHeight", metadata.measuredWebViewHeight ?: 0, comma = true)
            appendJson("heightDeltaPx", metadata.heightDeltaPx, comma = true)
            appendJson("heightDeltaRatio", metadata.heightDeltaRatio, comma = true)
            appendJson("visualDiffClass", diff.classification.name, comma = true)
            appendJson("visualDiffWidth", diff.width, comma = true)
            appendJson("visualDiffHeight", diff.height, comma = true)
            appendJson("visualDiffHeightDeltaPx", diff.heightDeltaPx, comma = true)
            appendJson("visualDiffMeanAbsolutePixelDifference", diff.meanAbsolutePixelDifference, comma = true)
            appendJson("visualDiffMismatchRatio", diff.thresholdedPixelMismatchRatio, comma = true)
            appendJson("renderTimeMs", metadata.renderTimeMs ?: 0L, comma = true)
            appendLine("  \"screenshots\": {")
            appendJson("nativePath", artifact.nativeScreenshotPath, comma = true, indent = "    ")
            appendJson("nativeExists", File(artifact.nativeScreenshotPath).exists(), comma = true, indent = "    ")
            appendJson(
                "nativeStatus",
                screenshotStatus(File(artifact.nativeScreenshotPath).exists()),
                comma = true,
                indent = "    ",
            )
            appendJson("webViewPath", artifact.webViewScreenshotPath, comma = true, indent = "    ")
            appendJson("webViewExists", File(artifact.webViewScreenshotPath).exists(), comma = true, indent = "    ")
            appendJson(
                "webViewStatus",
                screenshotStatus(File(artifact.webViewScreenshotPath).exists()),
                comma = true,
                indent = "    ",
            )
            appendJson("snapshotPath", artifact.snapshotScreenshotPath, comma = true, indent = "    ")
            appendJson("snapshotExists", File(artifact.snapshotScreenshotPath).exists(), comma = true, indent = "    ")
            appendJson(
                "snapshotStatus",
                screenshotStatus(File(artifact.snapshotScreenshotPath).exists()),
                comma = true,
                indent = "    ",
            )
            appendJson("status", "DeferredJvmMetadataOnly", comma = false, indent = "    ")
            appendLine("  },")
            appendJson("metadataLine", report.metadataLine(), comma = false)
            appendLine("}")
        }
    }

    private fun StringBuilder.appendJson(name: String, value: String, comma: Boolean, indent: String = "  ") {
        append(indent)
        append('"').append(jsonEscape(name)).append("\": ")
        append('"').append(jsonEscape(value)).append('"')
        if (comma) append(',')
        appendLine()
    }

    private fun StringBuilder.appendJson(name: String, value: Number, comma: Boolean, indent: String = "  ") {
        append(indent)
        append('"').append(jsonEscape(name)).append("\": ")
        append(value)
        if (comma) append(',')
        appendLine()
    }

    private fun StringBuilder.appendJson(name: String, value: Boolean, comma: Boolean, indent: String = "  ") {
        append(indent)
        append('"').append(jsonEscape(name)).append("\": ")
        append(value)
        if (comma) append(',')
        appendLine()
    }

    private fun jsonEscape(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }

    private fun screenshotStatus(exists: Boolean): String =
        if (exists) "Available" else "MissingDeferred"

    private data class ScreenshotEvidenceSummary(
        val artifactCount: Int,
        val missingNativeCount: Int,
        val missingWebViewCount: Int,
        val missingSnapshotCount: Int,
    ) {
        val status: String =
            if (missingNativeCount == 0 && missingWebViewCount == 0 && missingSnapshotCount == 0) {
                "Available"
            } else {
                "DeferredJvmMetadataOnly"
            }

        companion object {
            fun from(artifacts: List<RichScreenshotArtifact>): ScreenshotEvidenceSummary =
                ScreenshotEvidenceSummary(
                    artifactCount = artifacts.size,
                    missingNativeCount = artifacts.count { !File(it.nativeScreenshotPath).exists() },
                    missingWebViewCount = artifacts.count { !File(it.webViewScreenshotPath).exists() },
                    missingSnapshotCount = artifacts.count { !File(it.snapshotScreenshotPath).exists() },
                )
        }
    }
}
