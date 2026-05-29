# Rich Render V5 Development Requirements: Parser, Safety, Media, And SVG Hardening

Date: 2026-05-29

Depends on:

- V4.1 stability closure
- V1 render plan telemetry
- V2 `RichContentAst` / `RichTextFlowBlock`
- V3 height/orchestrator
- V4 snapshot islands

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

V5 makes the native rich renderer easier to extend safely.

This stage should reduce parser fragility, harden HTML safety, unify media loading, and improve SVG fallback choices. It should not chase broad visual-diff automation yet; that belongs to V6.

The product goal remains fidelity-first:

```text
Use native Compose when confidence is high.
Use snapshot islands or whole snapshot when browser fidelity is required.
Use governed inline WebView only for real runtime content.
```

## Required Scope Split

### V5A: Compiler Module Split

Current pressure point:

```text
RichHtmlCompiler.kt
  CSS parsing
  selector matching
  cascade
  variables
  DOM compile
  visual hints
  table/svg/list/layout compile
  animation analysis
```

Required split:

```text
richtext/compiler/
  RichCssParser.kt
  RichCssCascade.kt
  RichCssSelectorMatcher.kt
  RichHtmlAstCompiler.kt
  RichRenderModelCompiler.kt
  RichVisualHintAnalyzer.kt
  RichHtmlCompiler.kt
```

Rules:

- Keep public call sites stable: `RichHtmlCompiler.compile(...)` should remain the main entry point.
- Move code in small mechanical steps.
- Preserve existing behavior first; no broad CSS semantics change in the same patch as the split.
- Add package-private data types only when they clarify boundaries.
- Keep existing test fixtures passing after every split.

Acceptance:

- `RichHtmlCompiler.kt` becomes an orchestrating facade.
- CSS parsing/cascade/model compilation can be tested independently.
- No behavior loss in existing render seed and policy tests.

### V5B: CSS Parser And Cascade Hardening

Goal:

Reduce hand-written CSS declaration parsing errors while keeping UniVCP-owned capability analysis.

Requirements:

- Introduce a parser adapter layer, for example `RichCssParser`.
- Parser output must be normalized into project-owned data classes.
- If `ph-css` is adopted, use it for tokenization/declaration parsing only.
- Do not outsource cascade, safety, selector whitelist, or render capability decisions.
- Keep a fallback parser path or guarded failure behavior during adoption.

Target declaration groups:

- color functions: `rgb`, `rgba`, `hsl`, `hsla`, named colors where supported
- background shorthand and multiple layers
- border shorthand and per-side border
- font shorthand basics
- filter/backdrop-filter tokens
- mask/clip-path tokens for capability hints
- animation/transition tokens

Cascade/index requirements:

- Add lightweight rule indexes:
  - `idRules`
  - `classRules`
  - `tagRules`
  - `universalRules`
  - `complexRules`
- Elements should only inspect candidate rules where possible.
- Specificity and source order must remain deterministic.
- Unsupported selectors should be counted in telemetry, not silently misapplied.

Acceptance:

- Parser equivalence tests prove current supported CSS still maps to the same `ComputedStyle`.
- Complex selector and unsupported selector behavior is explicit.
- Long style sheets compile with less rule-scanning overhead or at least expose rule count/selector cost telemetry.

### V5C: Report-Only Sanitizer

Goal:

Add a second safety lens without changing historical rendering behavior prematurely.

Requirements:

- Add `RichHtmlSanitizer`.
- Use allowlist-based cleaning in report-only mode.
- Compare sanitizer findings with existing `RichHtmlSafety`.
- Report:
  - removed tags count
  - removed attributes count
  - dangerous protocol count
  - event handler count
  - script/runtime reason
  - whether existing safety layer agreed
- Do not log raw HTML, text, URLs, or attribute values.
- Do not feed sanitized HTML into rendering by default in V5.

Acceptance:

- Sanitizer report appears in debug telemetry and seed reports.
- Existing rendering output does not change because of sanitizer report-only mode.
- Unsafe/runtime content still routes to dynamic/preview according to existing policy.

