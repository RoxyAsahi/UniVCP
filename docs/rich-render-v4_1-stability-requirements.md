# Rich Render V4.1 Development Requirements: Stability Closure

Date: 2026-05-29

Depends on:

- V3 `RichRenderHeightCache` / `RichRenderOrchestrator`
- V4 subtree routing and snapshot islands

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

V4.1 is a closure release, not a new feature expansion.

The goal is to harden the routes introduced in V3/V4 before moving to parser and fidelity-platform work:

- Inline WebView crash must not leak global admission slots.
- Cached native models must still respect route/admission policy.
- Snapshot islands must not double-apply style or cause height jumps.
- Telemetry must make these cases visible without logging raw content.

## Current Findings To Close

### P0: Inline WebView Crash Admission Leak

Observed risk:

```text
InlineDynamicWebViewBlock
  -> admitted = true
  -> WebView render process gone
  -> inner surface shows deferred preview
  -> outer admission slot can remain occupied
```

Required behavior:

- When `onRenderProcessGone()` fires, release `InlineWebViewAdmission` for the inline id.
- Propagate crash state to the outer `InlineDynamicWebViewBlock` owner, or provide an explicit `onCrash` callback from `InlineWebViewSurface`.
- Set outer `admitted = false` after the crash path releases admission.
- Record telemetry:
  - `event=release`
  - `reason=render-process-gone` or `render-process-crashed`
  - active admission count after release
- Keep the existing deferred preview fallback visible.
- Do not destroy unrelated pooled WebViews.

Acceptance:

- A crashed inline WebView cannot permanently consume one of the global inline WebView slots.
- Two crashed inline WebViews do not block later visible inline WebViews.
- Existing pool release/discard behavior remains safe.

### P1: Cached Model Must Not Bypass Admission Policy

Observed risk:

```text
cachedModelAvailable == true
  -> nativeAllowed = true
  -> scheduler/orchestrator gate skipped
```

Required behavior:

- A compile cache hit may skip compile work, but must not skip route/admission policy.
- The orchestrator should receive `cachedModelAvailable=true` and decide:
  - `NativeNow`
  - `NativeDeferred`
  - `Lightweight`
  - `Snapshot`
  - `DynamicPreview`
- If a cached model is high risk during fast scroll, it should use the same fast-scroll first-render rules as a non-cached model.
- Already rendered content may stay visible, preserving the current experience priority.

Acceptance:

- Cached high-risk rich HTML cannot start new native first render during fast scroll solely because a compiled model exists.
- Compile cache hits still reduce background work.
- Route telemetry clearly shows `cachedModelAvailable=true`.

### P1: Snapshot Island Style Boundary Audit

Observed risk:

```text
RichSnapshotIslandBlock(style = original style, sourceHtml = original outerHtml)
  -> StyledContainer(block.style)
  -> Image(snapshot of sourceHtml that may already include style)
```

Depending on the candidate type, this can double-apply padding, margin, border, background, width, height, or transform.

Required behavior:

- Define a clear style boundary for snapshot islands:
  - either outer native style owns layout and snapshot source is normalized inner content
  - or snapshot source owns the visual style and native wrapper uses neutral layout style
- Keep fallback block visually close to the snapshot path.
- Add helper naming that makes the chosen boundary explicit.
- Add telemetry field `styleBoundary=native-wrapper|snapshot-source|neutral-wrapper`.

Recommended conservative route:

- For `RichSvgBlock`, keep native wrapper style only for layout constraints and render the original SVG source.
- For CSS visual container candidates, prefer neutral native wrapper and let source HTML own the visual style.
- Reject candidates when the style boundary cannot be determined safely.

Acceptance:

- Snapshot islands do not show doubled padding/border/background.
- Height placeholder matches final bitmap height within existing V3 tolerance.
- Existing fallback behavior still works when snapshot rendering fails.

### P2: Snapshot Island Queue And Height Telemetry

Required behavior:

- Record cache hit/miss for island bitmap cache and V3 height cache separately.
- Record render queue wait if the underlying snapshot renderer exposes it.
- Avoid starting new island snapshot rendering during fast scroll unless cached bitmap exists.
- If island snapshot fails, emit one fallback telemetry event, not repeated loops.

Acceptance:

- Debug logs can answer:
  - whether island bitmap was cached
  - whether height was cached
  - whether rendering was skipped due to fast scroll
  - why fallback happened

## Non-Goals

- Do not add visual diff infrastructure. That belongs to V6.
- Do not rewrite CSS parser/cascade. That belongs to V5.
- Do not change the V4 candidate set except for safety rejections.
- Do not make inline WebView more aggressive.
- Do not change historical message storage or prompt/VCP format.

## Required Tests

Add or update JVM tests:

- `InlineDynamicWebViewAdmissionTest`
  - crash releases admission
  - double crash does not exhaust slots
  - dispose after crash is idempotent
- `RichHtmlRenderSchedulerTest`
  - cached model still respects fast-scroll admission
  - already rendered cached model remains visible
- `RichSnapshotIslandOptimizerTest`
  - unsafe style boundary rejects candidate
  - source-owned style does not wrap with duplicate visual style metadata
- `RichSnapshotIslandRendererTest` or focused Compose test if practical
  - cached height placeholder is used
  - failed snapshot renders fallback once

Existing tests that must continue passing:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.RichSubtreeRoutePlannerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichSnapshotIslandOptimizerTest"
```

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest" --tests "me.rerere.rikkahub.ui.components.message.RichContentAstTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichTextFlowOptimizerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestratorTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

Compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

## Optional Device Smoke

Preferred seed:

- Assistant: `Uika`
- Conversation: `文字游龙`

Manual smoke, if requested:

1. Open dynamic inline WebView-heavy section.
2. Scroll up and down until inline WebViews attach/release.
3. Confirm no long-term `pool-full` after a render-process-gone event.
4. Confirm snapshot islands show stable placeholders and then bitmap.
5. Confirm no obvious doubled padding/border around islands.

Log patterns:

```text
inline-webview event=release reason=render-process-gone
snapshot-island-render
height cache
orchestrator cachedModel=true
FATAL EXCEPTION
RenderProcessGone
```

## Acceptance Criteria

V4.1 is accepted when:

- Inline WebView crash releases admission and leaves future inline content available.
- Cached compiled models no longer bypass policy gates.
- Snapshot island style boundary is explicit and tested.
- Existing V4 behavior remains conservative.
- V4/V3/V2/V1 regression tests pass.
- Debug build compiles.
- No raw HTML/text/URLs/button labels are added to telemetry.

## Developer Handoff Report Template

```text
Implemented scope:
- Crash admission release:
- Cached model admission:
- Snapshot island style boundary:
- Telemetry:

Files changed:
-

Tests passed:
-

Known risks / deferred:
-
```
