package me.rerere.rikkahub.ui.components.richtext.fidelity

import me.rerere.rikkahub.ui.components.richtext.RICH_HTML_SNAPSHOT_RENDERER_VERSION
import me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCache
import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderModelVersion
import me.rerere.rikkahub.ui.components.message.RichContentDocumentSchemaVersion
import me.rerere.rikkahub.ui.components.message.RichContentTransformPipelineVersion
import me.rerere.rikkahub.ui.components.message.RichRenderPlanVersion

internal object RichRenderPlatformVersions {
    const val RichContentAst = 1
    val RichContentDocument: Int get() = RichContentDocumentSchemaVersion
    val RichContentTransformPipeline: Int get() = RichContentTransformPipelineVersion
    val RichRenderPlan: Int get() = RichRenderPlanVersion
    val RichHtmlRenderModel: Int get() = RichHtmlRenderModelVersion
    const val FidelityReportSchema = 2
    val heightCacheRendererVersion: Int get() = RichRenderHeightCache.RendererVersion
    val snapshotCacheRendererVersion: Int get() = RICH_HTML_SNAPSHOT_RENDERER_VERSION
}
