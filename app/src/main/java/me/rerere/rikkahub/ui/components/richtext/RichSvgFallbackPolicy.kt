package me.rerere.rikkahub.ui.components.richtext

internal enum class RichSvgRoute {
    NativeIr,
    SnapshotIsland,
    DynamicPreview,
    AndroidSvgSpike,
}

internal data class RichSvgFallbackReport(
    val route: RichSvgRoute,
    val reason: String,
    val commandCount: Int,
    val visualHintCount: Int,
    val androidSvgAvailable: Boolean,
)

internal object RichSvgFallbackPolicy {
    private const val NativeCommandBudget = 96

    fun decide(model: RichSvgModel, sourceHtml: String? = null): RichSvgFallbackReport {
        if (containsRuntimeSvg(sourceHtml)) {
            return report(model, RichSvgRoute.DynamicPreview, "runtime-svg")
        }
        if (model.visualHints.isEmpty() && model.commands.size <= NativeCommandBudget) {
            return report(model, RichSvgRoute.NativeIr, "simple-native-ir")
        }
        val androidSvg = RichAndroidSvgSpike.tryAnalyze(sourceHtml)
        return if (androidSvg.available && androidSvg.staticSafe) {
            report(model, RichSvgRoute.AndroidSvgSpike, "android-svg-spike")
        } else {
            report(model, RichSvgRoute.SnapshotIsland, "complex-static-snapshot")
        }
    }

    fun containsRuntimeSvg(sourceHtml: String?): Boolean {
        val source = sourceHtml ?: return false
        return RUNTIME_SVG_HINT.containsMatchIn(source)
    }

    private fun report(model: RichSvgModel, route: RichSvgRoute, reason: String): RichSvgFallbackReport {
        return RichSvgFallbackReport(
            route = route,
            reason = reason,
            commandCount = model.commands.size,
            visualHintCount = model.visualHints.size,
            androidSvgAvailable = RichAndroidSvgSpike.Available,
        )
    }
}

private val RUNTIME_SVG_HINT = Regex("""<\s*(script|foreignobject)\b""", RegexOption.IGNORE_CASE)

internal data class RichAndroidSvgSpikeReport(
    val available: Boolean,
    val staticSafe: Boolean,
    val reason: String,
)

internal object RichAndroidSvgSpike {
    const val Available: Boolean = false

    fun tryAnalyze(sourceHtml: String?): RichAndroidSvgSpikeReport {
        if (!Available) {
            return RichAndroidSvgSpikeReport(available = false, staticSafe = false, reason = "adapter-not-linked")
        }
        val unsafe = sourceHtml?.contains("<script", ignoreCase = true) == true ||
            sourceHtml?.contains("foreignObject", ignoreCase = true) == true
        return RichAndroidSvgSpikeReport(available = true, staticSafe = !unsafe, reason = if (unsafe) "runtime-svg" else "static-svg")
    }
}
