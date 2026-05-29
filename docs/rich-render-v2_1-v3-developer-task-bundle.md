# UniVCP Rich Render V2.1 + V3 Developer Task Bundle

Date: 2026-05-29

## Background

UniVCP rich rendering is taking the fidelity-first native Compose route:

- Prefer native Compose when fidelity and interaction can be preserved.
- Use WebView snapshot for browser-only static visual effects.
- Use governed inline WebView only for truly dynamic/runtime content.
- Keep chat rendering cell-based, not one giant Lazy item per message.

V1 `RichRenderPlan` has landed as report-only observability.

V2 `RichContentAst` and conservative `RichTextFlowBlock` work has been implemented, but it needs a small V2.1 hardening pass before V3. V3 then focuses on upward history scroll stability through persistent height cache and a report-first render orchestrator.

This bundle is intended to be assigned directly to developers.

## Execution Order

Do not merge V2.1 and V3 into one ambiguous task.

Required order:

1. V2.1: V2 hardening and acceptance closure.
2. Commit/backup V2.1.
3. V3A: Persistent rich height cache.
4. V3C: Safe placeholder height usage.
5. V3B: Report-first `RichRenderOrchestrator`.
6. Device smoke on real conversation `Uika / 文字游龙`.

V2.1 is intentionally small. It must not become V6 visual diff infrastructure.

## Product Priority

Fidelity first.

Optimization is only acceptable when it does not visibly degrade rendering. If native Compose cannot reproduce a browser effect with high confidence, route that subtree/content to snapshot or governed inline WebView instead of approximating aggressively.

## V2.1: RichContentAst/TextFlow Hardening

### Goal

Close the remaining ambiguity in V2 so it is safe to build V3 on top of it.

V2.1 is not a new architecture milestone. It is a safety and acceptance pass for V2.

### Required Work

1. Confirm TextFlow is strictly gated.
   - TextFlow may handle simple text-heavy paragraphs, spans, simple lists, links, inline code, and already-supported inline text styles.
   - TextFlow must not swallow layout-heavy or visual-heavy content.

2. Add or verify blockers for these cases:
   - `button` / action elements
   - `table`
   - `svg`
   - `img`
   - `details`
   - `canvas`, `iframe`, `video`, `audio`, `object`, `embed`
   - absolute/fixed/sticky positioning
   - flex/grid containers
   - background images or layered backgrounds
   - filter/backdrop-filter/mask/clip-path/mix-blend-mode
   - meaningful border/background/shadow containers
   - inline boxes with explicit dimensions
   - list markers that require images or complex paint

3. Ensure telemetry/reporting remains privacy-safe.
   - No raw HTML.
   - No raw message text.
   - No button labels or preview body text in debug summaries.
   - Structural counts and reasons are allowed.

4. Ensure TextFlow behavior is explainable.
   - Report whether TextFlow was applied.
   - Report estimated render node reduction.
   - Report blocked reason categories for unsafe candidates.

5. Run a real-device smoke test after build.
   - Preferred seed: assistant `Uika`, conversation `文字游龙`.
   - Check for crash, obvious wrong rendering, and accidental TextFlow flattening of complex cards.

### Deferred From V2.1

These are explicitly not required for V2.1:

- Full native-vs-WebView screenshot visual diff gate.
- Full manual audit of every `文字游龙` rich bubble.
- Macrobenchmark.
- Snapshot island architecture.
- CSS parser rewrite.

Those belong to later V4/V5/V6 work.

### Required Tests

Add or verify tests around:

- `RichContentAstTest`
  - source order preservation
  - browser-only node classification
  - visual-heavy node classification
  - no raw body text in report snapshots

- `RichTextFlowOptimizerTest`
  - simple paragraphs convert to `RichTextFlowBlock`
  - span-heavy content reduces block count
  - button/table/svg/image/details are not flattened
  - complex visual styles are not flattened
  - list-style-image or equivalent complex list markers are not flattened
  - unsupported reasons are preserved
  - `countRichRenderBlocks` understands `RichTextFlowBlock`

### V2.1 Acceptance

V2.1 is accepted when:

- Existing V1/V2 tests pass.
- TextFlow never eats interactive or visual-heavy content.
- Telemetry has useful TextFlow applied/blocked signals.
- Debug build compiles.
- Real-device smoke launches and scrolls without crash.

## V3: Persistent Height Cache And Render Orchestrator

### Goal

Improve upward history scrolling stability and centralize rich render admission/route decisions.

V3 must not aggressively degrade visible content. Already-rendered visible rich content should stay visible.

### V3A: Persistent Rich Height Cache

Add a versioned persistent height cache for rich render content.

Suggested file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichRenderHeightCache.kt
```

Required key shape:

```kotlin
internal data class RichRenderHeightCacheKey(
    val id: String,
    val widthDp: Int,
    val fontScaleBucket: Int,
    val densityBucket: Int,
    val themeBucket: String,
    val contentType: String,
    val rendererVersion: Int,
)
```

Required entry shape:

```kotlin
internal data class RichRenderHeightCacheEntry(
    val heightPx: Int,
    val confidence: RichRenderHeightConfidence,
    val updatedAtMs: Long,
)

