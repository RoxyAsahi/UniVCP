# UniVCP Native Rich Render Architecture Roadmap V1-V6

Date: 2026-05-29

This document records the long-term native Compose rich-rendering architecture plan. It keeps the current direction: fidelity-first rendering, Compose native where confidence is high, WebView snapshot / inline WebView where browser fidelity is required.

The goal is not to replace the current path. The goal is to make the current path more systematic, testable, and closer to large IM/feed rendering architecture.

Architecture overview: `docs/rich-render-target-architecture.md`.

## Product Goal

The user-facing goal is:

- Chat bubbles should look as close to WebView as practical.
- Native Compose should be used when it can preserve high visual fidelity and interaction.
- Static visual effects that Compose cannot reproduce well should use WebView snapshot.
- Runtime content that truly needs JavaScript/canvas/WebGL should use governed inline/fullscreen WebView.
- Scroll performance should remain stable, especially upward history scrolling.

In other words:

```text
Fidelity first, but route each subtree to the cheapest renderer that can preserve that fidelity.
```

## Current Baseline

The project already has the right baseline:

- `ChatRenderCell` flattens chat messages into stable LazyColumn cells.
- Lazy items use `stableKey` and `contentType`.
- `RichHtmlCompiler -> RichHtmlRenderModel -> RichHtmlRenderer` separates compilation and rendering.
- `RichHtmlRenderScheduler` handles viewport-aware native admission and prewarm.
- `RichHtmlSnapshotPolicy` routes complex static content to WebView snapshot.
- `InlineDynamicWebViewBlock` has pool/admission/state-machine work for dynamic inline WebView.
- `文字游龙` is a high-value real device seed with multiple dynamic/rich WebView cases.

## Research Notes And Borrowed Practices

### Android Lazy Feed Guidance

Android official docs recommend stable keys for lazy list items, and note that `contentType` helps Lazy layouts reuse item composition for different item types. This supports the existing `ChatRenderCell` direction.

Borrowed requirement:

- Keep cell-level virtualization as the only chat list path.
- Add more stable content-type separation if new renderer backends appear, for example `TextFlowCell`, `NativeBoxCell`, `SnapshotIslandCell`, `InlineWebViewCell`.

References:

- https://developer.android.com/develop/ui/compose/lists
- https://developer.android.com/develop/ui/compose/quick-guides/content/build-list-multiple-item-types

### Compose Performance Guidance

Android official Compose performance docs emphasize:

- Use `remember` for expensive calculations.
- Use `derivedStateOf` for high-frequency scroll-derived state.
- Defer state reads to the latest possible phase.
- Avoid backwards writes.
- Keep parameters stable where possible.

Borrowed requirement:

- Render models and render plans should be immutable/stable.
- Scroll-derived state should stay in scheduler/orchestrator layers, not be read deeply by every rich renderer subtree.
- Renderer Composables should consume precomputed plans rather than recalculating route/style decisions during composition.

References:

- https://developer.android.com/develop/ui/compose/performance/bestpractices
- https://developer.android.com/develop/ui/compose/performance/stability
- https://developer.android.com/jetpack/compose/phases

### Macrobenchmark And Baseline Profiles

Android Macrobenchmark is intended for large end-user interactions such as startup, scrolling, and animations. Official docs discourage emulator-only performance conclusions and recommend profileable target apps.

Borrowed requirement:

- Rich render roadmap must be benchmarked in release/profileable flows.
- `文字游龙` and synthetic rich seeds should become named Macrobenchmark journeys.
- Baseline Profiles should include opening chat, scrolling rich history, first rich HTML render, snapshot open, and dynamic preview open.

References:

- https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- https://developer.android.com/topic/performance/benchmarking/benchmarking-overview
- https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile

### WebView Governance

Compose `AndroidView` docs note that Android Views in Lazy containers are expensive to discard/recreate unless reuse hooks are used. Android WebView docs also say `pauseTimers()` pauses timers for all WebViews globally, while `setOffscreenPreRaster(true)` can reduce artifacts but uses more memory and should be limited to a small number of screen-sized WebViews.