### V5D: Media Loading Unification

Goal:

Make images and background media use one bounded, cache-aware path.

Requirements:

- Add `RichMediaRequest` / `RichMediaLoader` wrapper around Coil.
- Route these through the wrapper:
  - `<img>`
  - CSS `background-image: url(...)`
  - `list-style-image`
  - future AST media nodes
- Normalize request keys by:
  - URL/source digest
  - size bucket
  - theme if relevant
  - renderer version
- Enforce safety:
  - supported protocols only
  - no unrestricted local file access
  - explicit handling for data URIs
- Add telemetry:
  - media request count
  - cache hit/miss if available
  - decode failure count
  - oversized image rejection count

Acceptance:

- Native image blocks and CSS background images do not maintain separate ad hoc request logic.
- Media failure degrades to stable placeholder/fallback.
- Existing image rendering behavior does not regress.

### V5E: SVG Hardening And Fallback Spike

Goal:

Improve complex static SVG fidelity without making all SVG live WebView.

Requirements:

- Keep current SVG IR for simple SVG that native renders well.
- Expand prepared draw cache where safe:
  - parsed paths
  - dash path effects
  - paints
  - gradients
  - transforms
- Add an AndroidSVG spike behind a guarded adapter:
  - parse static SVG string
  - render to `Picture`/bitmap or draw command where practical
  - measure render time, memory, and fidelity against current native and WebView snapshot
- Route candidates:
  - simple SVG -> current native IR
  - complex static SVG within budget -> AndroidSVG or snapshot island, depending on spike result
  - runtime/foreignObject/script SVG -> dynamic/preview or snapshot according to safety

Acceptance:

- Spike report exists before AndroidSVG is used in default path.
- No draw-stage path parsing or paint allocation is reintroduced.
- Complex SVG fallback choice is based on measured seed data.

### V5F: Prepared Draw Cache Expansion

Goal:

Move repeat object creation out of scroll-time draw paths.

Targets:

- dashed/dotted border recipes
- gradient brush recipes
- background tile recipes
- shadow recipes
- clip/mask shape recipes
- color matrix/filter recipes

Rules:

- Prepared objects must be keyed by immutable style signatures.
- Draw phase should consume prepared recipes.
- Cache size must be bounded.
- Telemetry should count prepared cache hit/miss.

Acceptance:

- No hot draw path repeatedly creates expensive `Paint`, `DashPathEffect`, path parser results, or equivalent objects for stable style input.
- Existing rendering remains visually equivalent.

### V5G: CSS Equivalence Suite

Goal:

Prove that parser/cascade/index changes preserve supported CSS behavior.

This suite is different from simple parser unit tests. It must compare observable style results:

```text
CSS input
  -> old parser/cascade result
  -> new parser/cascade/index result
  -> optional WebView getComputedStyle reference
  -> normalized comparable style snapshot
```

Research references:

- WPT is the cross-browser Web platform test suite and includes CSS tests.
  - https://github.com/web-platform-tests/wpt
- WPT documents reftests and browser-facing test structure.
  - https://web-platform-tests.github.io/wpt-actions-test/writing-tests/index.html
- `getComputedStyle()` returns resolved style values after stylesheets are applied and computation is resolved.
  - https://developer.mozilla.org/en-US/docs/Web/API/Window/getComputedStyle
- MDN cascade docs describe the cascade order: origin/layer, specificity, scoping proximity, and source order.
  - https://developer.mozilla.org/docs/Web/CSS/CSS_cascade/Cascade
- W3C CSS2 cascade section defines computed values, cascade, inheritance, and specificity.
  - https://www.w3.org/Style/css2-updates/css2/cascade.html
- CSSTree provides a spec-oriented parser/lexer and validation model that can inspire parser fixture structure.
  - https://github.com/csstree/csstree
- `postcss-parser-tests` provides parser torture cases and roundtrip-style parser validation ideas.
  - https://www.skypack.dev/view/postcss-parser-tests

