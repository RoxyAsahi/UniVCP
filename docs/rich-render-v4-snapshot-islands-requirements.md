# Rich Render V4 Development Requirements: Subtree Routing And Snapshot Islands

Date: 2026-05-29

Depends on:

- V1 `RichRenderPlan`
- V2 `RichContentAst` / `RichTextFlowBlock`
- V3 `RichRenderHeightCache` / report-first `RichRenderOrchestrator`

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

V4 introduces subtree-level route planning:

```text
RichContentAst / RichHtmlRenderModel
  -> subtree capability analysis
  -> native blocks where fidelity is high
  -> snapshot islands where browser fidelity is required
  -> native actions preserved around islands
```

The product goal is fidelity-first:

- Do not approximate complex browser-only visuals badly in native Compose.
- Do not turn an entire interactive card into a static image if only one visual subtree is hard.
- Preserve native buttons, links, text selection behavior, and action dispatch when possible.
- Use WebView snapshot only for visual-only difficult subtrees.

## Why V4

Current fallback choices are too coarse:

```text
whole bubble native
whole bubble snapshot
dynamic/inline WebView
```

This creates a bad tradeoff:

- Whole native can lose CSS/SVG fidelity.
- Whole snapshot preserves visuals but loses native interactivity.
- Inline WebView preserves runtime behavior but is expensive during scroll.

V4 adds a middle path:

```text
native card
  native text
  snapshot island for hard visual area
  native buttons/actions
```

This is the closest route to WebView fidelity without giving up the Compose cell pipeline.

## Required Scope Split

### V4A: Report-Only Subtree Capability Analysis

Add subtree classification without changing rendering.

### V4B: Snapshot Island Render Model

Add model nodes that represent a static browser-rendered subtree image.

### V4C: Conservative Snapshot Island Renderer

Render only very safe visual-only islands as snapshot images, preserving native parent/children around them.

### V4D: Telemetry And Fidelity Reports

Record island candidates, applied islands, rejected reasons, and whole-bubble fallback reduction.

If V4C cannot be safely completed in one pass, V4A + V4B may land first.

## Non-Goals

- Do not implement full CSS cascade rewrite.
- Do not introduce live WebView islands inside native layout.
- Do not route interactive subtrees to static snapshot islands.
- Do not make snapshot islands default for all SVG.
- Do not change dynamic inline WebView policy.
- Do not remove whole-bubble snapshot fallback.
- Do not store raw HTML/text in telemetry.
- Do not require visual diff gate yet; that belongs to V6.

## V4A: Subtree Capability Analysis

Suggested file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichSubtreeRoutePlanner.kt
```

Add:

```kotlin
internal data class RichSubtreeRoutePlan(
    val rootRoute: RichSubtreeRoute,
    val candidates: List<RichSnapshotIslandCandidate>,
    val rejected: List<RichSnapshotIslandRejected>,
    val nativePreservedActionCount: Int,
    val estimatedWholeSnapshotAvoided: Boolean,
)

internal enum class RichSubtreeRoute {
    Native,
    NativeWithSnapshotIslands,
    WholeSnapshot,
    DynamicPreview,
    InlineWebView,
}

internal data class RichSnapshotIslandCandidate(
    val blockId: String,
    val stablePath: String,
    val reason: RichSnapshotIslandReason,
    val estimatedWidthPx: Int?,
    val estimatedHeightPx: Int?,
    val visualOnly: Boolean,
)

internal enum class RichSnapshotIslandReason {
    ComplexSvg,
    SvgFilter,
    SvgMask,
    SvgClipPath,
    CssMask,
    CssClipPath,
    CssBackdropFilter,
    CssFilter,
    MixBlendMode,
    ComplexBackground,
    UnsupportedStaticVisual,
}

internal data class RichSnapshotIslandRejected(
    val blockId: String,
    val stablePath: String,
    val reason: RichSnapshotIslandRejectReason,
)

