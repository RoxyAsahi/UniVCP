package me.rerere.rikkahub.ui.components.richtext.compiler

import me.rerere.rikkahub.ui.components.richtext.RichVisualHint

internal object RichVisualHintAnalyzer {
    fun analyzeDeclarations(declarations: Map<String, String>): Set<RichVisualHint> {
        val hints = linkedSetOf<RichVisualHint>()
        declarations["filter"]?.takeIf { it.isMeaningfulCssValue("none") }?.let { hints += RichVisualHint.CssFilter }
        declarations["backdrop-filter"]?.takeIf { it.isMeaningfulCssValue("none") }?.let {
            hints += RichVisualHint.CssBackdropFilter
        }
        declarations["mix-blend-mode"]?.takeIf { it.isMeaningfulCssValue("normal") }?.let {
            hints += RichVisualHint.CssMixBlendMode
        }
        declarations["clip-path"]?.takeIf { it.isMeaningfulCssValue("none") }?.let { hints += RichVisualHint.CssClipPath }
        val background = declarations["background-image"] ?: declarations["background"]
        if (background != null && countExtraBackgroundLayers(background) > 0) {
            hints += RichVisualHint.BackgroundExtraLayer
        }
        listOf("mask", "mask-image", "-webkit-mask", "-webkit-mask-image").forEach { key ->
            declarations[key]?.takeIf { it.isMeaningfulCssValue("none") }?.let { hints += RichVisualHint.CssMask }
        }
        val animationValues = declarations.filterKeys { it == "animation" || it.startsWith("animation-") }.values
        if (animationValues.any { it.isNotBlank() && !it.equals("none", ignoreCase = true) }) {
            hints += RichVisualHint.CssAnimation
        }
        val transitionValues = declarations.filterKeys { it == "transition" || it.startsWith("transition-") }.values
        if (transitionValues.any { it.isNotBlank() && !it.equals("none", ignoreCase = true) }) {
            hints += RichVisualHint.CssTransition
        }
        return hints
    }

    private fun countExtraBackgroundLayers(value: String): Int =
        (splitTopLevel(value, ',').size - 1).coerceAtLeast(0)

    private fun String.isMeaningfulCssValue(defaultValue: String): Boolean =
        isNotBlank() && !equals(defaultValue, ignoreCase = true)

    private fun splitTopLevel(value: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        value.forEach { char ->
            when {
                quote != null -> {
                    current.append(char)
                    if (char == quote) quote = null
                }
                char == '"' || char == '\'' -> {
                    quote = char
                    current.append(char)
                }
                char == '(' -> {
                    depth += 1
                    current.append(char)
                }
                char == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(char)
                }
                char == delimiter && depth == 0 -> {
                    parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotBlank() }
    }
}
