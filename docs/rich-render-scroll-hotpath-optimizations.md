# Rich Render Scroll Hot Path Optimizations

## Purpose

This note records the current scroll-performance fixes for the Compose native rich renderer. Keep it with the code changes so future refactors do not accidentally revert behavior that was added from real-device evidence.

## Scroll Governance Decision

Last updated: 2026-05-29.

The recent history has shown that scroll performance regresses when we treat every jank report as a local renderer tweak. The long-term rule is:

```text
Do not make scroll state a deep renderer concern.
Keep scroll decisions at the feed scheduler/orchestrator boundary.
Let rich cells consume stable admissions, cached models, and cached heights.
```

In practical terms:

- `ChatList` may observe high-frequency scroll signals.
- `RichHtmlRenderScheduler` and `RichRenderOrchestrator` may use coarse scroll state to admit, defer, prewarm, or skip new expensive work.
- Leaf renderers should not repeatedly recompute route/plan/telemetry just because the finger moved by a few pixels.
- Already-rendered native content and already-live inline WebView content should stay visually present while visible.
- New expensive work may be delayed during scroll, but visible content should not aggressively downgrade unless the renderer has crashed or the route is unsafe.

This is the line we should hold. Future fixes should reduce repeated work and route churn, not keep adding stricter visible/idle gates that make the UI feel worse.

### What We Learned

1. **Upward history scrolling is the hardest path.**
   It combines Lazy item reuse, cached historical rich content, possible inline WebView admission, height restoration, and prewarm. Downward scrolling is usually cheaper because the user is moving toward newer content and already-warm cells.

2. **Repeated `render id=... route=native` is not automatically a compiler regression.**
   It often means Lazy remeasured a recycled native cell. It becomes a problem only when paired with repeated compile starts, height misses, large height deltas, frame-pressure spikes, or expensive logging.

3. **The biggest regression risk is high-frequency scroll state flowing into too many composables.**
   A single `RichRenderScrollState` object is currently provided to the whole chat subtree. If it changes on every scroll sample, every rich renderer that reads it can re-enter route/orchestrator/effect code even when the cell should only keep drawing its cached model.

4. **Animation freezing during scroll was tried and rejected.**
   The APR smoke became worse. Toggling native animation state directly from `scrollInProgress` creates composition/layer churn and can be slower than letting a small amount of animation continue.

5. **Aggressive WebView downgrade hurts the product goal.**
   Users prefer high-fidelity content to remain visible. The correct policy is not "hide WebView whenever the finger moves"; it is "do not admit new, never-seen expensive WebViews during bad frame pressure, but keep already-live visible content stable."

6. **Height cache is a scroll feature, not only a renderer feature.**
   If historical rich cells have measured height, re-entry should start from that height. Wrong or missing height causes jump, relayout, and extra measurement.

7. **Debug telemetry can become part of the jank.**
   Long per-cell debug strings in a fast scroll can distort debug profiling. Telemetry must be sampled, signature-gated, or aggregated.

### Current Decision For The Next Refactor

The next scroll work should be a small architectural refactor, not another threshold tweak:

```text
ChatList high-frequency scroll observer
  -> coarse RichScrollAdmissionState
  -> scheduler/orchestrator decisions
  -> stable per-cell render admission
  -> leaf renderers consume stable booleans/entries, not raw scroll motion
```

Required split:

- `RichScrollViewportState`
  - visible cell range
  - near viewport range
  - viewport width
  - scroll direction bucket
- `RichScrollAdmissionState`
  - idle / settling / active / fast
  - upward-history pressure flag
  - recent severe frame pressure flag
- `RichCellRenderState`
  - native admission result
  - inline WebView admission result
  - prewarm eligibility
  - placeholder height source

The deep renderer should receive `RichCellRenderState` or a narrow equivalent. It should not need the full raw scroll object for normal rendering.

### Non-Goals

- Do not replace Compose native rich rendering with RecyclerView.
- Do not restore live WebView as the default renderer.
- Do not hide already-rendered rich content during normal scroll.
- Do not use `pauseTimers()` as a per-cell WebView lifecycle tool; it is global.
- Do not catch arbitrary Compose measure exceptions and continue the layout pass.
- Do not add new per-scroll logging without a retention and de-duplication policy.

### Change Control

