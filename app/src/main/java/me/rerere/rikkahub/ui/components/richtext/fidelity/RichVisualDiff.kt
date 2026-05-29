package me.rerere.rikkahub.ui.components.richtext.fidelity

import kotlin.math.abs

internal enum class RichVisualDiffClass {
    Pass,
    MinorDifference,
    NeedsReview,
    KnownUnsupported,
    DynamicNotComparable,
    ReferenceFailed,
}

internal data class RichPixelBuffer(
    val width: Int,
    val height: Int,
    val argb: IntArray,
) {
    init {
        require(argb.size == width * height)
    }
}

internal data class RichVisualDiffMetrics(
    val width: Int,
    val height: Int,
    val heightDeltaPx: Int,
    val meanAbsolutePixelDifference: Float,
    val thresholdedPixelMismatchRatio: Float,
    val classification: RichVisualDiffClass,
)

internal object RichVisualDiff {
    fun compare(
        native: RichPixelBuffer?,
        reference: RichPixelBuffer?,
        dynamic: Boolean = false,
        knownUnsupported: Boolean = false,
    ): RichVisualDiffMetrics {
        if (dynamic) return empty(RichVisualDiffClass.DynamicNotComparable)
        if (knownUnsupported) return empty(RichVisualDiffClass.KnownUnsupported)
        if (native == null || reference == null) return empty(RichVisualDiffClass.ReferenceFailed)
        val width = minOf(native.width, reference.width)
        val height = minOf(native.height, reference.height)
        var total = 0L
        var mismatch = 0
        repeat(height) { y ->
            repeat(width) { x ->
                val a = native.argb[y * native.width + x]
                val b = reference.argb[y * reference.width + x]
                val diff = argbDistance(a, b)
                total += diff
                if (diff > 30) mismatch += 1
            }
        }
        val pixels = (width * height).coerceAtLeast(1)
        val mean = total.toFloat() / pixels
        val ratio = mismatch.toFloat() / pixels
        val classification = when {
            mean <= 2f && ratio <= 0.005f && abs(native.height - reference.height) <= 2 -> RichVisualDiffClass.Pass
            mean <= 12f && ratio <= 0.03f && abs(native.height - reference.height) <= 24 -> RichVisualDiffClass.MinorDifference
            else -> RichVisualDiffClass.NeedsReview
        }
        return RichVisualDiffMetrics(
            width = width,
            height = height,
            heightDeltaPx = native.height - reference.height,
            meanAbsolutePixelDifference = mean,
            thresholdedPixelMismatchRatio = ratio,
            classification = classification,
        )
    }

    private fun empty(classification: RichVisualDiffClass): RichVisualDiffMetrics {
        return RichVisualDiffMetrics(0, 0, 0, 0f, 0f, classification)
    }

    private fun argbDistance(a: Int, b: Int): Int {
        val ar = (a shr 16) and 0xff
        val ag = (a shr 8) and 0xff
        val ab = a and 0xff
        val br = (b shr 16) and 0xff
        val bg = (b shr 8) and 0xff
        val bb = b and 0xff
        return (abs(ar - br) + abs(ag - bg) + abs(ab - bb)) / 3
    }
}
