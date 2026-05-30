package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssParser
import me.rerere.rikkahub.ui.components.richtext.compiler.RichVisualHintAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichCssParserTest {
    @Test
    fun `parses color functions and named colors`() {
        val declarations = RichCssParser.parseDeclarationMap(
            "color: rgba(255, 0, 0, .5); background-color: hsl(210 50% 40%); border-color: rebeccapurple;"
        )

        assertEquals("rgba(255, 0, 0, .5)", declarations["color"])
        assertEquals("hsl(210 50% 40%)", declarations["background-color"])
        assertEquals("rebeccapurple", declarations["border-color"])
    }

    @Test
    fun `parses background and border shorthands`() {
        val declarations = RichCssParser.parseDeclarationMap(
            "background: url(https://example.test/a.png) no-repeat right 8px top 4px / 24px 24px, linear-gradient(#000,#fff); border: 2px dashed #38bdf8;"
        )

        assertTrue(declarations["background"].orEmpty().contains("linear-gradient"))
        assertEquals("2px dashed #38bdf8", declarations["border"])
        assertTrue(RichVisualHintAnalyzer.analyzeDeclarations(declarations).contains(RichVisualHint.BackgroundExtraLayer))
    }

    @Test
    fun `parses filter and animation tokens`() {
        val declarations = RichCssParser.parseDeclarationMap(
            "filter: blur(4px) brightness(.9); backdrop-filter: saturate(1.2); animation: fade .4s ease forwards; transition: transform .2s ease;"
        )
        val hints = RichVisualHintAnalyzer.analyzeDeclarations(declarations)

        assertEquals("blur(4px) brightness(.9)", declarations["filter"])
        assertTrue(hints.contains(RichVisualHint.CssFilter))
        assertTrue(hints.contains(RichVisualHint.CssBackdropFilter))
        assertTrue(hints.contains(RichVisualHint.CssAnimation))
        assertTrue(hints.contains(RichVisualHint.CssTransition))
    }

    @Test
    fun `visual hint analyzer ignores explicit none values`() {
        val declarations = RichCssParser.parseDeclarationMap(
            "filter: none; backdrop-filter: none; mask-image: none; clip-path: none; animation: none; transition: none;"
        )
        val hints = RichVisualHintAnalyzer.analyzeDeclarations(declarations)

        assertFalse(hints.contains(RichVisualHint.CssFilter))
        assertFalse(hints.contains(RichVisualHint.CssBackdropFilter))
        assertFalse(hints.contains(RichVisualHint.CssMask))
        assertFalse(hints.contains(RichVisualHint.CssClipPath))
        assertFalse(hints.contains(RichVisualHint.CssAnimation))
        assertFalse(hints.contains(RichVisualHint.CssTransition))
    }

    @Test
    fun `declaration equivalence is stable for supported declarations`() {
        val report = RichCssParser.declarationEquivalence(
            listOf(
                "color: red; background: linear-gradient(#000,#fff); border: 1px solid #38bdf8;",
                "filter: blur(4px); backdrop-filter: saturate(1.2); transform: translateX(4px);",
            )
        )

        assertEquals(2, report.fixtureCount)
        assertEquals(0, report.mismatchCount)
        assertEquals(emptyMap<String, Int>(), report.mismatchProperties)
    }

    @Test
    fun `declaration equivalence counts guarded fallback without raw values`() {
        val secret = "css-equivalence-secret"
        val report = RichCssParser.declarationEquivalence(
            listOf(
                "--accent: $secret; color: var(--accent);",
                "font: 700 14px/1.4 Inter, sans-serif;",
            )
        )

        assertEquals(2, report.fixtureCount)
        assertEquals(2, report.fallbackUsedCount)
        assertEquals(0, report.mismatchCount)
        assertFalse(report.toString().contains(secret))
        assertFalse(report.toString().contains("Inter"))
    }
}