Any future scroll optimization must update this document with:

- target history record, for example `APR`, `文字游龙`, or `5.5`
- before/after build type
- whether the change affects native, inline WebView, snapshot island, height cache, or telemetry
- expected user-visible behavior
- rollback condition

Before merging a scroll-path change, at least one of these must be true:

- it removes work from composition/measure/draw
- it makes admission more stable without hiding visible content
- it improves height/cache correctness
- it fixes a crash with a named stack trace
- it adds test or telemetry coverage for an existing blind spot

Threshold-only changes are allowed only when tied to real device evidence and must include a rollback note.

### 2026-05-29 Follow-Up Fixes

After the worktree review, three issues were triaged:

- `material3/` tracking is a backup/commit completeness issue. It should be fixed when staging the theme/material changes, not by changing render logic.
- CSS selector indexing is a fidelity issue and should be fixed immediately. Selectors with negation or dynamic pseudo classes must stay in the complex bucket so the engine cannot miss matches for elements that do not carry the indexed class/id.
- Scroll-state propagation is a render hot-path issue. The first low-risk fix is to avoid restarting the `ChatList` scroll observer when `isRecentScroll` changes and to explicitly de-duplicate emitted `RichRenderScrollState`. The larger follow-up remains the `RichScrollAdmissionState` split described above.

Primary validation histories:

- `文字游龙`: mostly inline WebView bubbles, useful for WebView admission and height behavior.
- `关于 "它的 APR 不是 8% 吗？..." (备用)`: mixed native rich HTML, useful for native Compose renderer scroll behavior.
- `关于 "5.5的这道题你能不能拿计算器..." (备用)`: mixed native rich HTML with body-only tables, useful for table/layout crash regression.

## Evidence From Device Smoke

Device: `SM_S937B`, package `com.univcp.android.debug`.

Artifacts:

- `build/rich-render-smoke/logcat-after-inline-cooldown.txt`
- `build/rich-render-smoke/gfxinfo-after-inline-cooldown.txt`
- `build/rich-render-smoke/logcat-apr-native-before-next-opt.txt`
- `build/rich-render-smoke/gfxinfo-apr-native-before-next-opt.txt`
- `build/rich-render-smoke/logcat-apr-native-after-hotpath-contenttype.txt`
- `build/rich-render-smoke/gfxinfo-apr-native-after-hotpath-contenttype.txt`
- `build/rich-render-smoke/logcat-apr-native-after-persistent-compile.txt`
- `build/rich-render-smoke/gfxinfo-apr-native-after-persistent-compile.txt`
- `build/rich-render-smoke/logcat-apr-native-after-heightdebounce.txt`
- `build/rich-render-smoke/gfxinfo-apr-native-after-heightdebounce.txt`
- `build/rich-render-smoke/logcat-apr-native-after-telemetry-gate.txt`
- `build/rich-render-smoke/gfxinfo-apr-native-after-telemetry-gate.txt`
- `build/rich-render-smoke/logcat-apr-native-after-animation-freeze.txt`
- `build/rich-render-smoke/gfxinfo-apr-native-after-animation-freeze.txt`
- `build/rich-render-smoke/logcat-55-diagnostic-rethrow.txt`
- `build/rich-render-smoke/logcat-55-fixed-scroll.txt`
- `build/rich-render-smoke/gfxinfo-55-fixed-scroll.txt`

Key observations:

- Inline WebView flow had jank around `Attaching -> Loading -> Measuring -> Live`, especially when scrolling toward history and admitting another WebView.
- Native APR flow did not crash and had no app-process `FATAL EXCEPTION`, `RenderProcessGone`, or `Unexpected state LookaheadMeasuring`.
- APR native flow still showed repeated `render id=... route=native` for the same ids when cells left and re-entered the viewport. This is mostly Compose measurement after lazy reuse, not repeated HTML compile, because logs also show `cachedModel=true` and `heightCache=Hit`.
- APR smoke before the latest hot-path cleanup: `1906` frames, `70` janky frames, `3.67%` jank, `99th percentile: 24ms`, with frame-pressure events up to `50.1ms` while `inlineActive=0`.
- APR smoke after persistent compile survival and native height debounce: no crash signatures, `compile start=0`, `compile queue=2`, `height delta=4`, `render id=4`, `frame-pressure=9`. Gfxinfo for this short debug run: `817` frames, `47` janky frames, `5.75%` jank, `99th percentile: 36ms`.
- APR smoke after debug telemetry signature gate: no crash signatures, `compile start=0`, `compile queue=2`, `height delta=2`, `render-plan=5`, `render id=4`, `frame-pressure=12`. Gfxinfo for this short debug run: `815` frames, `50` janky frames, `6.13%` jank, `99th percentile: 44ms`.
- A scroll-time native CSS animation freeze experiment was tried and reverted. It kept native cards visible, but the short APR smoke worsened to `683` frames, `53` janky frames, `7.76%` jank, `99th percentile: 81ms`, with severe frame-pressure up to `108.5ms`. The likely reason is that toggling animation enablement during scroll introduces extra composition/layer churn.
- The `5.5` smoke reproduced a native crash while cell `3425:sha256:edf53deb03c637b64441edac81d7cbea` entered native render. Full-stack logging showed the real source was `SpannedDataTable` measuring a body-only table: `headerP1.maxOf` was called with no header placeables. The older render guard swallowed the first `NoSuchElementException`, then Compose crashed as `layout state is not idle before measure starts`.
- After fixing body-only table measurement and rerunning the `5.5` scroll smoke, no `FATAL EXCEPTION`, `NoSuchElementException`, `layout state is not idle`, `render throwable`, or `CrashHandler` signature appeared. The process stayed alive. Debug gfxinfo for this focused run: `1237` frames, `86` janky frames, `6.95%` jank, `99th percentile: 36ms`.
- Treat gfxinfo percentages as directional only: debug build, short sample, manual adb swipes, and cold navigation make absolute jank percent noisy. The stronger signal is that compile churn did not return and remaining frame-pressure happened with `inlineActive=0`, cached heights, and native cached models.

## Optimizations Landed

### Native Placeholder Height Estimator

`RichHtmlBubbleBlock` memoizes the placeholder height estimator with:

- `html`
- `analysis`
- `density`
- `fontScale`

The estimator intentionally uses visible text length and structural costs for DOM nodes, tables, media, long HTML, layout styles, and interactive blocks. The caps are intentionally larger than the original values because real UIka cards are often long; too-small placeholders cause large first-render jumps and extra scroll work.

Do not reduce these caps without comparing APR and `文字游龙` smoke results.

### Inline WebView Admission Cooldown

`InlineDynamicWebViewBlock` applies a short frame-pressure cooldown before admitting a new not-yet-live WebView while scrolling toward history.

This does not hide already-live WebViews. It only delays new attach/load work when recent frame telemetry says the UI thread is already under pressure.

### Cell Pipeline Telemetry Hot Path

`ChatList` now precomputes total/rich/high-risk cell counts with `remember(renderCells)` instead of recounting the whole list every time `visibleCellRange` changes.

The previous form looked harmless but put O(N) list scanning into a scroll-driven `LaunchedEffect`. Telemetry should never make the scroll path slower than the renderer it observes.

### LazyColumn Content Type Reuse

`ChatRenderCell.RichHtmlCell` now reports its fixed `contentType`, not `contentType + stableKey`.

Stable identity still comes from `stableKey`. `contentType` is for LazyColumn reuse buckets. Making every rich cell a unique content type prevents same-type slot reuse and contradicts the cell-pipeline contract. Per-cell state that depends on HTML must remain keyed by `renderId` inside the renderer.

### Persistent Compile Survives Viewport Churn

`RichHtmlCompiler.compileAsync()` keeps persistent in-flight compiles alive after the last current composable waiter leaves the viewport. The result is allowed to finish and enter the persistent compile cache.

Before this guard, fast scrolling could cancel an almost-finished compile as soon as a cell was recycled. When the same rich block re-entered the viewport, the app would emit another `compile start` for the same digest with `joinedInFlight=false`. That made native rendering look like it was "rendering again and again" and could put CSS/DOM compilation back into the scroll burst.

Transient compiles remain cancellable because they are intentionally tied to short-lived preview/test rendering.

### Native Height Measurement Debounce

`RichHtmlBubbleBlock` ignores repeated `onSizeChanged` callbacks for the same composable instance when the measured height has not changed.

