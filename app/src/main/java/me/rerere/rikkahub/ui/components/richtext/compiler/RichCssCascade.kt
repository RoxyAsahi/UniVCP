package me.rerere.rikkahub.ui.components.richtext.compiler

import me.rerere.rikkahub.ui.components.render.renderTextCacheKey
import org.jsoup.nodes.Element

internal data class RichCssRule(
    val selector: String,
    val declarations: Map<String, String>,
    val order: Int,
) {
    val bucket: RichCssSelectorBucket = RichCssSelectorMatcher.bucket(selector)
    val selectorHash: String = renderTextCacheKey(selector)
    val key: RichCssRuleKey = RichCssRuleKey.from(RichCssSelectorMatcher.indexKey(selector))
    val highCostCategories: Set<RichCssHighCostSelectorCategory> =
        RichCssSelectorMatcher.highCostCategories(selector)
    val flags: Set<RichCssRuleFlag> = buildSet {
        if (bucket == RichCssSelectorBucket.Unsupported) add(RichCssRuleFlag.UnsupportedSelector)
        if (bucket == RichCssSelectorBucket.Complex) add(RichCssRuleFlag.ComplexSelector)
        if (highCostCategories.isNotEmpty()) add(RichCssRuleFlag.HighCostSelector)
    }
}

internal sealed interface RichCssRuleKey {
    data class Id(val value: String) : RichCssRuleKey
    data class ClassName(val value: String) : RichCssRuleKey
    data class TagName(val value: String) : RichCssRuleKey
    data class AttributeName(val value: String) : RichCssRuleKey
    data class Pseudo(val value: String) : RichCssRuleKey
    data object Universal : RichCssRuleKey
    data object Complex : RichCssRuleKey
    data object Unsupported : RichCssRuleKey

    companion object {
        fun from(key: RichCssSelectorIndexKey): RichCssRuleKey = when (key) {
            is RichCssSelectorIndexKey.Id -> Id(key.value)
            is RichCssSelectorIndexKey.ClassName -> ClassName(key.value)
            is RichCssSelectorIndexKey.TagName -> TagName(key.value)
            is RichCssSelectorIndexKey.AttributeName -> AttributeName(key.value)
            is RichCssSelectorIndexKey.Pseudo -> Pseudo(key.value)
            RichCssSelectorIndexKey.Universal -> Universal
            RichCssSelectorIndexKey.Complex -> Complex
            RichCssSelectorIndexKey.Unsupported -> Unsupported
        }
    }
}

internal enum class RichCssRuleFlag {
    ComplexSelector,
    UnsupportedSelector,
    HighCostSelector,
}

internal data class RichCssRuleIndexStats(
    val ruleCount: Int,
    val selectorCount: Int,
    val idRuleCount: Int,
    val classRuleCount: Int,
    val tagRuleCount: Int,
    val attrRuleCount: Int,
    val pseudoRuleCount: Int,
    val universalRuleCount: Int,
    val complexRuleCount: Int,
    val unsupportedRuleCount: Int,
    val highCostSelectorCategories: Map<String, Int>,
)

internal data class RichCssRuleIndex(
    val idRules: Map<String, List<RichCssRule>>,
    val classRules: Map<String, List<RichCssRule>>,
    val tagRules: Map<String, List<RichCssRule>>,
    val attrRules: Map<String, List<RichCssRule>>,
    val pseudoRules: Map<String, List<RichCssRule>>,
    val universalRules: List<RichCssRule>,
    val complexRules: List<RichCssRule>,
    val unsupportedRules: List<RichCssRule>,
    val stats: RichCssRuleIndexStats,
    val unsupportedSelectors: Int,
) {
    fun candidateRules(element: Element): List<RichCssRule> {
        val result = linkedSetOf<RichCssRule>()
        element.id().takeIf { it.isNotBlank() }?.let { id -> idRules[id]?.let(result::addAll) }
        element.classNames().forEach { className -> classRules[className]?.let(result::addAll) }
        tagRules[element.tagName().lowercase()]?.let(result::addAll)
        element.attributes().forEach { attr -> attrRules[attr.key.lowercase()]?.let(result::addAll) }
        if (element.tagName().equals("html", ignoreCase = true)) {
            pseudoRules["root"]?.let(result::addAll)
        }
        result += universalRules
        result += complexRules.filter { RichCssSelectorMatcher.matches(element, it.selector) }
        return result.sortedBy { it.order }
    }
}

internal object RichCssCascade {
    fun index(rules: List<RichCssRule>): RichCssRuleIndex {
        val idRules = linkedMapOf<String, MutableList<RichCssRule>>()
        val classRules = linkedMapOf<String, MutableList<RichCssRule>>()
        val tagRules = linkedMapOf<String, MutableList<RichCssRule>>()
        val attrRules = linkedMapOf<String, MutableList<RichCssRule>>()
        val pseudoRules = linkedMapOf<String, MutableList<RichCssRule>>()
        val universalRules = mutableListOf<RichCssRule>()
        val complexRules = mutableListOf<RichCssRule>()
        val unsupportedRules = mutableListOf<RichCssRule>()
        rules.forEach { rule ->
            when (val key = RichCssSelectorMatcher.indexKey(rule.selector)) {
                is RichCssSelectorIndexKey.Id -> idRules.getOrPut(key.value) { mutableListOf() } += rule
                is RichCssSelectorIndexKey.ClassName -> classRules.getOrPut(key.value) { mutableListOf() } += rule
                is RichCssSelectorIndexKey.TagName -> tagRules.getOrPut(key.value) { mutableListOf() } += rule
                is RichCssSelectorIndexKey.AttributeName -> attrRules.getOrPut(key.value) { mutableListOf() } += rule
                is RichCssSelectorIndexKey.Pseudo -> pseudoRules.getOrPut(key.value) { mutableListOf() } += rule
                RichCssSelectorIndexKey.Universal -> universalRules += rule
                RichCssSelectorIndexKey.Complex -> complexRules += rule
                RichCssSelectorIndexKey.Unsupported -> unsupportedRules += rule
            }
        }
        val stats = RichCssRuleIndexStats(
            ruleCount = rules.size,
            selectorCount = rules.size,
            idRuleCount = idRules.values.sumOf { it.size },
            classRuleCount = classRules.values.sumOf { it.size },
            tagRuleCount = tagRules.values.sumOf { it.size },
            attrRuleCount = attrRules.values.sumOf { it.size },
            pseudoRuleCount = pseudoRules.values.sumOf { it.size },
            universalRuleCount = universalRules.size,
            complexRuleCount = complexRules.size,
            unsupportedRuleCount = unsupportedRules.size,
            highCostSelectorCategories = rules
                .flatMap { rule -> rule.highCostCategories.map { it.name } }
                .groupingBy { it }
                .eachCount(),
        )
        return RichCssRuleIndex(
            idRules = idRules,
            classRules = classRules,
            tagRules = tagRules,
            attrRules = attrRules,
            pseudoRules = pseudoRules,
            universalRules = universalRules,
            complexRules = complexRules,
            unsupportedRules = unsupportedRules,
            stats = stats,
            unsupportedSelectors = stats.unsupportedRuleCount,
        )
    }
}
