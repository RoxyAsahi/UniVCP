# UniVCP Rich Render Architecture Review V4

Date: 2026-05-29

This note reviews the current Compose-native rich rendering route after reading the existing development docs and code. It focuses on architecture rather than one-off CSS fixes.

## Executive Summary

The current direction is right: keep the chat feed native Compose first, flatten messages into stable cells, compile rich HTML into an internal render model, and use snapshot / dynamic WebView only as controlled fallback.

The next architectural step should not be "support the entire browser in Compose". It should be to turn the renderer into a richer content platform:

```text
input text / html / markdown / protocol
  -> stable block extraction
  -> normalized Rich Content AST
  -> capability analysis + route
  -> text-flow renderer / box-layout renderer / media renderer / svg renderer / browser fallback
  -> cell feed scheduler + height/fidelity/perf telemetry
```

This is close to the pattern used by large IM/feed apps: keep scroll hot paths made of stable reusable cells, flatten text-heavy content into a lightweight attributed-text representation, and isolate complex browser-only work behind strict admission and snapshot policies.

## Current Architecture Strengths

The project already has several enterprise-grade pieces in place:

- `ChatRenderCell` has converted chat rendering from message-level giant items into stable feed cells with `stableKey`, `contentType`, `messageId`, `blockIndex`, height class, and render risk.
- `RichHtmlCompiler -> RichHtmlRenderModel -> RichHtmlRenderer` is the right separation. Renderer consumes model data instead of reading DOM/CSS strings at draw time.
- Rich rendering admission is viewport-aware through `RichHtmlRenderScheduler`, with near-viewport prewarm and fast-scroll deferral.
- Snapshot fallback is bounded and cached; inline dynamic WebView now has pool/admission/state-machine hardening.
- The docs already define a realistic boundary: Compose native targets high-fidelity static VCP cards, not a full browser.
- Real device seed strategy exists, especially Uika / `文字游龙`, which is exactly the kind of regression corpus this renderer needs.

## Main Gaps Compared With Large IM / Feed Systems

### 1. Need a Canonical Rich Content AST Before Render Model

Current `RichHtmlRenderModel` is already useful, but it is still tightly shaped by HTML/CSS compilation. A large app style architecture usually inserts a normalized content AST first.

Proposed split:

- `RichContentAst`: semantic nodes such as paragraph, text span, list, quote, card, media, table, button/action, svg, formula, protocol.
- `RichStyle`: normalized text/box style, independent from raw CSS syntax.
- `RichRenderModel`: platform-specific Compose/native render instructions.

Benefits:

- Markdown, HTML, Protocol, future structured VCP payloads, and device seed fixtures can converge into one representation.
- Most text-heavy content can bypass DOM-like nested Compose trees and become a styled text-flow model.
- Fallback decisions can be made per subtree, not only per whole HTML block.

### 2. Text-Heavy Content Should Become Styled Text First

Feishu RichTextVista's public materials emphasize unified AST input and optimized list rendering. Their public article also describes the StyledString approach as a way to reduce view hierarchy and keep long-list scrolling smooth. We should borrow the principle, not the code.

For UniVCP this means:

- Keep current `RichTextBlock(AnnotatedString)` direction.
- Push more simple inline / paragraph / list cases into fewer `Text` or paragraph-level render units.
- Avoid creating a `StyledContainer` tree for purely textual nested spans.
- Introduce `RichTextFlowBlock` that can contain paragraphs, bullets, code spans, links, inline math, inline badges, and simple inline boxes without building one Compose node per original DOM element.

This is likely the biggest long-term win for "HTML/CSS/div handled by native Compose" because AI-generated cards contain many nested div/span nodes that are visually just text and decoration.

### 3. Scheduler Should Own All Rich Routes, Not Just Native Admission

Today route decisions are spread across:

- `RichHtmlClassification`
- `RenderRiskScore`
- `RichHtmlSnapshotPolicy`
- `RichHtmlRenderScheduler`
- `InlineDynamicWebViewBlock`
- `RichHtmlBubbleBlock`

This works, but the next version should centralize admission into a single `RichRenderOrchestrator`.

Target responsibilities:

- Native compile budget.
- Native first-presentation budget.
- Snapshot render queue budget.
- Inline WebView live budget.
- Height-cache confidence.
- Circuit breaker / repeated failure memory.
- Frame-pressure feedback.

