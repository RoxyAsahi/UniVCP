package me.rerere.rikkahub.ui.components.richtext.compiler

import me.rerere.rikkahub.ui.components.richtext.RichCssDeclarationParser

internal data class RichCssDeclaration(
    val property: String,
    val value: String,
)

internal data class RichCssParserReport(
    val declarationCount: Int,
    val fallbackUsed: Boolean,
)

internal data class RichCssParseResult(
    val declarations: Map<String, String>,
    val report: RichCssParserReport,
)

internal data class RichCssDeclarationEquivalenceReport(
    val fixtureCount: Int,
    val mismatchCount: Int,
    val fallbackUsedCount: Int,
    val mismatchProperties: Map<String, Int>,
) {
    companion object {
        val Empty = RichCssDeclarationEquivalenceReport(
            fixtureCount = 0,
            mismatchCount = 0,
            fallbackUsedCount = 0,
            mismatchProperties = emptyMap(),
        )
    }
}

internal object RichCssParser {
    fun parseDeclarations(style: String): List<RichCssDeclaration> {
        return RichCssDeclarationParser.parse(style)
            .map { (property, value) -> RichCssDeclaration(property = property, value = value) }
    }

    fun parseDeclarationMap(style: String): Map<String, String> {
        return parseDeclarations(style).associate { it.property to it.value }
    }

    fun parseDeclarationMapWithReport(style: String): RichCssParseResult {
        val parsed = RichCssDeclarationParser.parseWithReport(style)
        return RichCssParseResult(
            declarations = parsed.declarations,
            report = RichCssParserReport(
                declarationCount = parsed.declarations.size,
                fallbackUsed = parsed.fallbackUsed,
            ),
        )
    }

    fun report(style: String): RichCssParserReport {
        val previous = RichCssDeclarationParser.forceFallbackForTest
        val normal: Map<String, String>
        val fallback: Map<String, String>
        try {
            RichCssDeclarationParser.forceFallbackForTest = false
            normal = RichCssDeclarationParser.parse(style)
            RichCssDeclarationParser.forceFallbackForTest = true
            fallback = RichCssDeclarationParser.parse(style)
        } finally {
            RichCssDeclarationParser.forceFallbackForTest = previous
        }
        return RichCssParserReport(
            declarationCount = normal.size,
            fallbackUsed = normal != fallback,
        )
    }

    fun declarationEquivalence(styles: Iterable<String>): RichCssDeclarationEquivalenceReport {
        val mismatchProperties = linkedMapOf<String, Int>()
        var fixtureCount = 0
        var mismatchCount = 0
        var fallbackUsedCount = 0
        styles.forEach { style ->
            if (style.isBlank()) return@forEach
            fixtureCount += 1
            val comparison = compareNormalAndFallback(style)
            if (comparison.normalReport.fallbackUsed) fallbackUsedCount += 1
            if (comparison.normal != comparison.fallback) {
                mismatchCount += 1
                val keys = comparison.normal.keys + comparison.fallback.keys
                keys.sorted().forEach { key ->
                    if (comparison.normal[key] != comparison.fallback[key]) {
                        mismatchProperties[key] = (mismatchProperties[key] ?: 0) + 1
                    }
                }
            }
        }
        return RichCssDeclarationEquivalenceReport(
            fixtureCount = fixtureCount,
            mismatchCount = mismatchCount,
            fallbackUsedCount = fallbackUsedCount,
            mismatchProperties = mismatchProperties,
        )
    }

    private fun compareNormalAndFallback(style: String): ParserComparison {
        val previous = RichCssDeclarationParser.forceFallbackForTest
        try {
            RichCssDeclarationParser.forceFallbackForTest = false
            val normalResult = RichCssDeclarationParser.parseWithReport(style)
            RichCssDeclarationParser.forceFallbackForTest = true
            val fallback = RichCssDeclarationParser.parse(style)
            return ParserComparison(
                normal = normalResult.declarations,
                fallback = fallback,
                normalReport = RichCssParserReport(
                    declarationCount = normalResult.declarations.size,
                    fallbackUsed = normalResult.fallbackUsed,
                ),
            )
        } finally {
            RichCssDeclarationParser.forceFallbackForTest = previous
        }
    }
}

private data class ParserComparison(
    val normal: Map<String, String>,
    val fallback: Map<String, String>,
    val normalReport: RichCssParserReport,
)