Borrowed requirement:

- Inline WebView remains a governed scarce resource.
- Avoid `pauseTimers()` for per-cell lifecycle.
- Use `onPause()/onResume()` per WebView.
- Use offscreen preraster only for visible/about-to-visible WebViews and only under strict count/memory guard.

References:

- https://developer.android.com/reference/kotlin/androidx/compose/ui/viewinterop/AndroidView
- https://developer.android.com/reference/android/webkit/WebView
- https://developer.android.com/reference/android/webkit/WebSettings#setOffscreenPreRaster(boolean)

### CSS Parsing

`ph-css` is a Java CSS parser and builder. Its README explicitly notes that it has no logic for applying CSS onto HTML elements. That fits our architecture: use it for parsing/tokenization, not as a full style engine.

Borrowed requirement:

- Use `ph-css` to reduce hand-written CSS parsing.
- Keep cascade, selector whitelist, capability analysis, and safety budgets in UniVCP-owned code.

Reference:

- https://github.com/phax/ph-css

### HTML Safety

jsoup Cleaner/Safelist provides allowlist-based HTML cleaning and protocol restrictions. This aligns with the existing `RichHtmlSafety` layer but should be introduced carefully in report-only mode first.

Borrowed requirement:

- Add `RichHtmlSanitizer` report-only mode first.
- Compare sanitizer output with existing safety decisions.
- Do not silently change rendering of historical messages until reports are clean.

References:

- https://jsoup.org/cookbook/cleaning-html/
- https://javadoc.io/doc/org.jsoup/jsoup/latest/org/jsoup/safety/Safelist.html

### Text / Rich Content Architecture

Feishu/Lark RichTextVista publicly describes a high-performance extensible rich text component with rich styling and list-performance orientation. The useful principle is not a direct Android drop-in, but the architecture direction: normalize input into a rich text/content representation, flatten text-heavy content, and avoid one view per source node.

Borrowed requirement:

- Add a UniVCP-owned `RichContentAst`.
- Add `RichTextFlowBlock` for text-heavy HTML/Markdown/Protocol.
- Prefer attributed text / paragraph runs over deep Compose node trees for inline content.

Reference:

- https://www.github-zh.com/projects/982030760-rich-text-vista

### Layout Engine Research

Taffy is a Rust layout engine implementing Flexbox and CSS Grid for custom renderers. It is useful as research, but Android integration would require JNI/WASM/bridging and measurement callbacks.

Borrowed requirement:

- Keep official Compose FlexBox as the default native flex path.
- Keep custom grid for high-frequency VCP cards.
- Treat Taffy as an offline research spike only if real seed reports prove grid/flex fidelity is the dominant issue.

References:

- https://taffylayout.com/
- https://github.com/DioxusLabs/taffy

### Image And SVG

Coil has SVG support through `coil-svg`. AndroidSVG can parse SVG from string/input stream/resource and render to Canvas/Picture. These are useful as lower-level building blocks, not wholesale renderer replacement.

Borrowed requirement:

- Continue using Coil as the image loading/cache base.
- Use AndroidSVG as a spike for complex static SVG fallback.
- Keep current SVG IR for simple controlled SVG where telemetry and budget enforcement matter.

References:

- https://coil-kt.github.io/coil/svgs/
- https://bigbadaboom.github.io/androidsvg/api_summary.html

### Large Feed Architecture

Meta Litho was designed for complex products such as News Feed and emphasizes smooth UI performance. The important lesson for this project is not to migrate to Litho, but to borrow feed architecture principles: precompute models, reduce runtime work, and make scrolling a first-class benchmark.

Borrowed requirement:

- Continue with Compose, but move expensive route/style/layout decisions out of composition and into immutable plans.

Reference:

- https://engineering.fb.com/2017/04/18/android/open-sourcing-litho-a-declarative-ui-framework-for-android/

## Target Native Architecture

```text
Assistant text / HTML / Markdown / Protocol
  -> MessageTextBlocks
  -> RichContentAst
  -> RichCapabilityAnalysis
  -> RichRenderPlan
  -> Renderer backends
       - TextFlowRenderer
       - BoxLayoutRenderer
       - TableRenderer
       - SvgRenderer
       - MediaRenderer
       - ActionRenderer
       - SnapshotIslandRenderer
       - InlineDynamicWebViewRenderer
  -> Unified RichRenderOrchestrator
  -> Height/Fidelity/Performance telemetry
```

### Key Concepts

#### RichContentAst

Canonical semantic content tree shared by HTML, Markdown, Protocol, and future structured VCP output.

Example nodes:

- `Document`
- `Card`
- `Paragraph`
- `TextRun`
- `ListBlock`
- `QuoteBlock`
- `CodeBlock`
- `Table`
- `Image`
- `Svg`
- `ButtonAction`
- `Details`
- `Formula`
- `BrowserOnly`

#### RichRenderPlan

Immutable plan generated before Compose rendering.

It should include:

- route per subtree
- native confidence
- visual risks
- estimated height
- text-flow candidate count
- render node count
- snapshot island count
- interaction count
- required caches
- fallback reason

#### RichTextFlowBlock

A paragraph/text-flow model that flattens text-heavy HTML into styled text runs instead of nested Compose containers.

This is expected to improve both fidelity and scrolling:

- Browser inline layout is closer to attributed text than nested native views.
- Compose node count decreases.
- Text measuring becomes more stable.

#### Snapshot Island

A subtree-level snapshot, not whole-bubble snapshot.

Example:

```text
Card native
  Text native
  Button native
  Complex SVG snapshot island
```

This preserves interaction while using WebView fidelity for visual-only difficult areas.

#### Unified RichRenderOrchestrator

Central place for all route/admission decisions:

- native compile budget
- native first presentation budget
- snapshot queue budget
- inline WebView live budget
- frame pressure
- height cache confidence
- circuit breaker
- historical failures

## Requirements V1-V6

### V1: RenderPlan And Observability

Goal:

Make current native rendering explainable before changing behavior.

Requirements:

- Add `RichRenderPlan` data model.
- Produce a plan for every `RichHtmlCell`.
- Plan initially mirrors existing behavior.
- Record debug telemetry:
  - route
  - native confidence
  - risk score
  - DOM/node count
  - render block count
  - visual hints
  - estimated height
  - cached height hit
  - snapshot/dynamic reason
- Add seed report fields for `文字游龙`.
- Add tests that plan generation is deterministic and does not include message body text in telemetry.

Acceptance:

- No visible behavior change.
- Existing rich render tests continue passing.
- Developer logs can answer: "Why did this bubble go native/snapshot/dynamic?"

### V2: RichContentAst And TextFlow

Goal:

Reduce native renderer tree depth and improve browser-like text fidelity.

Detailed implementation requirements: `docs/rich-render-v2-ast-textflow-requirements.md`.

Requirements:

- Add `RichContentAst` package.
- Convert simple HTML text/list/button/card cases into AST.
- Add `RichTextFlowBlock`.
- Add `TextFlowRenderer`.
- Convert span-heavy/paragraph-heavy content into paragraph-level `AnnotatedString` runs.
- Keep fallback to existing `RichHtmlRenderModel` for unsupported nodes.
- Add telemetry:
  - source DOM node count
  - AST node count
  - TextFlow paragraph count
  - render node reduction ratio

Acceptance:

- On real seeds, simple text-heavy rich HTML reduces render block count by at least 40%.
- Visual output for paragraphs, inline styles, lists, code spans, and links is no worse than current native path.
- No loss of button action behavior.

### V3: Persistent Height And Unified Orchestrator

Goal:

Make upward history scrolling height-stable and move route/admission out of leaf Composables.

