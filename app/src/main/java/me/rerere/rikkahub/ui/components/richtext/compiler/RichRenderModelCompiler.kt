package me.rerere.rikkahub.ui.components.richtext.compiler

import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompileOptions
import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompiler
import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModel

internal object RichRenderModelCompiler {
    fun compile(html: String, options: RichHtmlCompileOptions = RichHtmlCompileOptions()): RichHtmlRenderModel {
        return RichHtmlCompiler.compile(html, options)
    }
}
