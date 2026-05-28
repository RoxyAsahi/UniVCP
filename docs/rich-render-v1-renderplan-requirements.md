# Rich Render V1 Development Requirements: RenderPlan And Observability

Date: 2026-05-29

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

Add a report-only `RichRenderPlan` layer for every rich HTML chat block.

V1 must not change visible rendering behavior. It only makes the current routing explainable and measurable.

After V1, for any `RichHtmlCell`, debug telemetry and seed reports should answer:

- Why did this bubble choose native, snapshot, lightweight, dynamic preview, or inline WebView?
- How risky is it?
- How many source/render nodes are involved?
- Which visual fidelity gaps are present?
- Was height cache available?
- Which route would the planner recommend under current static capability rules?

## Non-Goals

- Do not replace `RichHtmlSnapshotPolicy`.
- Do not move scheduler/admission behavior yet.
- Do not implement `RichContentAst`.
- Do not implement text-flow renderer.
- Do not add new WebView rendering behavior.
- Do not change user-visible route decisions in V1.
- Do not log raw HTML, CSS, button labels, or chat text.

## Required New Model

Create a new pure Kotlin model, suggested file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/message/RichRenderPlan.kt
```

Suggested data model:

```kotlin
internal data class RichRenderPlan(
    val id: String,
    val route: RichRenderPlanRoute,
    val nativeConfidence: RichRenderNativeConfidence,
    val reason: String,
    val htmlLength: Int,
    val sourceNodeCount: Int,
    val estimatedRenderBlockCount: Int,
    val textFlowCandidateCount: Int,
    val snapshotIslandCandidateCount: Int,
    val interactiveActionCount: Int,
    val visualHints: Set<RichVisualHint>,
    val unsupported: Set<RichUnsupportedReason>,
    val riskScore: Int,
    val riskReasons: Set<String>,
    val heightCache: RichRenderHeightCacheState,
)
```

Suggested enums:

```kotlin
internal enum class RichRenderPlanRoute {
    Native,
    Snapshot,
    DynamicPreview,
    Lightweight,
    InlineWebView,
}

internal enum class RichRenderNativeConfidence {
    High,
    Medium,
    Low,
    BrowserRequired,
}

internal enum class RichRenderHeightCacheState {
    Unknown,
    Miss,
    Hit,
}
```

The exact names may be adjusted to fit local style, but the fields above are required in spirit.

## Required Builder

Add a pure builder function:

```kotlin
internal fun buildRichRenderPlan(
    html: String,
    analysis: RichHtmlAnalysis = analyzeRichHtml(html),
    risk: RenderRiskScore = RenderRiskScore.fromHtml(html, analysis),
    model: RichHtmlRenderModel? = null,
    heightCacheState: RichRenderHeightCacheState = RichRenderHeightCacheState.Unknown,
): RichRenderPlan
```

V1 behavior:

- If `analysis.kind == ComplexDynamic`, plan route should be `DynamicPreview`.
- If `risk.route == DynamicPreview`, plan route should be `DynamicPreview`.
- If `risk.route == Snapshot`, plan route should be `Snapshot`.
- If `RichHtmlSnapshotPolicy.beforeCompile(analysis)` says snapshot, plan route should be `Snapshot`.
- If a compiled `model` is provided:
  - include `model.visualHints`
  - include `model.unsupported`
  - apply `RichHtmlSnapshotPolicy.afterCompile(analysis, model)` to update route/reason
- Otherwise:
  - estimate node counts and candidates from HTML only
  - do not compile synchronously just to build a plan

Important:

- V1 planner must be side-effect free.
- It must not start compile jobs.
- It must not touch WebView.
- It must not read Compose state.

## Required Estimators

Add lightweight estimators:

### Source Node Count

Count HTML tags safely with regex or Jsoup if already parsed by caller. V1 may use regex because this is report-only, but avoid full compile.

### Estimated Render Block Count

Approximate:

- text-heavy simple paragraphs/spans -> lower count
- tables, SVG, images, buttons, details -> higher count
- containers with layout/display styles -> higher count

This does not need to be perfect. It only needs to be deterministic and useful for trend tracking.

### Text Flow Candidate Count

Count nodes likely eligible for future `RichTextFlowBlock`:

- `p`
- `span`
- `b/strong`
- `i/em`
- `u`
- `code`
- `a`
- `li`
- text-heavy `div` without layout/position/background-heavy style

### Snapshot Island Candidate Count

Count visual-only features that may become subtree snapshots in V4:

- complex SVG tags/features
- `filter`
- `backdrop-filter`
- `mask`
- `mix-blend-mode`
- complex `clip-path`
- multi-background
- unsupported SVG defs/use/symbol/filter/mask/foreignObject

### Interactive Action Count

Count likely native-preserved actions:

- `<button`
- `data-input`
- `data-send`
- `onclick="input(...)"`

## Required Telemetry

Extend:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlRenderTelemetry.kt
```

Add a debug-only record method, suggested:

```kotlin
internal fun recordRichRenderPlan(plan: RichRenderPlan)
```

It must log/report only:

- digest id
- route
- confidence
- reason
- counts
- visual hint names
- unsupported names
- risk score/reasons
- height cache state

It must not log:

- raw HTML
- raw CSS
- preview text
- button label
- chat message body

## Required Integration Points

### In `RichHtmlBubbleBlock`

Compute and record a report-only plan near existing `analysis`, `risk`, `renderId`, and height cache logic.

Important:

- Do not let the plan drive behavior in V1.
- Existing snapshot/native/dynamic logic remains authoritative.
- Plan should be recomputed only when relevant stable inputs change.

### In Seed/Fidelity Tests

Extend existing seed/fidelity report output to include:

- plan route
- confidence
- reason
- source node count
- estimated render block count
- text-flow candidate count
- snapshot island candidate count
- interactive action count
- visual hint count
- unsupported count

Suggested existing tests to update or add alongside:

- `RichHtmlFidelityReportTest`
- `RenderSeedFixtureTest`

## Required Unit Tests

Add:

```text
app/src/test/java/me/rerere/rikkahub/ui/components/message/RichRenderPlanTest.kt
```

Minimum cases:

1. Simple static card plans as `Native` with high or medium confidence.
2. `<script>` or canvas/runtime content plans as `DynamicPreview`.
3. `backdrop-filter` / `mask` / complex visual hint plans as `Snapshot` or low confidence.
4. Interactive button content increments `interactiveActionCount`.
5. Span-heavy paragraph increments `textFlowCandidateCount`.
6. Complex SVG feature increments `snapshotIslandCandidateCount`.
7. Builder is deterministic for same input.
8. Telemetry/debug summary does not contain raw HTML text.
9. Passing compiled model updates visual hints/unsupported fields.
10. Height cache state is preserved in plan.

## Required Validation Commands

Developer should run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest"
```

Also run existing related tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotPolicyTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

And compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

## Acceptance Criteria

Codex acceptance will check:

- No visible behavior change in rich HTML route decisions.
- `RichRenderPlan` exists and is pure/report-only.
- Plan generation does not synchronously compile rich HTML unless a model is already provided.
- Telemetry does not include raw user content.
- Seed/fidelity report includes plan fields.
- Unit tests cover deterministic route/counter behavior.
- Existing snapshot policy and seed tests still pass.
- Code is scoped and does not refactor unrelated renderer behavior.

## Handoff Checklist

Developer should provide:

- Summary of files changed.
- Example debug plan log for one simple native card.
- Example debug plan log for one snapshot/dynamic candidate.
- Test command output.
- Any uncertainty about route thresholds or estimator formulas.