Detailed implementation requirements: `docs/rich-render-v3-height-orchestrator-requirements.md`.

Requirements:

- Add versioned persistent native height cache.
- Key by digest, width bucket, fontScale, density, theme bucket, renderer version, and content type.
- Track height confidence:
  - `Estimated`
  - `MeasuredNative`
  - `MeasuredSnapshot`
  - `MeasuredInlineWebView`
- Add `RichRenderOrchestrator`.
- Move admission decisions from individual Composables into orchestrator where possible.
- Use `derivedStateOf`/deferred reads for scroll-derived state.
- Add debug UI or telemetry summary:
  - native queue
  - snapshot queue
  - inline WebView active count
  - height hit rate
  - frame pressure

Acceptance:

- History upward scroll uses cached height placeholders.
- Height delta warnings are visible in debug telemetry.
- No regression in inline WebView "already visible content should stay visible" behavior.

### V4: Subtree Routing And Snapshot Islands

Goal:

Improve visual fidelity without turning whole interactive cards into static images.

Detailed implementation requirements: `docs/rich-render-v4-snapshot-islands-requirements.md`.

Requirements:

- Add subtree capability analysis.
- Identify snapshot candidates inside native cards:
  - complex SVG
  - mask
  - clip-path path/polygon
  - mix-blend-mode
  - backdrop-filter
  - unsupported filter
  - complex multi-background
- Add `SnapshotIslandRenderer` for visual-only subtrees.
- Preserve native text/buttons around snapshot islands.
- Add hit-testing rules:
  - snapshot islands are visual-only
  - action buttons remain native when possible
  - whole-card open preview remains available

Acceptance:

- A card with complex SVG plus buttons can keep buttons native.
- Whole-bubble snapshot count decreases for mixed interactive cards.
- Visual fidelity improves for browser-only visual islands.

### V4.1: Stability Closure

Goal:

Close V3/V4 stability risks before parser and fidelity-platform work.

Detailed implementation requirements: `docs/rich-render-v4_1-stability-requirements.md`.

Requirements:

- Release inline WebView admission after render-process-gone/crash.
- Ensure cached compiled models still respect scheduler/orchestrator policy.
- Make snapshot island style boundaries explicit and tested.
- Improve island queue, fallback, and height telemetry.

Acceptance:

- Inline WebView crash cannot exhaust global live WebView slots.
- Cached model hits still obey fast-scroll first-render gates.
- Snapshot islands do not double-apply padding/border/background.
- V1-V4 regression tests continue passing.

### V5: Parser, Safety, Media, And SVG Hardening

Goal:

Reduce hand-written parser fragility and use proven libraries in bounded roles.

Detailed implementation requirements: `docs/rich-render-v5-parser-safety-media-svg-requirements.md`.

Requirements:

- Split `RichHtmlCompiler.kt` into:
  - `RichCssParser`
  - `RichCssCascade`
  - `RichHtmlAstCompiler`
  - `RichRenderModelCompiler`
  - `RichVisualHintAnalyzer`
  - `RichHtmlSanitizer`
- Expand `ph-css` usage for declaration/shorthand parsing:
  - background
  - border
  - font
  - filter
  - color functions
  - animation/transition tokens
- Add jsoup Cleaner/Safelist report-only mode.
- Unify image loading through Coil request builders:
  - `img`
  - background image
  - list-style-image
- Run AndroidSVG spike for complex static SVG fallback.

Acceptance:

- Parser equivalence tests prove no behavior loss for existing fixtures.
- Sanitizer report-only output does not unexpectedly remove supported safe VCP content.
- Complex SVG spike has measured fidelity/time/memory data before adoption.

### V6: Fidelity Gate And Rich Rendering Platform

Goal:

Make fidelity measurable and turn UniVCP rich rendering into a stable platform.

Detailed implementation requirements: `docs/rich-render-v6-fidelity-platform-requirements.md`.