The Composables should only render a decided state: `NativeReady`, `Preparing`, `SnapshotReady`, `InlineLive`, `Frozen`, `DynamicPreview`, `Fallback`.

### 4. Height Cache Should Be First-Class and Persistent

Inline WebView height cache is persistent, but native `RichHtmlHeightCache` is in-memory. For history scrolling, height stability is almost as important as render speed.

Recommended:

- Promote native height cache to a versioned persistent cache.
- Key by `digest + width bucket + fontScale + theme density + renderer version + contentType`.
- Track confidence: `Estimated`, `MeasuredNative`, `MeasuredSnapshot`, `MeasuredInlineWebView`.
- Use measured height as placeholder before compile/snapshot/live attach.
- Record height deltas in debug telemetry and seed report.

### 5. CSS and Safety Need Modularization

`RichHtmlCompiler.kt` is doing many jobs: CSS parsing, selector matching, style computation, DOM compilation, layout hints, animation parsing, and visual hint extraction.

Suggested module split:

- `RichCssParser`: ph-css-backed declaration/shorthand parsing and token utilities.
- `RichCssCascade`: selector matching, specificity, variable resolution, UA baseline.
- `RichHtmlSanitizer`: jsoup Cleaner/Safelist report-only first, then enforced.
- `RichHtmlAstCompiler`: DOM/Markdown/protocol to canonical AST.
- `RichRenderModelCompiler`: AST to Compose render model.

This keeps the system maintainable as fidelity work grows.

### 6. Layout Engine Should Stay Pragmatic

Current flex migration to official Compose `FlexBox` is a good choice. The custom grid `Layout` is acceptable for small chat cards, but should not become a full browser grid implementation.

Recommended:

- Keep Compose FlexBox as the default.
- Keep custom Grid for VCP high-frequency static subsets.
- Add layout cost scoring before render: node count, grid/table spans, positioned overlays, filters, SVG complexity.
- If cost is too high, route to snapshot or dynamic preview earlier.
- Only investigate Taffy as an offline spike if real seed reports show grid/flex gaps dominate.

### 7. Drawing Hot Path Still Has Cleanup Opportunities

Most SVG path/paint preparation has moved to `RichSvgPreparedDrawCache`, which is good. There are still renderer-side draw helpers that create objects for borders/backgrounds during drawing. This is not always fatal, but it should be audited with allocation traces.

Recommended:

- Extend prepared draw cache beyond SVG: border dash effects, repeated background tiles, gradients, masks, common shadow recipes.
- Add a debug allocation/perf seed for `文字游龙` and several SVG/table/card-heavy samples.
- Treat "object creation inside draw for repeated cells" as a P1 perf smell.

### 8. Visual Fidelity Needs a Frequency-Weighted Roadmap

The current docs list unsupported CSS/SVG features well. The missing piece is a prioritization report that says how often each feature appears in real chat data and how visible it is.

Recommended buckets:

- `High frequency + high visual impact`: implement native or snapshot policy.
- `High frequency + low impact`: stable approximation.
- `Low frequency + high impact`: snapshot fallback.
- `Low frequency + low impact`: visual hint only.

The Uika / `文字游龙` seed should become a named benchmark corpus, not just manual test data.

## What We Can Borrow From Existing Cases

### Android Official Guidance

- Lazy lists only compose/layout visible viewport items; large feeds should use lazy containers.
- Stable keys preserve item state across list changes.
- `contentType` lets Compose reuse item compositions among similar item types.
- Lazy list performance should be measured in release/R8/profileable mode, not debug.
- Compose performance guidance recommends `derivedStateOf`, deferred state reads, and avoiding state writes during composition.
- Macrobenchmark/Baseline Profiles should cover real interactions such as startup and scrolling on physical devices.
- WebView `setOffscreenPreRaster` can reduce artifacts for offscreen-to-onscreen animation, but uses more memory and should be limited to a small number of WebViews no larger than screen size.

### Feishu / RichTextVista Direction

RichTextVista exposes a unified AST-oriented flow and supports HTML/Markdown/custom AST inputs. Public descriptions emphasize optimized pipelines for high-performance lists, custom styles, and low view hierarchy through styled string style rendering.

