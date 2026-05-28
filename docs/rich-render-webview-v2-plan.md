# UniVCP Rich Render WebView V2 Plan

## Goal

Inline WebView is kept as a high-fidelity fallback for dynamic rich bubbles, but it must behave like an industrial feed cell:

- Live when the user is reading or interacting.
- Deferred when history scrolling would introduce a never-seen WebView during list churn.
- Height-stable at all times.
- Never performs full-page bitmap capture or repeated JavaScript measurement on the scroll hot path.

## Reference Principles

- Compose `AndroidView` inside `LazyColumn` must use `onReset` and `onRelease` so the underlying View can be reused.
- Lazy cells keep stable keys and `contentType` so Compose reuses compatible item composition.
- WebView `onPause` and `onResume` are instance-level; `pauseTimers()` is global and must not be used for per-cell freezing.
- Hybrid container practice from VasSonic-style systems maps well to our case as: small pool, main-thread prewarm, strict clean/reset, and bounded live admission.

## V2 Admission Rules

1. Already-live inline WebView content has priority and should stay live while visible.
2. A never-seen inline WebView may enter live mode during gentle upward reading, but is deferred when recent frame pressure shows jank while another inline WebView is already live.
3. Fast scroll blocks new admission.
4. When the scroll settles and the cell is still visible, the deferred WebView may enter live mode.
5. Offscreen WebViews are released through the pool; height and frozen bitmap caches remain available for same-size placeholders.

## Hot Path Rules

- `AndroidView.update` may attach runtime and load a new document when necessary.
- `AndroidView.update` must not schedule repeated height JavaScript probes.
- `AndroidView.update` must not capture a full bitmap.
- Full bitmap capture is allowed only after page finished or release/reset, and skipped while scrolling.
- Height reports come from the injected page bridge and are cached by content digest.

## V2.1 Completed

- Inline WebView height cache keys now include content digest, viewport width bucket, and font scale bucket.
- Height cache is persisted lightly through shared preferences so a restarted chat can still reserve a realistic height.
- Debug telemetry records inline WebView admission, deferral, release, active count, and height cache hit/miss.
- A dedicated upward-history Macrobenchmark journey was added for the observed jank direction.
- Cache key behavior is covered by JVM tests.

## V3 Candidates

- Add a frame-pressure signal from `gfxinfo`/Perfetto-backed debug telemetry.
- Promote inline WebView admission to a shared scheduler with explicit states: `Deferred`, `Live`, `Releasing`, `Crashed`.
- Add a kill switch to disable inline WebView and force snapshot/dynamic preview if device memory or frame pressure is bad.

## V3 Started

- Added Choreographer-based chat scroll frame-pressure telemetry. Debug logs now emit `frame-pressure` for slow, janky, and severe frames while scrolling, including direction, visible cell range, fast-scroll state, and active inline WebView count.
- Frame-pressure samples are now retained in a short rolling window and used by inline WebView admission. New, never-seen WebViews are deferred only when upward history scrolling recently produced janky/severe frames with existing live inline content; already-seen/live content remains prioritized.
- Inline WebView admission is now staggered during active scrolling. If one WebView just became live, the next new live WebView waits briefly instead of attaching/loading in the same frame cluster.
- Inline WebView rendering now has an explicit phase model: `Deferred -> Acquiring -> Attaching -> Loading -> Measuring -> Live -> Freezing -> Releasing -> Released`, with `Crashed` as a terminal fallback path. Debug logs emit `inline-webview-phase` so frame spikes can be correlated with attach/load/resume/release instead of only active count.
- The JS height bridge now carries the inline content id. Late callbacks from a previously reused WebView document are ignored, reducing wrong-height oscillation during fast reuse.
- Phase transitions are filtered to ignore `about:blank`/tag-mismatched callbacks after reset, and late page callbacks no longer regress an already-live WebView back to `Loading`/`Measuring`.
- Inline WebView height measurement was moved to a v3 cache key. The wrapper no longer forces `html/body min-height:100%`, and JS measurement now prioritizes real content bounds over viewport-derived `scrollHeight`, preventing large blank tails under short dynamic pages.
- The device seed exporter can filter by conversation title.
- The preferred V3 real-device seed is Uika / `文字游龙`, exported locally with:

```bash
python scripts/export_device_render_seed.py --serial 192.168.6.121:40011 --assistant-name Uika --conversation-title 文字游龙 --out-dir .codex-artifacts/device-render-seed --include-alternatives
```

Current local report for this seed: 7 assistant text parts, 18,200 text chars, 7 rich roots, 3 buttons, 1 blur filter, 1 mix-blend, 3 transitions, and 7 dynamic runtime parts.