#### Layer 1: Declaration Parser Equivalence

Purpose:

Verify that `RichCssParser` and legacy parser agree for supported declaration input.

Input:

```text
style="color:red;background:linear-gradient(...);border:1px solid #ddd"
```

Compare:

- property names
- normalized values
- shorthand expansion
- unsupported token classification
- parser fallback reason

Required fixture groups:

- colors:
  - hex
  - rgb/rgba
  - hsl/hsla
  - named colors
  - invalid colors
- lengths:
  - px
  - em/rem where supported
  - percent where supported
  - negative values
- background:
  - color
  - image
  - gradient
  - repeat
  - position
  - size
  - multiple layers
- border:
  - shorthand
  - per-side
  - radius
  - dashed/dotted/double
- font/text:
  - font-size
  - font-weight
  - line-height
  - text-decoration
  - letter-spacing
  - white-space
- filters:
  - blur
  - opacity
  - brightness
  - unsupported functions
- animation/transition:
  - duration
  - delay
  - iteration count
  - layout-affecting properties
- variables:
  - custom property declarations
  - `var(--x)`
  - fallback values
  - unresolved variables

Acceptance:

- Supported declarations produce the same normalized `ComputedStyle` fields as before.
- Unsupported declarations are classified, not silently dropped.
- Parser fallback count is reported.

#### Layer 2: Cascade And Selector Equivalence

Purpose:

Verify that rule matching, specificity, inheritance, and source order remain equivalent when selector indexing or parser changes.

Compare:

```text
legacy full scan matcher
  vs
indexed matcher
```

for the same HTML/style fixture.

Required fixture groups:

- simple selectors:
  - tag
  - class
  - id
  - universal
- compound selectors:
  - `div.card`
  - `#root .title`
  - `button.primary.active`
- combinators:
  - descendant
  - child
  - adjacent sibling where supported
  - general sibling where supported
- selector lists:
  - `h1, h2, .title`
- specificity:
  - id beats class
  - class beats tag
  - later source order wins at equal specificity
  - inline style wins over author rule
  - `!important` behavior where supported
- inheritance:
  - color
  - font
  - line-height
  - custom variables
- pseudo/selectors:
  - `:root`
  - unsupported pseudo-class
  - unsupported pseudo-element
  - `:has` rejected or complex
- variables:
  - root variable
  - inherited variable
  - overridden variable
  - fallback variable

Required output per fixture:

- matched rule ids per element
- legacy matched rule ids
- indexed candidate rule count
- indexed matched rule count
- computed style snapshot
- mismatch fields
- fallback/complex selector reasons

Acceptance:

- Indexed matcher and legacy matcher produce identical supported `ComputedStyle` snapshots.
- Any mismatch keeps legacy result and records `indexMismatch`.
- Unsupported selector behavior is explicit.
- Candidate rule count is materially lower for indexed fixtures.

#### Layer 3: WebView Computed Style Reference

Purpose:

Compare UniVCP computed style against browser resolved style for supported properties.

Use a small offscreen WebView or existing snapshot infrastructure to evaluate:

```javascript
const el = document.querySelector("[data-fixture-id='target']");
const style = window.getComputedStyle(el);
```

Normalize browser output before comparison:

- colors to rgba/ARGB
- lengths to px
- font weights to numeric or canonical keywords
- line-height to px/normal bucket
- unsupported properties to ignored or risk bucket
- URLs to redacted/digest-only markers

Supported comparison properties:

- color
- background-color
- opacity
- font-size
- font-weight
- font-style
- line-height
- text-align
- text-decoration
- letter-spacing
- white-space
- display
- padding
- margin
- border width/color/style
- border-radius
- width/height/min/max where deterministically measurable

Do not gate on:

- browser default UA styles unless explicitly modeled
- layout-dependent pixel values that native intentionally approximates
- dynamic pseudo state
- animations/transitions
- unsupported browser-only visuals

Acceptance:

