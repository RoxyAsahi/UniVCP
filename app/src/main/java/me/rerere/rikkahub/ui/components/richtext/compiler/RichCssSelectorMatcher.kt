package me.rerere.rikkahub.ui.components.richtext.compiler

import org.jsoup.nodes.Element

internal enum class RichCssSelectorBucket {
    Id,
    Class,
    Tag,
    Attribute,
    Pseudo,
    Universal,
    Complex,
    Unsupported,
}

internal enum class RichCssHighCostSelectorCategory {
    UniversalRightmost,
    SubstringAttribute,
    LongDescendantChain,
    DynamicPseudo,
    UnsupportedPseudoElement,
    HasSelector,
    ComplexCombinator,
}

internal sealed interface RichCssSelectorIndexKey {
    data class Id(val value: String) : RichCssSelectorIndexKey
    data class ClassName(val value: String) : RichCssSelectorIndexKey
    data class TagName(val value: String) : RichCssSelectorIndexKey
    data class AttributeName(val value: String) : RichCssSelectorIndexKey
    data class Pseudo(val value: String) : RichCssSelectorIndexKey
    data object Universal : RichCssSelectorIndexKey
    data object Complex : RichCssSelectorIndexKey
    data object Unsupported : RichCssSelectorIndexKey
}

internal object RichCssSelectorMatcher {
    fun bucket(selector: String): RichCssSelectorBucket {
        return when (indexKey(selector)) {
            is RichCssSelectorIndexKey.Id -> RichCssSelectorBucket.Id
            is RichCssSelectorIndexKey.ClassName -> RichCssSelectorBucket.Class
            is RichCssSelectorIndexKey.TagName -> RichCssSelectorBucket.Tag
            is RichCssSelectorIndexKey.AttributeName -> RichCssSelectorBucket.Attribute
            is RichCssSelectorIndexKey.Pseudo -> RichCssSelectorBucket.Pseudo
            RichCssSelectorIndexKey.Universal -> RichCssSelectorBucket.Universal
            RichCssSelectorIndexKey.Complex -> RichCssSelectorBucket.Complex
            RichCssSelectorIndexKey.Unsupported -> RichCssSelectorBucket.Unsupported
        }
    }

    fun indexKey(selector: String): RichCssSelectorIndexKey {
        val raw = selector.trim()
        if (raw.isBlank()) return RichCssSelectorIndexKey.Unsupported
        if (raw == "*") return RichCssSelectorIndexKey.Universal
        if (raw.contains("::") || raw.contains(":has", ignoreCase = true)) return RichCssSelectorIndexKey.Unsupported
        val pseudoNames = CSS_PSEUDO_SELECTOR.findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.lowercase() }
            .toList()
        if (pseudoNames.any { it != "root" }) return RichCssSelectorIndexKey.Complex
        if ("root" in pseudoNames && raw != ":root") return RichCssSelectorIndexKey.Complex
        if (CSS_COMBINATOR_HINT.containsMatchIn(raw)) return RichCssSelectorIndexKey.Complex
        CSS_ID_SELECTOR.findAll(raw).lastOrNull()?.groupValues?.getOrNull(1)?.let {
            return RichCssSelectorIndexKey.Id(it)
        }
        CSS_CLASS_SELECTOR.findAll(raw).lastOrNull()?.groupValues?.getOrNull(1)?.let {
            return RichCssSelectorIndexKey.ClassName(it)
        }
        CSS_ATTRIBUTE_SELECTOR.findAll(raw).lastOrNull()?.groupValues?.getOrNull(1)?.let {
            return RichCssSelectorIndexKey.AttributeName(it.lowercase())
        }
        CSS_TYPE_SELECTOR.find(raw)?.groupValues?.getOrNull(1)?.let {
            return RichCssSelectorIndexKey.TagName(it.lowercase())
        }
        pseudoNames.lastOrNull()?.let {
            return if (it.equals("root", ignoreCase = true)) {
                RichCssSelectorIndexKey.Pseudo(it.lowercase())
            } else {
                RichCssSelectorIndexKey.Complex
            }
        }
        return if (raw.contains("*")) RichCssSelectorIndexKey.Universal else RichCssSelectorIndexKey.Complex
    }

    fun matches(element: Element, selector: String): Boolean {
        if (bucket(selector) == RichCssSelectorBucket.Unsupported) return false
        return runCatching { element.`is`(selector) }.getOrDefault(false)
    }

    fun highCostCategories(selector: String): Set<RichCssHighCostSelectorCategory> {
        val raw = selector.trim()
        if (raw.isBlank()) return emptySet()
        val categories = linkedSetOf<RichCssHighCostSelectorCategory>()
        val rightmost = raw.split(Regex("""\s+|[>+~]""")).lastOrNull { it.isNotBlank() }.orEmpty()
        if (rightmost == "*" || rightmost.contains("*")) {
            categories += RichCssHighCostSelectorCategory.UniversalRightmost
        }
        if (CSS_SUBSTRING_ATTRIBUTE_SELECTOR.containsMatchIn(raw)) {
            categories += RichCssHighCostSelectorCategory.SubstringAttribute
        }
        if (CSS_DESCENDANT_OR_COMBINATOR.findAll(raw).count() >= 3) {
            categories += RichCssHighCostSelectorCategory.LongDescendantChain
        }
        if (raw.contains("::")) {
            categories += RichCssHighCostSelectorCategory.UnsupportedPseudoElement
        }
        if (raw.contains(":has", ignoreCase = true)) {
            categories += RichCssHighCostSelectorCategory.HasSelector
        }
        val pseudoNames = CSS_PSEUDO_SELECTOR.findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.lowercase() }
            .filterNot { it == "root" }
            .toList()
        if (pseudoNames.isNotEmpty()) {
            categories += RichCssHighCostSelectorCategory.DynamicPseudo
        }
        if (CSS_COMBINATOR_HINT.containsMatchIn(raw)) {
            categories += RichCssHighCostSelectorCategory.ComplexCombinator
        }
        return categories
    }
}

private val CSS_COMBINATOR_HINT = Regex("""(\s|[>+~])""")
private val CSS_DESCENDANT_OR_COMBINATOR = Regex("""\s+|[>+~]""")
private val CSS_ID_SELECTOR = Regex("""#([A-Za-z_][\w-]*)""")
private val CSS_CLASS_SELECTOR = Regex("""\.([A-Za-z_][\w-]*)""")
private val CSS_ATTRIBUTE_SELECTOR = Regex("""\[([A-Za-z_][\w-]*)(?:\s*[*^$|~]?=\s*(?:"[^"]*"|'[^']*'|[^\]]+))?]""")
private val CSS_SUBSTRING_ATTRIBUTE_SELECTOR = Regex("""\[[^\]]+[*^$]=[^\]]+]""")
private val CSS_TYPE_SELECTOR = Regex("""^([A-Za-z][\w-]*)""")
private val CSS_PSEUDO_SELECTOR = Regex(""":([A-Za-z-]+)""")