internal enum class RichSnapshotIslandRejectReason {
    InteractiveSubtree,
    ContainsAction,
    ContainsFormControl,
    ContainsScriptRuntime,
    ContainsCanvasRuntime,
    UnknownSize,
    TooLarge,
    TooSmall,
    TooManyIslands,
    ParentLayoutTooDependent,
    SafetyRejected,
}
```

Rules:

- Analyze `RichHtmlRenderModel` first, because it reflects the current native compiler output.
- Optionally use `RichContentAst` stats as supporting metadata.
- Candidate detection must be deterministic.
- Candidate `stablePath` must be structural, never text-derived.
- Candidate subtree must be visual-only.
- Any subtree containing action dispatch, button, form controls, script/canvas/runtime content, or navigation side effects must be rejected.

Candidate triggers:

- SVG with filter/mask/clip-path/pattern/symbol/use/foreignObject.
- CSS filter/backdrop-filter/mask/clip-path/mix-blend-mode.
- Complex layered background or background image that native cannot reproduce well.
- Unsupported static visual features already forcing whole snapshot.

Budget:

- Max islands per bubble: default 2.
- Max island height: default 1.5 screens or 1800 px, whichever is lower.
- Min useful island size: default 48 x 48 px.
- If candidate count exceeds budget, keep whole-bubble snapshot behavior.

## V4B: Snapshot Island Render Model

Update `RichHtmlRenderModel.kt` with a new block:

```kotlin
internal data class RichSnapshotIslandBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val sourceHtml: String,
    val sourceDigest: String,
    val reason: RichSnapshotIslandReason,
    val estimatedHeightPx: Int?,
    val fallbackBlock: RichBlock?,
) : RichBlock
```

Privacy rules:

- `sourceHtml` may exist in render model for actual rendering, but must not be logged.
- Telemetry must log only digest, reason, dimensions, route, and reject reason.

Fallback rules:

- `fallbackBlock` is optional and used only if snapshot rendering fails.
- Fallback must not hide native buttons/actions outside the island.

Model transformation:

```text
RichHtmlRenderModel
  -> RichSubtreeRoutePlanner.plan(model)
  -> RichSnapshotIslandOptimizer.apply(model, plan)
```

Suggested file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichSnapshotIslandOptimizer.kt
```

The optimizer must be conservative:

- Replace only the exact candidate subtree.
- Preserve parent layout block.
- Preserve sibling order.
- Preserve native action blocks.
- Leave model unchanged if uncertain.

## V4C: Snapshot Island Renderer

Update `RichHtmlRenderer.kt`:

- Add `RichSnapshotIslandBlockView`.
- Render via existing `BubbleSnapshotRenderer` or a new island-specific wrapper around it.
- Reuse WebView snapshot pool.
- Reuse `RichRenderHeightCache` with content type `snapshot-island`.
- Show stable-height placeholder while island snapshot is rendering.
- Fall back to `fallbackBlock` or existing whole-bubble fallback on failure.

Suggested island snapshot API:

```kotlin
internal object RichSnapshotIslandRenderer {
    suspend fun render(
        context: Context,
        html: String,
        widthPx: Int,
        theme: BubbleTheme,
    ): RichSnapshotIslandResult
}
```

Cache key:

```text
sourceDigest + width + density + fontScale + theme + rendererVersion + reason
```

Renderer rules:

- Never create live WebView per island.
- Render offscreen snapshot, then display bitmap in Compose.
- Return WebView to pool immediately after bitmap capture.
- Use bounded queue shared with existing snapshot renderer.
- Avoid starting island snapshot work during fast scroll unless cached bitmap exists.
- Use cached height placeholder if bitmap is not ready.

## V4D: Telemetry

Extend `RichHtmlRenderTelemetry` with:

- island candidate count
- island applied count
- island reject reason distribution
- whole snapshot avoided count
- island snapshot cache hit/miss
- island snapshot render time
- island height cache hit/miss
- island fallback reason

Example debug log shape:

