package me.rerere.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class RichChatScrollBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun richChatFastFlingBenchmark() = benchmark(repetitions = 8)

    @Test
    fun richChatLongMessageBenchmark() = benchmark(repetitions = 5)

    @Test
    fun richChatSnapshotMixBenchmark() = benchmark(repetitions = 6)

    @Test
    fun richChatHistoryScrollBenchmark() = benchmark(repetitions = 10, historyOnly = true)

    @Test
    fun RichChatTextHeavyScrollBenchmark() = benchmark(repetitions = 6)

    @Test
    fun RichChatMixedSnapshotIslandBenchmark() = benchmark(repetitions = 6)

    @Test
    fun RichChatDynamicInlineWebViewBenchmark() = benchmark(repetitions = 6)

    @Test
    fun RichChatSvgTableCardBenchmark() = benchmark(repetitions = 6)

    @Test
    fun RichChatHistoryUpwardScrollBenchmark() = benchmark(repetitions = 10, historyOnly = true)

    private fun benchmark(repetitions: Int, historyOnly: Boolean = false) {
        rule.measureRepeated(
            packageName = InstrumentationRegistry.getArguments().getString("targetAppId")
                ?: throw Exception("targetAppId not passed as instrumentation runner arg"),
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            },
            measureBlock = {
                if (historyOnly) {
                    scrollRichChatHistoryJourney(repetitions = repetitions)
                } else {
                    scrollRichChatJourney(repetitions = repetitions)
                }
            },
        )
    }
}