internal enum class RichRenderHeightConfidence {
    Estimated,
    MeasuredNative,
    MeasuredSnapshot,
    MeasuredInlineWebView,
}
```

Rules:

- Persist through SharedPreferences or an existing lightweight local store.
- Keep an in-memory LRU in front of persistent storage.
- Default max persisted entries: 512.
- Clamp height to safe min/max range.
- Do not overwrite higher-confidence height with lower-confidence height.
- Same-confidence entries may update only when height delta is meaningful.
- Key must include width, font scale, density, theme, content type, and renderer version.
- Do not store raw HTML, raw text, screenshots, or bitmaps.

### V3C: Safe Placeholder Height Use

Use measured cached height only in branches that already use placeholders/loading states:

- compile pending
- native presentation deferred
- lightweight during scroll
- snapshot loading
- inline WebView deferred preview

Rules:

- Already-rendered visible native content must not be replaced by placeholder.
- Prefer the highest-confidence cached height.
- If no cache exists, use the current estimate.
- Log height delta warnings after real measure:
  - ignore delta below 24 px
  - warning if delta > 25%
  - severe warning if delta > 50%

Expected user-visible improvement:

- Revisiting rich history should reserve a realistic height earlier.
- Upward scrolling through old rich bubbles should jump less.

### V3B: Report-First RichRenderOrchestrator

Add a unified orchestrator that initially mirrors current behavior.

Suggested file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichRenderOrchestrator.kt
```

Required input:

```kotlin
internal data class RichRenderDecisionInput(
    val plan: RichRenderPlan,
    val analysis: RichHtmlAnalysis,
    val risk: RenderRiskScore,
    val scrollState: RichRenderScrollState,
    val cellIndex: Int?,
    val cachedModelAvailable: Boolean,
    val heightEntry: RichRenderHeightCacheEntry?,
    val alreadyRendered: Boolean,
    val transient: Boolean,
)
```

Required output:

```kotlin
internal data class RichRenderDecision(
    val route: RichRenderDecisionRoute,
    val reason: String,
    val placeholderHeightPx: Int?,
    val nativeAdmissionAllowed: Boolean,
    val shouldPrewarm: Boolean,
)

internal enum class RichRenderDecisionRoute {
    Native,
    NativeDeferred,
    Snapshot,
    DynamicPreview,
    Lightweight,
    InlineWebView,
}
```

V3B rules:

- Initially mirror current decisions from scheduler, snapshot policy, and bubble rendering.
- Do not remove the existing scheduler in V3.
- Do not change route thresholds unless explicitly required by a failing test.
- Record orchestrator decisions in debug telemetry.
- Keep behavior equivalent while making decisions visible and testable.

### V3 Required Telemetry

Extend `RichHtmlRenderTelemetry` with:

- persistent height cache hit/miss
- height confidence
- placeholder source
- measured-vs-placeholder height delta
- orchestrator route
- orchestrator reason
- already-rendered state
- cached model state

Debug summary should include:

- height hit rate
- height confidence distribution
- max height delta
- route distribution
- native deferred count
- snapshot/dynamic/inline WebView count

No raw content may be logged.

### V3 Required Tests

Add:

```text
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichRenderHeightCacheTest.kt
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichRenderOrchestratorTest.kt
```

Minimum height cache tests:

1. Width buckets are separated.
2. Font scale buckets are separated.
3. Density, theme, and content type are separated.
4. Higher confidence is not overwritten by lower confidence.
5. Same confidence can update on meaningful delta.
6. Entry count is bounded.
7. No raw HTML/text is stored.
8. Renderer version separates old heights.

Minimum orchestrator tests:

1. Complex dynamic content routes to dynamic preview or inline WebView according to existing classification.
2. Snapshot-risk content routes to snapshot.
3. Fast scroll blocks risky new native first render.
4. Already-rendered content remains native allowed.
5. Cached model can be native allowed.
6. Height cache hit produces placeholder height.
7. No cache falls back to estimate.
8. Decision reason is deterministic.

## Validation Commands

V2.1 and V3 tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichContentAstTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichTextFlowOptimizerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestratorTest"
```

Regression tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

Compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

Debug APK:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug
```

## Real Device Smoke

Preferred target:

- Assistant: `Uika`
- Conversation: `文字游龙`

Check logs for:

```text
render-plan
TextFlow
height cache
height delta
orchestrator
frame-pressure
inline-webview-phase
FATAL EXCEPTION
RenderProcessGone
```

Acceptance:

- App launches.
- No `FATAL EXCEPTION`.
- No `RenderProcessGone`.
- TextFlow appears only for safe text-heavy content.
- Height cache misses appear on first visit and hits appear after revisiting.
- Already-visible rich content does not disappear during scroll.
- Upward history scroll has fewer large height jumps after cache is warm.

## Developer Handoff Report Template

Developer should report back with:

```text
Implemented scope:
- V2.1:
- V3A:
- V3C:
- V3B:

Files changed:
-

Important behavior notes:
-

Telemetry examples:
-

Tests passed:
-

Device smoke:
- Device:
- Conversation:
- Crash:
- RenderProcessGone:
- Height cache hit after revisit:
- Obvious visual regression:

Known risks / deferred items:
-
```

## Final Acceptance By Codex

Codex will verify:

- V2.1 did not expand scope into V6 visual diff.
- TextFlow remains conservative and fidelity-first.
- V3 height cache is versioned, bounded, and privacy-safe.
- Placeholder height only affects existing placeholder states.
- Orchestrator is report-first or behavior-equivalent.
- Tests pass.
- Debug build installs and launches.
- `文字游龙` logs show no crash and useful height/orchestrator telemetry.
