# Rich Render V2 Development Requirements: RichContentAst And TextFlow

Date: 2026-05-29

Depends on:

- V1 `RichRenderPlan` report-only infrastructure.

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

Introduce the first native architecture optimization after V1:

```text
HTML / Markdown / Protocol
  -> RichContentAst
  -> TextFlow candidate analysis
  -> optional RichTextFlowBlock render optimization
```

V2 is intended to reduce Compose node count for text-heavy rich HTML while improving browser-like inline text fidelity.

The product goal remains fidelity-first. TextFlow must not swallow layout-heavy cards, buttons, tables, SVG, images, or browser-only visual effects.

## Required Scope Split

V2 must be implemented in two internal stages.

### V2A: Report-Only RichContentAst

Add canonical AST models and converters, but do not change visible rendering.

### V2B: Feature-Flagged TextFlow Rendering

Add `RichTextFlowBlock` and renderer for safe text-heavy cases. It may be enabled behind an internal flag or guarded by strict capability checks.

If V2B cannot be safely completed in one pass, V2A is still valuable and can land alone.

## Non-Goals

- Do not replace `RichHtmlCompiler`.
- Do not rewrite CSS cascade.
- Do not replace snapshot/dynamic WebView fallback.
- Do not route layout-heavy cards into TextFlow.
- Do not render tables/SVG/images/buttons as plain text.
- Do not log raw message text or raw HTML.
- Do not change `MessageTextBlocks` behavior unless required for metadata only.

## V2A Required Models

Create suggested file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/message/RichContentAst.kt
```

Suggested sealed model:

```kotlin
internal data class RichContentAst(
    val id: String,
    val root: RichContentNode,
    val stats: RichContentAstStats,
)

internal sealed interface RichContentNode {
    val stablePath: String
    val sourceKind: RichContentSourceKind
}

