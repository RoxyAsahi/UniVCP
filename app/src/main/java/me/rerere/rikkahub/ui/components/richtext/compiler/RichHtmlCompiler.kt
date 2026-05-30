package me.rerere.rikkahub.ui.components.richtext.compiler

import me.rerere.rikkahub.ui.components.richtext.RichHtmlCompileOptions
import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModel

internal object RichHtmlCompilerFacade {
    fun compile(html: String, options: RichHtmlCompileOptions = RichHtmlCompileOptions()): RichHtmlRenderModel {
        return RichRenderModelCompiler.compile(html, options)
    }
}