Requirements:

- Add fidelity gate for curated fixtures:
  - native render screenshot
  - WebView reference screenshot
  - snapshot screenshot
  - route reason
  - visual hint buckets
- Add frequency-weighted report:
  - high frequency/high impact -> native or snapshot priority
  - high frequency/low impact -> approximation
  - low frequency/high impact -> snapshot/dynamic fallback
  - low frequency/low impact -> hint only
- Add Macrobenchmark journeys:
  - `文字游龙` upward history scroll
  - mixed native/snapshot feed
  - text-heavy long assistant response
  - SVG/table/card-heavy feed
  - dynamic inline WebView feed
- Add Baseline Profile journeys for chat open and rich feed scroll.
- Version the AST/render plan format.
- Prepare future structured VCP rich payload support, so HTML becomes one import format rather than the internal source of truth.

Acceptance:

- Every route decision has telemetry.
- Every high-impact visual gap has either native work item, snapshot policy, or dynamic fallback.
- Native render node count, compile time, frame jank, height delta, and route distribution are tracked over time.

## Requirement Backlog By Subsystem

### A. Native Fidelity

- Add browser baseline style tokens as data, not scattered constants.
- Improve text metrics calibration against WebView reference samples.
- Add `RichTextFlowBlock`.
- Add subtree snapshot islands.
- Add visual hint frequency ranking.

### B. Native Performance

- Make render plan immutable and stable.
- Reduce Compose node count for text-heavy content.
- Avoid renderer object allocation in draw paths.
- Extend prepared draw cache beyond SVG:
  - dashed/dotted border
  - gradients
  - background tile recipes
  - clip/mask shapes
  - shadow recipes
- Add render node count and allocation trace checks.

### C. Parser And Safety

- Move CSS parsing to `RichCssParser`.
- Keep project-owned cascade and capability analysis.
- Add jsoup Cleaner/Safelist report-only mode.
- Add CSS parser equivalence tests.
- Keep dangerous JavaScript/runtime features out of native and snapshot routes.

### D. Layout

- Keep Compose FlexBox as primary flex renderer.
- Keep current custom grid for high-frequency static VCP layouts.
- Add layout cost scoring.
- Use Taffy only as an offline spike if grid/flex fidelity dominates real reports.

### E. WebView Fallback

- Keep inline WebView pool/admission.
- Keep WebView count small.
- Persist height and frozen visual placeholders.
- Do not use global `pauseTimers()` for per-cell behavior.
- Avoid offscreen preraster except one or few visible/about-to-visible WebViews.

### F. Test And Benchmark

- JVM:
  - AST conversion tests
  - render plan determinism
  - parser equivalence
  - route policy
  - seed report privacy
- Compose/UI:
  - text-flow visual sanity
  - action button preservation
  - snapshot island card
  - long list scroll visibility
- Device/perf:
  - profileable Macrobenchmark
  - frame timing
  - height delta
  - compile queue wait
  - snapshot queue wait
  - inline WebView phase/frame correlation

## Implementation Order

1. V1 first: add `RichRenderPlan` and report-only telemetry.
2. V2 next: add `RichContentAst` and `RichTextFlowBlock` behind feature flag.
3. V3: persistent height cache and orchestrator.
4. V4: subtree snapshot islands.
5. V5: parser/safety/media/svg hardening.
6. V6: fidelity gate and platform versioning.

## Non-Goals

- Do not attempt to implement a full browser in Compose.
- Do not turn all content into live WebView.
- Do not replace current renderer wholesale with a new engine.
- Do not introduce Rust/JNI layout engine into default path without seed-backed evidence.
- Do not make snapshot the default for interactive cards when native interaction can be preserved.

## Bottom Line

The existing path is correct. The next generation is to make it plan-driven:

```text
HTML is input.
RichContentAst is the truth.
RichRenderPlan is the contract.
Renderer backends preserve fidelity by capability.
Telemetry decides priorities from real chat data.
```
