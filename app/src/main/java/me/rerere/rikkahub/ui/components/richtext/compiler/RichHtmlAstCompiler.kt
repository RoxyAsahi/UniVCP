package me.rerere.rikkahub.ui.components.richtext.compiler

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal object RichHtmlAstCompiler {
    fun parseBody(html: String): Document = Jsoup.parseBodyFragment(html)
}