For us, the actionable lesson is:

- Do not render every HTML element as a native view/composable.
- Normalize input first.
- Flatten text first.
- Preserve custom business components through extension points.
- Keep list reuse and performance as the first-class design constraint.

### WebView / Hybrid Practice

VasSonic-style hybrid systems are useful as a mental model for WebView governance: prewarm, cache, separate data loading from WebView attach, reuse or keep small pools, and never let feed scrolling create unbounded WebViews. We should keep WebView as a governed resource, not a renderer for every cell.

## Recommended V4 Roadmap

### V4.0: Architecture Cleanup

- Add `RichContentAst` package and define canonical nodes/styles/actions.
- Add adapters: HTML -> AST, Markdown block -> AST, Protocol -> AST.
- Keep existing compiler path working while introducing AST behind feature flags.
- Add tests proving HTML/Markdown/protocol produce stable AST ids and do not leak raw message text into telemetry.

### V4.1: Styled Text Flow Renderer

- Introduce `RichTextFlowBlock`.
- Convert paragraph/list/span-heavy content into paragraph-level `AnnotatedString` runs.
- Reduce Compose node count for simple text DOM by at least 40% on render seeds.
- Add telemetry: DOM node count vs render node count.

### V4.2: Unified Orchestrator

- Create `RichRenderOrchestrator`.
- Move route/admission state out of individual Composables where possible.
- Define route states and budgets for native compile, native presentation, snapshot, inline WebView.
- Expose debug summary in developer UI: visible rich cells, active WebViews, compile queue, snapshot queue, height hit rate, frame pressure.

### V4.3: Height Stability

- Make native height cache persistent and versioned.
- Add confidence classes and height delta warnings.
- Use measured height placeholders for native/snapshot/dynamic/inline routes.
- Add "history upward scroll" benchmark that fails if height delta or jank spikes exceed thresholds.

### V4.4: Parser/Safety Modularization

- Move ph-css-backed parsing into `RichCssParser`.
- Add jsoup Cleaner/Safelist report-only mode.
- Add parser equivalence tests for background/border/font/filter/color/flex/grid.
- Keep selector matcher under project control until tests prove replacement is safe.

### V4.5: Fidelity-Weighted Rendering

- Promote render seed report into a recurring quality gate.
- Add frequency-weighted visual hint report.
- Use reports to decide: native implementation, snapshot, dynamic preview, or ignore.
- Add screenshot/pixel-diff tests for a small curated fixture set if CI/device setup permits.

### V5: Rendering Platform

- Treat UniVCP rich rendering as a platform with stable IR versioning.
- Allow future VCP output to emit structured rich payloads directly, bypassing HTML when possible.
- Keep HTML compatibility as an import format, not the internal source of truth.
- Keep WebView as dynamic/full-fidelity fallback with strict pool/admission, not as default chat feed renderer.

## Immediate Next Engineering Steps

1. Add a `RichContentAst` skeleton and one read-only converter for simple HTML text/list/button/card cases.
2. Add seed report fields for `domNodeCount`, `renderBlockCount`, `textFlowCandidateCount`, `visualHintFrequency`, and `route`.
3. Add a JVM test using `文字游龙` exported seed metadata to quantify how many nodes could become text-flow.
4. Move native height cache toward persistent versioned storage.
5. Audit renderer draw-time allocations, starting with border dash, background layers, shadow, and SVG fallback paths.

## Bottom Line

The project is already on a strong route. The big shift now is conceptual:

- Stop thinking of the renderer as "HTML/CSS/div in Compose".
- Start treating HTML as one input frontend into a canonical rich content engine.
- Push text-heavy content into attributed text.
- Keep box/layout/media/SVG/browser features as separate render backends behind one scheduler.

That is the closest fit to large IM/feed practice while preserving UniVCP's high-fidelity creative output.

## References

- Android Developers: Lazy lists, stable keys, content type, and release-mode measurement.
- Android Developers: Compose performance best practices, including `derivedStateOf` and deferred state reads.
- Android Developers: Macrobenchmark and Baseline Profiles for scrolling/startup measurement on physical devices.
- Android Developers: WebView `setOffscreenPreRaster` memory guidance.
- LarkSuite RichTextVista public repository and related public article.