internal enum class RichContentSourceKind {
    Html,
    Markdown,
    Protocol,
}
```

Minimum node types:

- `Document`
- `Container`
- `Paragraph`
- `TextRun`
- `StyledTextRun`
- `LinkRun`
- `InlineCodeRun`
- `ListBlock`
- `ListItem`
- `ActionButton`
- `Image`
- `Svg`
- `Table`
- `Details`
- `Formula`
- `BrowserOnly`
- `Unsupported`

Stats:

```kotlin
internal data class RichContentAstStats(
    val sourceNodeCount: Int,
    val astNodeCount: Int,
    val textRunCount: Int,
    val paragraphCount: Int,
    val textFlowCandidateCount: Int,
    val blockedTextFlowCount: Int,
    val actionCount: Int,
    val mediaCount: Int,
    val tableCount: Int,
    val svgCount: Int,
    val browserOnlyCount: Int,
)
```

Important:

- AST nodes must not store raw full HTML.
- Text nodes necessarily contain display text for rendering, but telemetry/report snapshots must not serialize them.
- Stable paths should be structural, for example `r0/c2/p1`, not text-derived.

## V2A Required Builder

Add:

```kotlin
internal fun buildRichContentAstFromHtml(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
): RichContentAst
```

V2A builder rules:

- Use Jsoup parse safely.
- Preserve source order.
- Classify obvious browser-only nodes:
  - `script`
  - `canvas`
  - `iframe`
  - `video`
  - `audio`
  - `object`
  - `embed`
- Classify visual-only complex nodes:
  - SVG complex features
  - filter/mask/clip-path/mix-blend/backdrop-filter containers
- Mark text-flow-eligible nodes only when they do not contain layout-heavy style.
- Preserve action buttons as `ActionButton`, not text.
- Preserve tables/SVG/images/details as dedicated nodes.

V2A should integrate with V1:

- Extend `RichRenderPlan` with optional AST stats or an `astStats` field if convenient.
- Extend fidelity/seed report with AST stats.
- Do not make route decisions from AST yet unless report-only.

## V2B Required Render Model

Add to `RichHtmlRenderModel.kt`:

```kotlin
internal data class RichTextFlowBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val paragraphs: List<RichTextFlowParagraph>,
) : RichBlock
```

Suggested paragraph model:

```kotlin
internal data class RichTextFlowParagraph(
    val content: AnnotatedString,
    val inlineMath: List<InlineMathRun> = emptyList(),
    val inlinePaints: List<InlineTextPaintRun> = emptyList(),
    val listMarker: String? = null,
)
```

The exact shape may change, but V2B must preserve:

- inline styles
- links where current native renderer supports them
- inline code
- bold/italic/underline/delete/sup/sub behavior where already supported
- list markers where already supported
- inline math where already supported

## V2B Required Optimizer

Add:

```kotlin
internal object RichTextFlowOptimizer {
    fun optimize(model: RichHtmlRenderModel): RichHtmlRenderModel
}
```

V2B safe conversion rules:

Eligible:

- containers whose children are only text/paragraph/list/simple inline containers
- paragraph-heavy HTML
- span-heavy HTML
- simple lists without nested rich layout

Blocked:

- button/action
- image
- table
- svg
- details with interactive state
- absolute/fixed/sticky positioned descendants
- flex/grid containers
- background image/layers
- filter/backdrop-filter/mask/clip-path/mix-blend
- nontrivial border/background/shadow containers that visually matter
- inline boxes with explicit dimensions unless already safely represented

TextFlow optimizer must be conservative. If uncertain, leave the existing model unchanged.

## V2B Required Renderer

Update `RichHtmlRenderer.kt`:

- Add `RichTextFlowBlockView`.
- Render paragraphs using stable `Text` / `AnnotatedString`.
- Keep paragraph/list spacing compatible with existing browser baseline.
- Do not introduce nested cards.
- Do not use WebView.

The renderer should be simple and measurable.

## Required Telemetry

Extend V1 plan telemetry with:

- `astNodeCount`
- `textRunCount`
- `paragraphCount`
- `textFlowCandidateCount`
- `blockedTextFlowCount`
- `textFlowAppliedCount`
- `renderNodeReductionEstimate`

Do not log raw text.

## Required Tests

Add:

```text
app/src/test/java/me/rerere/rikkahub/ui/components/message/RichContentAstTest.kt
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichTextFlowOptimizerTest.kt
```

Minimum AST tests:

1. HTML paragraphs/spans preserve source order.
2. Action buttons become `ActionButton`, not plain text.
3. Tables/images/SVG become dedicated nodes.
4. Script/canvas become `BrowserOnly`.
5. Text-flow candidate stats increase for span-heavy content.
6. Layout-heavy style blocks TextFlow candidacy.
7. AST telemetry/report output does not include raw body text.
8. Builder is deterministic.

Minimum optimizer tests:

1. Simple paragraph model converts to `RichTextFlowBlock`.
2. Span-heavy text reduces block count.
3. Button-containing model does not convert the button to text.
4. Table/SVG/image models are not flattened.
5. Visual-heavy container is not flattened.
6. Optimized model has same id or a traceable id suffix.
7. Existing unsupported reasons are preserved.
8. `countRichRenderBlocks` understands `RichTextFlowBlock`.

## Required Validation Commands

Run new tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichContentAstTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichTextFlowOptimizerTest"
```

Run existing related tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

Compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

## Acceptance Criteria

Codex acceptance will check:

- V2A AST is pure and deterministic.
- V2A report/telemetry does not leak raw content.
- V2B, if implemented, is conservative and feature-flagged or strictly gated.
- TextFlow never swallows buttons, tables, images, SVG, details, or browser-only content.
- Existing native/snapshot/dynamic route behavior remains stable unless explicitly guarded.
- Render block count decreases on text-heavy fixtures.
- Existing rich HTML quality/fidelity tests still pass.

## Handoff Checklist

Developer should provide:

- Files changed.
- Whether V2A only or V2A+V2B was completed.
- Example AST stats for a paragraph-heavy card.
- Example blocked TextFlow reason for a visual-heavy card.
- Before/after render block count for at least one fixture.
- Test outputs.
- Any uncertainty about flattening eligibility.