This avoids duplicate `height delta` logs, repeated height-cache writes, and redundant scheduler success marking during the same native render lifetime. A new height is still recorded immediately when the measured value changes.

### Debug Telemetry Signature Gate

`RichHtmlRenderTelemetry` de-duplicates identical debug metadata for the same rich block and event type:

- `render-plan`
- `orchestrator`
- `route-closure`
- `height-delta`

This does not change rendering, routing, admission, height cache, or fidelity behavior. It only prevents repeated long debug strings from being rebuilt and logged when a Lazy cell leaves and re-enters the viewport with identical state.

Do not remove this gate while debugging scroll jank. Debug logging itself is part of the measured debug hot path, especially for APR-style native rich cards where a single render-plan line can be very long.

### TextFlow Inside Visual Wrappers

`RichTextFlowOptimizer` now has a conservative child-grouping pass for APR-style native cards:

- First, it still tries the old whole-container TextFlow path for fully safe text containers.
- If the parent container itself is not safe to flatten because it has visual styling such as background/border/shadow, it keeps that parent wrapper native.
- Inside that wrapper, consecutive safe text children can be grouped into a child `RichTextFlowBlock`.
- The grouping does not cross actions, details, images, math, SVG, tables, snapshot islands, unsupported blocks, positioned children, z-indexed children, flex containers, or grid containers.

This is a fidelity-preserving optimization: visual shells stay native, interactions stay native, and only browser-like inline text flow is collapsed into fewer Compose nodes.

### Nested Neutral Text Wrappers

`RichTextFlowOptimizer` also folds safe nested text wrappers such as `div > span > text` or `p > span > text` into TextFlow when the wrapper has no box, layout, positioning, visual-effect, generated-content, or interaction semantics.

The rule is intentionally narrow:

- Text-only wrapper styles such as color, font weight, font style, font family, letter spacing, decoration, vertical align, and inherited text flow can be carried as `AnnotatedString` spans or paragraph style.
- Wrapper paragraph or box semantics such as line-height, text-align, white-space, word-break, padding, margin, gap, border, shadow, background, explicit size, transform, overflow, flex/grid, z-index, cursor, animation, and generated content remain hard boundaries.
- If a child TextFlow block has a neutral wrapper style, the parent can consume its paragraphs while preserving the wrapper text style as spans. If that wrapper has box spacing, it stays as its own `RichTextFlowBlock` so padding/margin are still rendered by `StyledContainer`.

This targets APR-style cards where AI output often nests text in many neutral `span/div` layers. It reduces Compose nodes while keeping the same visible text styling and refusing cases that need real box layout.

### Body-Only Table Measure Guard

`SpannedDataTable` now treats "no header cells" as a valid table shape. Header height is computed with `maxOfOrNull() ?: 0`, so native HTML tables made only of `tr/td` rows do not crash during Compose measure.

This is a fidelity-preserving fix: the table still renders as a native table, and only the missing header row is represented as zero height. The associated instrumentation test uses a `vcp-root` card with a body-only table to prevent this regression from coming back.

### Nested Table Scroll Constraint Guard

Real-device crash evidence from the `5.5` history showed a second table failure:

```text
IllegalStateException: Horizontally scrollable component was measured with an infinity maximum width constraints
```

The triggering shape is a native `table` whose compiled CSS also carries `overflow:auto` or `overflow:scroll`. The rich renderer was wrapping the table in a CSS overflow horizontal scroll container, while `SpannedDataTable` already owns its own horizontal scroll. Compose measures children of a horizontal scroll with unbounded width, so the nested `horizontalScroll` inside the table crashes during measure.

The guard has two layers:

- `RichTableBlockView` strips only `Scroll/Auto` overflow from the table's outer `StyledContainer`; `Hidden` is preserved, and the table's native horizontal scroll remains.
- `SpannedDataTable` uses `BoxWithConstraints` and enables its internal `horizontalScroll` only when the incoming width is bounded. If a future parent accidentally measures it under infinity width, the table can still measure instead of crashing.

This is fidelity-preserving for chat cards: the cells, borders, caption, column widths, and table body stay native. The change removes a redundant scroll shell rather than flattening or snapshotting the table.

### Native Measure Exception Policy

