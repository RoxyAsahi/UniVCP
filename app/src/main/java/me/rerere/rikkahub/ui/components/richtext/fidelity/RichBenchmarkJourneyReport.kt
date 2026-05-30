package me.rerere.rikkahub.ui.components.richtext.fidelity

internal enum class RichBenchmarkJourneyKind {
    Macrobenchmark,
    BaselineProfile,
}

internal enum class RichBenchmarkEvidenceStatus {
    Planned,
    DeferredNoDevice,
    Measured,
}

internal data class RichBenchmarkJourney(
    val name: String,
    val kind: RichBenchmarkJourneyKind,
    val required: Boolean,
    val upwardHistory: Boolean = false,
    val baselineProfileCovered: Boolean = false,
    val signals: Set<String> = emptySet(),
)

internal data class RichBenchmarkJourneyReport(
    val journeys: List<RichBenchmarkJourney>,
    val evidenceStatus: RichBenchmarkEvidenceStatus,
) {
    val requiredJourneyCount: Int get() = journeys.count { it.required }
    val missingRequiredJourneyNames: List<String>
        get() = journeys.filter { it.required && it.signals.isEmpty() }.map { it.name }
    val upwardHistoryJourneyCount: Int get() = journeys.count { it.upwardHistory }
    val baselineProfileCoveredCount: Int get() = journeys.count { it.baselineProfileCovered }

    fun summaryLine(): String = listOf(
        "benchmarkEvidence=${evidenceStatus.name}",
        "benchmarkJourneyCount=${journeys.size}",
        "requiredBenchmarkJourneys=$requiredJourneyCount",
        "upwardHistoryJourneys=$upwardHistoryJourneyCount",
        "baselineProfileCovered=$baselineProfileCoveredCount",
        "missingRequiredBenchmarkJourneys=$missingRequiredJourneyNames",
        "benchmarkJourneyNames=${journeys.map { it.name }}",
    ).joinToString(" ")
}

internal object RichBenchmarkJourneyManifest {
    private val frameSignals = setOf(
        "FrameTiming",
        "JankyFrames",
        "HeightDelta",
        "CompileQueueWait",
        "SnapshotQueueWait",
        "InlineWebViewActiveCount",
    )

    fun deferredNoDeviceReport(): RichBenchmarkJourneyReport = RichBenchmarkJourneyReport(
        journeys = listOf(
            RichBenchmarkJourney(
                name = "RichChatTextHeavyScrollBenchmark",
                kind = RichBenchmarkJourneyKind.Macrobenchmark,
                required = true,
                signals = frameSignals,
            ),
            RichBenchmarkJourney(
                name = "RichChatMixedSnapshotIslandBenchmark",
                kind = RichBenchmarkJourneyKind.Macrobenchmark,
                required = true,
                signals = frameSignals,
            ),
            RichBenchmarkJourney(
                name = "RichChatDynamicInlineWebViewBenchmark",
                kind = RichBenchmarkJourneyKind.Macrobenchmark,
                required = true,
                signals = frameSignals + "InlineWebViewPhase",
            ),
            RichBenchmarkJourney(
                name = "RichChatSvgTableCardBenchmark",
                kind = RichBenchmarkJourneyKind.Macrobenchmark,
                required = true,
                signals = frameSignals,
            ),
            RichBenchmarkJourney(
                name = "RichChatHistoryUpwardScrollBenchmark",
                kind = RichBenchmarkJourneyKind.Macrobenchmark,
                required = true,
                upwardHistory = true,
                signals = frameSignals,
            ),
            RichBenchmarkJourney(
                name = "BaselineProfileRichChatJourney",
                kind = RichBenchmarkJourneyKind.BaselineProfile,
                required = true,
                baselineProfileCovered = true,
                signals = setOf("Startup", "RichChatScroll", "RichHistoryScroll"),
            ),
        ),
        evidenceStatus = RichBenchmarkEvidenceStatus.DeferredNoDevice,
    )
}