```text
snapshot-island-plan id=... route=NativeWithSnapshotIslands candidates=2 applied=1 rejected=1 reasons=[SvgMask] rejectReasons=[ContainsAction]
snapshot-island-render id=... block=... reason=SvgMask cacheHit=false renderMs=42 heightPx=360
```

No raw HTML, text, CSS, button labels, URLs, or preview body text in telemetry.

## Required Tests

Add:

```text
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichSubtreeRoutePlannerTest.kt
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichSnapshotIslandOptimizerTest.kt
```

Minimum planner tests:

1. Complex visual-only SVG becomes a snapshot island candidate.
2. SVG containing action/button ancestor is rejected.
3. CSS mask/filter/backdrop-filter produces candidate.
4. Runtime canvas/script is not an island and routes dynamic/inline according to existing policy.
5. Candidate count over budget rejects or falls back to whole snapshot.
6. Tiny visual subtree is rejected.
7. Stable path is deterministic and not text-derived.
8. Plan telemetry metadata does not contain raw text/html.

Minimum optimizer tests:

1. Replaces only the candidate subtree with `RichSnapshotIslandBlock`.
2. Preserves parent container and sibling order.
3. Preserves native button/action siblings.
4. Leaves model unchanged when candidate cannot be found.
5. Leaves model unchanged when candidate contains interactive child.
6. `countRichRenderBlocks` understands `RichSnapshotIslandBlock`.
7. Whole-bubble snapshot route is unchanged when island budget is exceeded.
8. Fallback block is preserved.

Optional Compose/UI tests:

- Snapshot island placeholder uses cached height.
- Native action button beside an island still dispatches.
- Snapshot island failure shows fallback without crashing.

## Required Validation Commands

Run new V4 tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.RichSubtreeRoutePlannerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichSnapshotIslandOptimizerTest"
```

Run related regression tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest" --tests "me.rerere.rikkahub.ui.components.message.RichContentAstTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichTextFlowOptimizerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestratorTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

Compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

Debug build:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug
```

## Device Validation

Preferred seed:

- Assistant: `Uika`
- Conversation: `文字游龙`

Manual checks:

1. Find cards with complex SVG/CSS visuals plus native text/buttons.
2. Confirm complex visual area appears high-fidelity.
3. Confirm native buttons/actions around it still work.
4. Scroll upward/downward through island-heavy bubbles.
5. Confirm no `FATAL EXCEPTION` or `RenderProcessGone`.
6. Confirm no excessive WebView count; island output should be bitmap, not live WebView.

Log patterns:

```text
snapshot-island-plan
snapshot-island-render
height cache
height delta
orchestrator
frame-pressure
FATAL EXCEPTION
RenderProcessGone
```

## Acceptance Criteria

V4 is accepted when:

- Subtree planner is report-only or conservatively applied.
- Static visual-only difficult subtrees can become snapshot islands.
- Interactive/action subtrees are never converted to static islands.
- Whole-bubble snapshot fallback remains available.
- Native buttons/actions around an island are preserved.
- Island snapshots reuse existing offscreen snapshot infrastructure/pool.
- Placeholder height uses V3 height cache where possible.
- Tests pass.
- Debug build compiles.
- Telemetry can answer:
  - why an island was created
  - why a candidate was rejected
  - whether a whole-bubble snapshot was avoided
  - whether height/cache worked

## Developer Handoff Report Template

Developer should report back with:

```text
Implemented scope:
- V4A:
- V4B:
- V4C:
- V4D:

Files changed:
-

Candidate examples:
-

Rejected examples:
-

Telemetry examples:
-

Tests passed:
-

Known risks / deferred:
-
```

## Codex Acceptance Focus

Codex will specifically check:

- No interactive subtree is snapshotted.
- No raw content leaks to telemetry.
- Existing whole-bubble native/snapshot/dynamic routes do not unexpectedly regress.
- Island rendering is bounded and does not create live WebViews in the list.
- Height cache integration does not reintroduce big jumps.
- V4 does not sneak in V5 parser rewrite or V6 visual diff infrastructure.
