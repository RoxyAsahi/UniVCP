# Rich Render V3 Development Requirements: Persistent Height Cache And Orchestrator

Date: 2026-05-29

Depends on:

- V1 `RichRenderPlan`
- V2 `RichContentAst` / `RichTextFlowBlock`

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

Improve upward history scrolling stability and centralize rich render route/admission decisions.

V3 introduces two linked pieces:

```text
Persistent Rich Height Cache
Unified RichRenderOrchestrator
```

The immediate product goal is:

- rich bubbles reserve realistic height before native compile/snapshot/inline WebView finishes
- route/admission decisions become explainable in one place
- already-visible rich content remains stable
- no aggressive degradation of visible content

## Required Scope Split

### V3A: Persistent Native Height Cache

Promote the current in-memory native `RichHtmlHeightCache` into a versioned persistent cache.

### V3B: Report-First RichRenderOrchestrator

Add a unified orchestrator that initially mirrors existing behavior and records decisions.

### V3C: Safe Height Placeholder Use

Use persistent measured height for placeholders where behavior is already placeholder-based.

If V3B cannot be fully wired in one pass, V3A + V3C may land first.

## Non-Goals

- Do not change visual route thresholds in V3.
- Do not make snapshot default for interactive cards.
- Do not hide already-rendered native content during scroll.
- Do not introduce new live WebView behavior.
- Do not persist raw HTML, CSS, text, button labels, or preview content.
- Do not store screenshots/bitmaps in the height cache.

## V3A Required Height Cache

Suggested new file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichRenderHeightCache.kt
```

Required key:

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

Required value:

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

- Persist through SharedPreferences or existing lightweight local store.
- Keep memory LRU in front of persistent storage.
- Max persisted entries: 512 by default.
- Clamp height to safe range.
- Do not overwrite higher confidence with lower confidence.
- Same confidence may update if height delta is meaningful.
- `rendererVersion` must bump if height semantics change.
- The cache key must include width/fontScale/density/theme/contentType.

Migration:

- Current `RichHtmlHeightCache` may become a wrapper around the new cache.
- Existing callers should continue compiling with minimal churn.

## V3B Required Orchestrator

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

V3 behavior:

- Initially mirror current decisions from `RichHtmlRenderScheduler`, `RichHtmlSnapshotPolicy`, and `RichHtmlBubbleBlock`.
- Do not remove current scheduler yet.
- Record debug decision telemetry.
- Existing Composables may still execute the final decision, but the decision should be visible from orchestrator.

## V3C Required Placeholder Behavior

Use persistent measured height in existing placeholder branches:

- compile pending
- native presentation deferred
- lightweight during scroll
- snapshot loading
- inline WebView deferred preview

Rules:

- Already-rendered visible native content must not be replaced by a placeholder.
- Height placeholder should use highest-confidence cache entry.
- If no cache exists, use current estimate.
- Height delta warning should be logged if measured height differs from placeholder by a significant threshold.

Suggested thresholds:

- warning if delta > 25%
- severe warning if delta > 50%
- ignore tiny delta below 24px

## Required Telemetry

Extend `RichHtmlRenderTelemetry` with:

- height cache persistent hit/miss
- height confidence
- height delta percentage
- orchestrator route
- orchestrator reason
- placeholder source
- already rendered state
- cached model state

Debug summary should include:

- height hit rate
- confidence distribution
- max height delta
- route distribution
- native deferred count
- snapshot/dynamic count

Do not log raw content.

## Required Tests

Add:

```text
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichRenderHeightCacheTest.kt
app/src/test/java/me/rerere/rikkahub/ui/components/richtext/RichRenderOrchestratorTest.kt
```

Minimum height cache tests:

1. Key separates width buckets.
2. Key separates fontScale buckets.
3. Key separates density/theme/contentType.
4. Higher confidence is not overwritten by lower confidence.
5. Same confidence meaningful delta updates height.
6. Entry count is bounded.
7. No raw HTML/text is stored.
8. Renderer version separates old heights.

Minimum orchestrator tests:

1. Complex dynamic plans route to `DynamicPreview` or `InlineWebView` according to existing classification.
2. Snapshot risk routes to `Snapshot`.
3. Fast scroll blocks new risky native first render.
4. Already rendered content remains native allowed.
5. Cached model can be native allowed.
6. Height cache hit produces placeholder height.
7. No cache falls back to estimate.
8. Decision reason is deterministic.

## Required Validation Commands

Run new tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestratorTest"
```

Run related regression tests:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest" --tests "me.rerere.rikkahub.ui.components.message.RichContentAstTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichTextFlowOptimizerTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

Compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

## Device Validation

Preferred device seed:

- Assistant: Uika
- Conversation: `文字游龙`

Manual smoke:

1. Install debug build.
2. Open rich conversation.
3. Scroll downward and upward through rich bubbles.
4. Watch logs:
   - `render-plan`
   - `height cache`
   - `height delta`
   - `orchestrator`
   - `frame-pressure`
   - `inline-webview-phase`
   - `FATAL EXCEPTION`
   - `RenderProcessGone`

Acceptance:

- No crash.
- Persistent height cache hits appear after revisiting rich bubbles.
- Already-visible content does not disappear during scroll.
- Height placeholder reduces obvious jump when revisiting history.
- No raw message text in logs.

## Codex Acceptance Criteria

Codex will check:

- V3 does not change route policy unexpectedly.
- Height cache is versioned and privacy-safe.
- Orchestrator is either report-only or behavior-equivalent to existing logic.
- Placeholder height uses confidence-aware cache.
- Upward history scroll logs show height hits after first measure.
- New tests and existing V1/V2/seed/fidelity tests pass.
- Debug package installs and launches on wireless device.
