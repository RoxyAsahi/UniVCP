package me.rerere.rikkahub.ui.components.richtext

import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssCascade
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssHighCostSelectorCategory
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssRule
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssRuleFlag
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssRuleKey
import me.rerere.rikkahub.ui.components.richtext.compiler.RichCssSelectorMatcher
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichCssCascadeTest {
    @Test
    fun `indexes id class tag universal and complex rules`() {
        val index = RichCssCascade.index(
            listOf(
                RichCssRule("#hero", mapOf("color" to "red"), order = 0),
                RichCssRule(".card", mapOf("padding" to "8px"), order = 1),
                RichCssRule("button", mapOf("border" to "0"), order = 2),
                RichCssRule("*", mapOf("box-sizing" to "border-box"), order = 3),
                RichCssRule(".card > button", mapOf("font-weight" to "700"), order = 4),
                RichCssRule("button.primary.active", mapOf("color" to "blue"), order = 5),
                RichCssRule("[data-kind=\"hero\"]", mapOf("opacity" to "0.9"), order = 6),
                RichCssRule(":root", mapOf("--accent" to "red"), order = 7),
                RichCssRule("div::before", mapOf("content" to "x"), order = 8),
                RichCssRule("section:has(button)", mapOf("outline" to "1px"), order = 9),
            )
        )

        assertEquals(1, index.idRules.size)
        assertEquals(2, index.classRules.size)
        assertEquals(1, index.tagRules.size)
        assertEquals(1, index.attrRules.size)
        assertEquals(1, index.pseudoRules.size)
        assertEquals(1, index.universalRules.size)
        assertEquals(1, index.complexRules.size)
        assertEquals(2, index.unsupportedSelectors)
        assertEquals(2, index.unsupportedRules.size)
        assertEquals(10, index.stats.ruleCount)
        assertEquals(2, index.stats.unsupportedRuleCount)
        assertEquals(1, index.stats.complexRuleCount)
        assertEquals(1, index.stats.attrRuleCount)
    }

    @Test
    fun `candidate selection keeps source order and excludes unsupported selectors`() {
        val document = Jsoup.parseBodyFragment(
            """<div id="hero" class="card"><button class="primary active" data-kind="hero">Go</button></div>"""
        )
        val button = document.selectFirst("button")!!
        val index = RichCssCascade.index(
            listOf(
                RichCssRule(".card", mapOf("padding" to "8px"), order = 1),
                RichCssRule("button", mapOf("border" to "0"), order = 2),
                RichCssRule("*", mapOf("box-sizing" to "border-box"), order = 3),
                RichCssRule(".card > button", mapOf("font-weight" to "700"), order = 4),
                RichCssRule("button.primary.active", mapOf("color" to "blue"), order = 5),
                RichCssRule("[data-kind=\"hero\"]", mapOf("opacity" to "0.9"), order = 6),
                RichCssRule("button::before", mapOf("content" to "x"), order = 7),
            )
        )

        val candidates = index.candidateRules(button)

        assertEquals(listOf(2, 3, 4, 5, 6), candidates.map { it.order })
        assertFalse(candidates.any { it.selector.contains("::") })
        assertTrue(candidates.any { it.declarations["font-weight"] == "700" })
        assertTrue(candidates.any { it.declarations["color"] == "blue" })
        assertTrue(candidates.any { it.declarations["opacity"] == "0.9" })
    }

    @Test
    fun `root pseudo bucket only applies to html element`() {
        val document = Jsoup.parse("""<html><body><main data-kind="hero">Body</main></body></html>""")
        val index = RichCssCascade.index(
            listOf(
                RichCssRule(":root", mapOf("--accent" to "red"), order = 1),
                RichCssRule("[data-kind]", mapOf("display" to "block"), order = 2),
            )
        )

        val htmlCandidates = index.candidateRules(document.selectFirst("html")!!)
        val mainCandidates = index.candidateRules(document.selectFirst("main")!!)

        assertTrue(htmlCandidates.any { it.selector == ":root" })
        assertFalse(mainCandidates.any { it.selector == ":root" })
        assertTrue(mainCandidates.any { it.selector == "[data-kind]" })
    }

    @Test
    fun `negation and dynamic pseudo selectors stay complex to avoid false negative indexing`() {
        val document = Jsoup.parseBodyFragment(
            """<div class="card">Visible</div><div class="card hidden">Hidden</div>"""
        )
        val visible = document.selectFirst("div.card:not(.hidden)")!!
        val hidden = document.selectFirst("div.hidden")!!
        val index = RichCssCascade.index(
            listOf(
                RichCssRule(":not(.hidden)", mapOf("opacity" to "1"), order = 1),
                RichCssRule("div:not(.hidden)", mapOf("color" to "green"), order = 2),
                RichCssRule("div:first-child", mapOf("font-weight" to "700"), order = 3),
            )
        )

        val visibleCandidates = index.candidateRules(visible)
        val hiddenCandidates = index.candidateRules(hidden)

        assertEquals(3, index.complexRules.size)
        assertTrue(visibleCandidates.any { it.selector == ":not(.hidden)" })
        assertTrue(visibleCandidates.any { it.selector == "div:not(.hidden)" })
        assertTrue(visibleCandidates.any { it.selector == "div:first-child" })
        assertFalse(hiddenCandidates.any { it.selector == ":not(.hidden)" })
        assertFalse(hiddenCandidates.any { it.selector == "div:not(.hidden)" })
    }

    @Test
    fun `high cost selector categories are metadata only`() {
        val categories = RichCssSelectorMatcher.highCostCategories(
            """section[data-name*="secret"] article.card div *:hover"""
        )

        assertTrue(RichCssHighCostSelectorCategory.SubstringAttribute in categories)
        assertTrue(RichCssHighCostSelectorCategory.UniversalRightmost in categories)
        assertTrue(RichCssHighCostSelectorCategory.LongDescendantChain in categories)
        assertTrue(RichCssHighCostSelectorCategory.DynamicPseudo in categories)
        assertFalse(categories.joinToString().contains("secret"))
    }

    @Test
    fun `indexed rules expose metadata only hash key flags and stats`() {
        val secret = "selector-secret-token"
        val rule = RichCssRule(
            selector = """section[data-name*="$secret"] article.card div *:hover""",
            declarations = mapOf("color" to "red"),
            order = 0,
        )
        val index = RichCssCascade.index(
            listOf(
                rule,
                RichCssRule("button::before", mapOf("content" to "x"), order = 1),
            )
        )
        val serialized = listOf(
            rule.selectorHash,
            rule.key.toString(),
            rule.flags.joinToString(),
            index.stats.toString(),
            index.unsupportedRules.map { it.selectorHash }.joinToString(),
        ).joinToString("\n")

        assertTrue(rule.selectorHash.isNotBlank())
        assertTrue(rule.selectorHash != rule.selector)
        assertTrue(rule.key is RichCssRuleKey.Complex)
        assertTrue(RichCssRuleFlag.ComplexSelector in rule.flags)
        assertTrue(RichCssRuleFlag.HighCostSelector in rule.flags)
        assertEquals(1, index.unsupportedRules.size)
        assertEquals(1, index.stats.highCostSelectorCategories["SubstringAttribute"])
        assertFalse(serialized.contains(secret))
        assertFalse(serialized.contains("button::before"))
    }
}