`GuardedRichHtmlRender` records full render throwables with the rich render id and measure/place phase, then lets the original exception surface. Do not swallow exceptions thrown by Compose child measurement and continue the same layout pass; real-device evidence showed that this can corrupt Compose layout state and produce a secondary `layout state is not idle before measure starts` crash that hides the real source.

When a native renderer branch can fail, fix or guard the producer component before it enters Compose measure. Examples: body-only tables, malformed span grids, empty SVG commands, invalid inline placeholder mappings. The outer guard is diagnostic telemetry, not a safe recovery boundary for arbitrary measure failures.

## Rejected Experiments

### Scroll-Time Native Animation Freeze

Native CSS animations were disabled while the chat list was actively scrolling, with the card staying native and visible. This matched the product idea of prioritizing stable content over in-scroll animation playback, but it regressed the APR smoke.

Do not reintroduce this as a simple `scrollInProgress` flag around `RichHtmlRenderer.nativeAnimationsEnabled`. A future version would need a stable per-cell animation snapshot state that does not toggle composition locals on every scroll state change.

## What Repeated Native `render id` Means

`render id=... route=native` is emitted from the native measurement layout, not from the compiler. Seeing the same id again after scrolling back usually means the Lazy item was measured again after recycling or re-entering the viewport.

Treat it as a problem only when paired with one of these:

- repeated compile logs for the same digest and width
- height cache misses after a measured cache should exist
- frame-pressure spikes while `inlineActive=0`
- expensive telemetry or policy work in composition
- visual height jumps or placeholder deltas above the warning threshold

## Smoke Commands

Build and install:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug
adb -s <serial> install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

Before a focused scroll:

```powershell
adb -s <serial> logcat -c
adb -s <serial> shell dumpsys gfxinfo com.univcp.android.debug reset
```

After the scroll:

```powershell
adb -s <serial> logcat -d | Out-File -Encoding utf8 build\rich-render-smoke\logcat-apr-native.txt
adb -s <serial> shell dumpsys gfxinfo com.univcp.android.debug | Out-File -Encoding utf8 build\rich-render-smoke\gfxinfo-apr-native.txt
```

Useful log filters:

```powershell
Select-String -Path build\rich-render-smoke\logcat-apr-native.txt -Pattern "RichHtmlRender: render id=|render-plan id=|frame-pressure|height delta|orchestrator"
Select-String -Path build\rich-render-smoke\logcat-apr-native.txt -Pattern "FATAL EXCEPTION|RenderProcessGone|Unexpected state LookaheadMeasuring|ANR"
```

## Guardrails

- Do not put O(N) conversation scans in effects keyed by visible range or scroll state.
- Do not provide raw high-frequency scroll state to every rich renderer when a coarse admission state would do.
- Do not key route/orchestrator decisions on fields that change every pixel unless the decision genuinely needs pixel-level motion.
- Do not make `contentType` unique per message. Use `stableKey` for identity and fixed content type for reuse.
- Do not parse HTML, CSS, SVG paths, colors, borders, shadows, or dash effects from draw/measure callbacks.
- Do not downgrade already-live inline WebViews to placeholders just because the user is moving a finger.
- Do not cancel persistent rich HTML compiles solely because the current Lazy item left the viewport; let them finish into cache unless the scheduler explicitly marks them obsolete.
- Do not write height cache or telemetry on every unchanged `onSizeChanged` callback.
- Do not repeatedly log identical render-plan/orchestrator/height metadata while scrolling; use signature-gated telemetry or aggregate summaries.
- Do not flatten visual wrappers just to get TextFlow. Keep the wrapper and only group safe consecutive text children.
- Do not inline-flatten nested wrappers that carry box/layout semantics. Preserve them as native wrappers or standalone TextFlow blocks with their style intact.
- Do not toggle native animation enablement directly from `scrollInProgress`; the APR smoke showed that this can add composition/layer churn.
- Do not catch a child Compose measure exception and keep placing the parent in the same pass. Log the original throwable and fix the specific renderer branch instead.
- Do not assume tables always have headers. AI-generated HTML commonly emits body-only `table > tr > td` structures.
- Do not nest `horizontalScroll` for native tables. CSS `overflow:auto/scroll` on a table must not wrap `SpannedDataTable` in another horizontal scroll.
- Prefer adding telemetry snapshots outside the scroll hot path over logging every measure.