- Browser reference tests exist for curated supported CSS.
- Differences are categorized:
  - `Equivalent`
  - `NormalizedEquivalent`
  - `KnownApproximation`
  - `Unsupported`
  - `Regression`
  - `ReferenceUnavailable`
- V6 fidelity report can include CSS equivalence status.

#### Fixture Format

Suggested file:

```text
app/src/test/resources/rich-css-equivalence/*.json
```

Suggested schema:

```json
{
  "id": "cascade-specificity-id-class",
  "category": "specificity",
  "html": "<div id=\"root\"><p class=\"title\" data-fixture-id=\"target\">Hello</p></div>",
  "css": "#root .title{color:red}.title{color:blue}",
  "targetSelector": "[data-fixture-id='target']",
  "expected": {
    "color": "#ff0000"
  },
  "supportedProperties": ["color"],
  "knownApproximation": []
}
```

Privacy:

- Fixtures must be synthetic or curated.
- Real seed-derived fixtures must be sanitized and approved.
- Reports must not log raw chat text, raw URLs, action payloads, or full HTML by default.

## Non-Goals

- Do not build a full browser CSS engine.
- Do not replace Compose renderer with WebView.
- Do not introduce visual diff gate; V6 owns that.
- Do not change message storage or VCP format.
- Do not make sanitizer output authoritative until report-only data is reviewed.
- Do not introduce Rust/JNI layout engines in default path.

## Required Telemetry

Add metadata-only telemetry:

- CSS rule count
- selector match candidate count
- unsupported selector count
- parser fallback count
- sanitizer report summary
- media request summary
- SVG route summary
- prepared draw cache hit/miss
- compile time by phase:
  - parse
  - cascade
  - DOM/AST compile
  - render model compile
  - optimizer

Telemetry must not include raw HTML, CSS bodies, text, URLs, button labels, or action payloads.

## Required Tests

Add tests:

- `RichCssParserTest`
  - color functions
  - background shorthand
  - border shorthand
  - filter tokens
  - animation tokens
- `RichCssCascadeTest`
  - specificity
  - source order
  - id/class/tag index candidate selection
  - unsupported selectors
- `RichHtmlSanitizerTest`
  - report-only output
  - no raw content in report
  - safety agreement/disagreement
- `RichMediaLoaderTest`
  - request key stability
  - unsafe protocol rejection
  - data URI handling
- `RichSvgFallbackPolicyTest`
  - simple native route
  - complex static route
  - runtime SVG rejection
- `PreparedDrawCacheTest`
  - stable keys
  - bounded cache
  - dash/paint/gradient recipe reuse

Existing tests that must continue passing:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichRenderPlanTest" --tests "me.rerere.rikkahub.ui.components.message.RichContentAstTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichTextFlowOptimizerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCacheTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestratorTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichSubtreeRoutePlannerTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichSnapshotIslandOptimizerTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest"
```

Compile:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

## Optional Seed Validation

Preferred seed:

- Assistant: `Uika`
- Conversation: `文字游龙`

Collect debug summaries:

- route distribution
- CSS parser fallback count
- sanitizer summary
- media request count
- SVG route count
- prepared cache hit rate
- snapshot island count

No visual diff gate is required in V5, but obvious regressions should block acceptance.

## Acceptance Criteria

V5 is accepted when:

- Compiler responsibilities are split into testable modules.
- CSS parsing/cascade behavior is equivalent for existing supported fixtures.
- Sanitizer report-only mode exists and does not alter rendering.
- Media loading goes through a unified bounded path.
- SVG fallback strategy is measured before broad adoption.
- Prepared draw cache is expanded without visual regression.
- Existing V1-V4 behavior remains stable.
- Debug build compiles and required tests pass.

## Developer Handoff Report Template

```text
Implemented scope:
- Compiler split:
- CSS parser/cascade:
- Sanitizer report-only:
- Media loader:
- SVG spike/fallback:
- Prepared draw cache:

Files changed:
-

Telemetry examples:
-

Tests passed:
-

Known risks / deferred:
-
```
