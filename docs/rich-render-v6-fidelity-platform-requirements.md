# Rich Render V6 Development Requirements: Fidelity Gate And Rendering Platform

Date: 2026-05-29

Depends on:

- V4.1 stability closure
- V5 parser/safety/media/svg hardening
- Existing baseline profile and macrobenchmark modules

Owner model:

- Developer implements.
- Codex reviews plan adherence and performs acceptance after implementation.

## Goal

V6 turns rich rendering from a collection of renderer paths into a measurable platform.

The goal is not to demand pixel-perfect native Compose for every browser feature. The goal is to make every important gap visible, ranked, and routed:

```text
native when high confidence
snapshot island when browser visual fidelity matters
whole snapshot when the whole static block is browser-only
inline/fullscreen WebView when runtime behavior is required
```

V6 should answer:

- How close is native Compose to WebView for real chat records?
- Which visual gaps are most frequent?
- Which gaps matter most to the user?
- Which route decisions create jank or height jumps?
- Did a change improve or regress fidelity/performance?
- Which CSS selector/cascade patterns dominate compile cost?

## Research Notes: Selector And Cascade Indexing

This workstream borrows from browser engines and large cross-platform renderers.

### Chromium / Blink

Blink's `RuleSet` partitions rules into indexed buckets. The public source comments describe separate storage for rules that match against id, class, tag, shadow host, and other groups, with fallback universal rules. The same source exposes `IdRules`, `ClassRules`, `AttrRules`, `TagRules`, and `universal_rules_` collections.

Source:

- https://chromium.googlesource.com/chromium/src/+/e3dd4411efafbe9d4c285898e2c195c55612fe89/third_party/blink/renderer/core/css/rule_set.h

Older Blink source shows the practical key-selection order:

```text
id -> class -> custom pseudo -> pseudo bucket -> tag -> universal
```

and files rules into universal only when no more specific bucket is safe.

Source:

- https://chromium.googlesource.com/chromium/src/+/c0c27800d2322cd971bf1409ab1efec29aa74b78/third_party/WebKit/Source/core/css/RuleSet.cpp

Blink's element rule collector then gathers only the relevant id/class/tag/universal lists for the current element before matching and sorting:

- https://chromium.googlesource.com/chromium/blink/+/refs/heads/main/Source/core/css/ElementRuleCollector.cpp

Blink style invalidation also compiles style rules into feature sets and invalidation sets, so DOM changes can recalculate a smaller affected set instead of the whole tree:

- https://chromium.googlesource.com/chromium/src/+/master/third_party/blink/renderer/core/css/style-invalidation.md

Borrowed requirements for UniVCP:

- Build `RichCssRuleIndex` at compile time.
- Pick one safe key selector per rule.
- For each element, collect only likely rule candidates.
- Keep `complexRules` and `universalRules` as conservative fallback buckets.
- Add rule feature metadata for future incremental invalidation, even if V6 only uses it for reporting.

### Mozilla Stylo

Mozilla's Stylo write-up describes the rule tree and style sharing cache. The rule tree remembers matched/sorted rules for descendants when matching does not need to change; style sharing helps initial render when many nodes share the same rule list and computed style.

Source:

- https://hacks.mozilla.org/2017/08/inside-a-super-fast-css-engine-quantum-css-aka-stylo/

Borrowed requirements for UniVCP:

- Add optional `RichMatchedRuleSignature`.
- Cache matched rule chains for repeated text/card/list structures.
- Cache only when no dynamic pseudo, unsafe selector, CSS variable dependency, or ancestor-dependent complex selector makes reuse uncertain.
- Measure hit rate before making this path aggressive.

### Microsoft Edge / DevTools

Microsoft Edge's selector-performance article reinforces that selector matching is right-to-left and that the real cost is the amount of work per selector multiplied by how often it is matched. It also highlights problematic broad selectors such as accidental universal matches and substring attribute selectors.

Source:

- https://blogs.windows.com/msedgedev/2023/01/17/the-truth-about-css-selector-performance/

Borrowed requirements for UniVCP:

- Report high-cost selector forms:
  - universal rightmost selector
  - substring attribute selector
  - long descendant chain
  - repeated broad selector prefix
  - dynamic pseudo selectors
- Do not blindly ban these selectors; route them to `complexRules` and measure.

### Lynx / Cross-Platform Renderer Practice

Lynx documents a web-like but bounded selector/cascade model for a native-oriented cross-platform renderer. It supports type, class, id, universal, combinators, selector lists, and a limited pseudo-class set, and separately documents cascade order and specificity.

Sources:

- https://lynxjs.org/api/css/selectors
- https://lynxjs.org/zh/api/css/selectors
- https://lynxjs.org/guide/ui/styling.html
- https://lynxjs.org/zh/guide/ui/styling

Borrowed requirements for UniVCP:

- Keep a documented supported selector subset.
- Treat unsupported pseudo-elements and complex selectors as capability hints, not best-effort approximate native styling.
- Prefer deterministic fallback to snapshot/dynamic route over silently applying wrong native styles.

## Research Notes: AST And Rich Content Architecture

This workstream borrows from rich text editors, markdown processors, and browser-like content pipelines.

### ProseMirror / Tiptap

ProseMirror uses a schema-governed document model. The guide describes the document as a recursive tree of nodes, but its inline content is not represented as deeply nested DOM; inline markup is modeled as metadata/marks attached to inline content. ProseMirror also separates model, state, view, and transform modules.

Sources:

- https://prosemirror.net/docs/guide/
- https://prosemirror.net/docs/ref/
- https://tiptap.dev/docs/editor/core-concepts/introduction

Borrowed requirements for UniVCP:

- `RichContentAst` should become schema-governed, not just a loose sealed class tree.
- Inline content should be flattened into runs with marks where possible.
- HTML should be one import format, not the internal truth.
- AST transforms should be explicit passes, not hidden in Composables.

### Lexical

Lexical treats editor state as an immutable snapshot containing a node tree and selection. Its docs describe double buffering: updates mutate a work-in-progress state, then commit a new immutable state. Lexical also uses node transforms that run on dirty nodes.

Sources:

- https://facebook-lexical.mintlify.app/concepts/editor-state
- https://facebook-lexical.mintlify.app/concepts/transforms
- https://facebook.github.io/lexical-ios/documentation/lexical/introduction/

Borrowed requirements for UniVCP:

- Add immutable, versioned `RichContentDocument` snapshots.
- Use structural hashes and dirty flags for subtree-level reuse.
- Add transform passes:
  - normalize
  - sanitize/classify
  - resolve style
  - flatten text flow
  - route subtrees
  - lower to render model
- Cache by subtree digest, not only whole HTML digest.

### Slate

Slate emphasizes normalization: arbitrary pasted or generated content is corrected to a predictable shape. Its constraints make editor code less error-prone because every element has valid children and the tree is JSON-serializable.

Source:

- https://docs.slatejs.org/concepts/11-normalizing

Borrowed requirements for UniVCP:

- Add AST normalization rules before capability analysis.
- Ensure containers, paragraphs, lists, tables, and inline nodes have predictable children.
- Normalize unsupported or malformed nodes into explicit `BrowserOnly` / `Unsupported` nodes instead of letting later stages guess.

### Draft.js

Draft.js centers rendering around immutable `EditorState` / `ContentState`. It exposes a `blockMap`, entity map, and a generated block tree used for decorated/styled rendering ranges.

Sources:

- https://draftjs.org/docs/api-reference-editor-state
- https://draftjs.org/docs/api-reference-content-state/

Borrowed requirements for UniVCP:

- Keep block-level segmentation explicit.
- Produce renderer-friendly block/range structures from AST instead of asking Compose to rediscover them.
- Treat decoration/action/media ranges as metadata, not extra nested layout when possible.

### unified / mdast / hast / CommonMark

The unified ecosystem uses multiple syntax trees instead of one universal tree for every format. Markdown can keep information that HTML loses, while HTML has browser-oriented details that Markdown does not. CommonMark/cmark parses Markdown into an AST, supports AST manipulation, and then renders to multiple outputs.

Sources:

- https://unifiedjs.com/learn/guide/introduction-to-syntax-trees/
- https://github.com/syntax-tree/mdast
- https://github.com/commonmark/cmark
- https://github.com/commonmark/commonmark.js

Borrowed requirements for UniVCP:

- Keep source-specific import ASTs if useful:
  - `RichHtmlAst`
  - `RichMarkdownAst`
  - `RichProtocolAst`
- Normalize them into one canonical `RichContentDocument`.
- Do not force Markdown through HTML when semantic information is available earlier.
- Add import/export diagnostics so conversion loss is visible.

### Feishu / RichTextVista

Public RichTextVista coverage describes a high-performance rich text component that avoids deep Component trees and uses a lightweight styled-string direction. That aligns with the text-flow goal: text-heavy content should become attributed text/runs, not nested containers.

Sources:

- https://www.elecfans.com/d/6821779.html
- https://www.github-zh.com/projects/982030760-rich-text-vista

Borrowed requirements for UniVCP:

- Treat text flow as a primary backend, not a late optimizer.
- Prefer run/paragraph models for text-heavy generated HTML.
- Keep cards/actions/media as explicit nodes around the text flow.

## V1-V5 Maturity Audit

This audit records whether the earlier stages reached their original product goals. It is intentionally strict: "implemented" does not always mean "goal achieved".

### V1: RenderPlan And Observability

Current assessment: mostly achieved.

What is in place:

- `RichRenderPlan` exists.
- Plan telemetry exists.
- Seed/fidelity reports include plan fields.
- The plan is privacy-aware and report-oriented.

Remaining gap:

- The plan is still more diagnostic than contractual.
- Later route decisions are not fully derived from one immutable plan.

V6 gate:

- Every rich render artifact must carry the plan id/version.
- V6 reports must compare planned route versus actual route.

### V2: RichContentAst And TextFlow

Current assessment: partially achieved.

What is in place:

- `RichContentAst` exists.
- HTML AST stats exist.
- `RichTextFlowBlock` exists.
- A conservative `RichTextFlowOptimizer` exists.

Remaining gaps:

- AST is HTML-only in practice, despite `Markdown` and `Protocol` enum values.
- AST is report/supporting metadata, not the canonical source of truth.
- TextFlow optimization currently lowers from `RichHtmlRenderModel`, not from AST.
- AST does not yet represent full marks, entities, media ranges, actions, style capabilities, or route policy as a stable document contract.
- No AST schema/version/normalization gate exists.
- No subtree dirty tracking or structural hashing exists.

V6 gate:

- Promote AST from report helper to canonical `RichContentDocument`.
- Add schema, normalization, versioning, and source-specific import diagnostics.
- Make TextFlow lowering AST-driven.

### V3: Persistent Height Cache And Orchestrator

Current assessment: partially achieved.

What is in place:

- Persistent `RichRenderHeightCache` exists.
- Confidence levels exist.
- Height writes exist for native/snapshot/inline paths.
- `RichRenderOrchestrator` exists and records decisions.

Remaining gaps:

- Orchestrator is not yet the single source of route/admission truth.
- Some leaf Composable and scheduler branches still make final decisions.
- Cached compiled models can still create policy inconsistencies unless V4.1 closes them.
- Height cache is not yet tied to a full render platform version/schema.
- Performance validation on release/profileable journeys is still missing.

V6 gate:

- Actual route must be reconciled against orchestrator decision.
- Height delta and cache confidence must be part of fixture reports.
- Release/profileable benchmark must prove upward history scroll behavior.

### V4: Subtree Routing And Snapshot Islands

Current assessment: useful but incomplete.

What is in place:

- `RichSubtreeRoutePlanner` exists.
- `RichSnapshotIslandBlock` exists.
- Snapshot island optimizer and renderer exist.
- Visual-only candidates are conservative.
- Native action siblings are preserved.

Remaining gaps:

- Subtree routing runs over `RichHtmlRenderModel`, not canonical AST.
- Candidate detection is limited to a small set of visual-only cases.
- Unknown-size candidates are rejected, so many real CSS visuals still fall back to whole snapshot/native approximation.
- Style boundary around snapshot island source/wrapper needs V4.1 hardening.
- No visual diff gate proves that island output is closer to WebView.
- No aggregate report proves whole-bubble snapshot count dropped on real data.

V6 gate:

- Subtree routing should be explainable from AST capability analysis.
- Snapshot island benefit must be measured by fixture report:
  - island count
  - whole snapshot avoided
  - native actions preserved
  - visual diff improvement

### V5: Parser, Safety, Media, And SVG Hardening

Current assessment: planned, not achieved.

What is in place:

- V5 requirements document exists.
- Selector/cascade indexing plan has been added to V6 as a measurable gate.

Remaining gaps:

- `RichHtmlCompiler.kt` is still large.
- Parser/cascade/media/svg/sanitizer hardening is not implemented yet.
- No parser equivalence suite exists.
- No sanitizer report-only output exists.
- No prepared draw cache report exists beyond current SVG work.

V6 gate:

- V6 should not mark fidelity platform complete until V5 emits parser/cascade/sanitizer/media/svg telemetry.

## V1-V5 Non-AST Closure Backlog

This section lists unfinished requirements outside the AST workstream. These must be tracked explicitly so V6 does not become an AST-only cleanup.

### V1 Closure: Plan Versus Actual Route Contract

Unfinished requirements:

- `RichRenderPlan` is not yet a contract consumed by all renderer paths.
- Planned route and actual route can diverge without a required mismatch report.
- Route reason taxonomy is not yet stable enough for dashboards and regression gates.
- Plan version is not yet part of every related cache/report schema.

Required V6 closure:

- Add `plannedRoute`, `actualRoute`, and `routeMismatchReason` to every rich fixture report.
- Add `RichRenderPlanVersion`.
- Add route mismatch telemetry:
  - `PlanNativeActualSnapshot`
  - `PlanSnapshotActualNative`
  - `PlanDynamicActualInline`
  - `PlanNativeActualLightweight`
  - `PolicyOverride`
  - `RuntimeFallback`
- Make route reason strings enum-like or structured where possible.
- Add tests proving telemetry does not log raw content.

Acceptance:

- A V6 report can explain every route drift.
- Route drift is either expected and categorized, or fails the fixture gate.

### V2 Closure: TextFlow Measurement And Behavior Guarantees

Unfinished requirements:

- TextFlow exists, but real-seed reduction targets are not proven.
- TextFlow is not yet a primary backend selected by render plan.
- TextFlow coverage for Markdown/Protocol is not proven.
- Text metric calibration against WebView reference is not automated.
- No regression gate verifies that links/actions/math/inline paint survive TextFlow lowering.

Required V6 closure:

- Add TextFlow fixture category:
  - span-heavy HTML
  - long Markdown paragraphs
  - mixed inline code/link/math
  - list-heavy content
- Add report fields:
  - `textFlowEligibleNodeCount`
  - `textFlowAppliedBlockCount`
  - `renderBlockReductionRatio`
  - `textFlowBlockedReasons`
  - `inlineFeaturePreservedCount`
- Add visual/reference checks for:
  - line breaks
  - list markers
  - inline code
  - links
  - bold/italic/underline/strike
  - inline math
  - inline paint/background
- Add a target:
  - text-heavy rich HTML should reduce render block count by at least 40% on approved fixtures without losing supported inline semantics.

Acceptance:

- TextFlow has measurable benefit and guarded fidelity, not just a conservative optimizer.

### V3 Closure: Orchestrator Authority And Height Stability

Unfinished requirements:

- `RichRenderOrchestrator` is not yet the single source of route/admission truth.
- Some Composables still have local decisions that can override or bypass orchestrator intent.
- Cached compiled model policy still needs V4.1 closure.
- Height cache works, but height deltas are not yet a release/profileable benchmark gate.
- Height cache keys are not tied to a complete render platform schema version.
- Upward history scroll performance is not yet proven by repeatable benchmark.

Required V6 closure:

- Add `actualDecisionSource`:
  - `Orchestrator`
  - `Scheduler`
  - `SnapshotPolicy`
  - `InlineWebViewAdmission`
  - `ComposableFallback`
  - `RuntimeFailure`
- Add a report that flags non-orchestrator final decisions.
- Move new route/admission logic into orchestrator first, then have leaf Composables consume it.
- Add height report fields:
  - placeholder height
  - measured height
  - delta px
  - delta ratio
  - cache confidence
  - cache source route
  - renderer version
- Add benchmark thresholds:
  - p95 height delta after cache hit
  - upward scroll janky frames
  - native first-render concurrency
  - snapshot queue wait
  - inline WebView active count

Acceptance:

- V6 can prove whether V3 solved upward history scroll or only added cache plumbing.
- Any new admission path must be visible through orchestrator telemetry.

### V4.1 Closure: WebView And Snapshot Island Stability

Unfinished requirements:

- Inline WebView render-process-gone/crash admission release must be fixed.
- Snapshot island style boundary must be explicit.
- Snapshot island rendering needs fallback-loop protection.
- Island bitmap cache hit and height cache hit must be distinguishable.
- Island work must not start during fast scroll unless cached.
- Whole-bubble snapshot reduction is not measured on real data.
- Native action preservation around islands is tested mostly by unit-level structure, not device/UI behavior.

Required V6 closure:

- Track V4.1 as a prerequisite for V6 device/perf validation.
- Add fixture fields:
  - island candidate count
  - island applied count
  - island rejected count
  - rejection reasons
  - whole snapshot avoided
  - native action siblings preserved
  - island bitmap cache hit
  - island height cache hit
  - island fallback reason
- Add UI smoke or Compose test for:
  - action button beside island still dispatches
  - snapshot failure shows fallback once
  - style boundary does not double-apply visual box
- Add log correlation:
  - `snapshot-island-plan`
  - `snapshot-island-render`
  - `inline-webview-phase`
  - `frame-pressure`
  - `height-delta`

Acceptance:

- V6 can prove whether V4 reduced whole-bubble snapshot usage and preserved interaction.
- Inline WebView crash cannot consume scarce WebView slots.

### V5 Closure: Parser, Safety, Media, SVG, And Draw Cache

Unfinished requirements:

- `RichHtmlCompiler.kt` still needs module split.
- CSS parser/cascade equivalence suite is missing.
- Selector/cascade index is not implemented.
- Sanitizer is not in report-only mode yet.
- Media loading is not unified through a bounded wrapper.
- AndroidSVG or equivalent complex SVG spike is not measured.
- Prepared draw cache is not expanded beyond current limited SVG work.

Required V6 closure:

- V6 fixture reports must include V5 telemetry when available:
  - CSS parse time
  - selector match time
  - cascade apply time
  - parser fallback count
  - sanitizer warning count
  - media request count
  - media failure count
  - SVG route count
  - prepared draw cache hit rate
- Add parser equivalence fixtures for:
  - background shorthand
  - border shorthand
  - font shorthand
  - filters
  - animation/transition tokens
  - CSS variables
  - selector specificity/source order
- Add sanitizer comparison:
  - existing `RichHtmlSafety` result
  - sanitizer report-only result
  - disagreement reason
- Add media/SVG route reports:
  - native image
  - background image
  - data URI
  - complex static SVG
  - runtime/foreignObject SVG

Acceptance:

- V6 cannot claim platform maturity until V5 hardening emits measurable reports.
- Parser/safety/media/SVG regressions are visible independently from AST/rendering regressions.

## Required Scope Split

### V6A: Curated Fidelity Fixture System

Add a fixture system for real and synthetic rich content.

Required fixture sources:

- curated synthetic HTML snippets
- existing render seeds
- real-device export from:
  - Assistant: `Uika`
  - Conversation: `文字游龙`

Fixture metadata:

- stable fixture id
- source kind: `Synthetic`, `RenderSeed`, `DeviceSeed`
- content digest
- approximate category:
  - text-heavy
  - table
  - card
  - SVG
  - CSS visual
  - dynamic/runtime
  - media
  - mixed interactive
- expected route if known
- privacy mode

Rules:

- Do not commit private raw chat exports unless explicitly approved.
- Store metadata and digests by default.
- If a real fixture is checked in, it must be an explicit curated test fixture with sensitive data removed or approved.
- Reports must not log raw message bodies, raw URLs, button payloads, or full HTML by default.

Acceptance:

- Test code can load fixture metadata and feed content into native, snapshot, and WebView reference renderers.
- `文字游龙` can be used locally as a named device seed without requiring it to be committed.

### V6B: Screenshot Reference Pipeline

Add a pipeline that can render the same fixture through multiple backends:

```text
fixture
  -> native Compose render screenshot
  -> WebView reference screenshot
  -> snapshot route screenshot
  -> route/plan metadata
```

Requirements:

- Use deterministic viewport widths:
  - phone narrow
  - phone normal
  - optional tablet/wide
- Use deterministic theme buckets:
  - light
  - dark
- Store screenshots in generated test artifacts, not normal source files by default.
- Include render metadata:
  - route
  - native confidence
  - visual hints
  - unsupported reasons
  - snapshot island count
  - text flow count
  - height cache confidence
  - measured height
  - render time if available

Acceptance:

- A developer can run one command and get artifacts for selected fixtures.
- Generated reports include links/paths to screenshots and metadata.

### V6C: Visual Difference Metrics

Add coarse but useful visual diff metrics.

Metrics:

- image dimensions
- height delta
- mean absolute pixel difference
- thresholded pixel mismatch ratio
- optional perceptual hash distance
- optional text-region weighted diff if practical

Rules:

- Visual diff is a gate only for curated stable fixtures.
- Dynamic/runtime fixtures should be classified rather than pixel-gated.
- Animations should compare stable frame or staticized route.
- Report uncertainty instead of false precision.

Suggested classifications:

- `Pass`
- `MinorDifference`
- `NeedsReview`
- `KnownUnsupported`
- `DynamicNotComparable`
- `ReferenceFailed`

Acceptance:

- The report can rank fixtures by likely visual regression.
- Known unsupported browser features do not fail the whole suite without an explicit policy.
- New regressions in stable fixtures are visible.

### V6D: Frequency-Weighted Gap Report

Add a report that ranks what to fix next using real data.

Inputs:

- fixture category counts
- visual hints
- unsupported reasons
- route distribution
- visual diff result
- height delta
- crash/fallback records
- manual impact override if needed

Output buckets:

```text
High frequency + high impact -> native capability or snapshot policy priority
High frequency + low impact -> approximation acceptable
Low frequency + high impact -> fallback/dynamic route priority
Low frequency + low impact -> observe only
```

Acceptance:

- The team no longer chooses CSS features to implement by guesswork.
- `文字游龙` can produce a prioritized gap list.

### V6E: Macrobenchmark And Baseline Profile Journeys

Add or extend release/profileable benchmark journeys.

Required Macrobenchmark journeys:

- `RichChatTextHeavyScrollBenchmark`
- `RichChatMixedSnapshotIslandBenchmark`
- `RichChatDynamicInlineWebViewBenchmark`
- `RichChatSvgTableCardBenchmark`
- `RichChatHistoryUpwardScrollBenchmark`

Preferred real seed journey:

- `文字游龙` upward history scroll

Measured signals:

- frame timing / janky frames
- startup or chat open time where relevant
- scroll duration
- dropped frames
- memory snapshots if practical
- native compile queue wait
- snapshot queue wait
- inline WebView active count
- height delta events

Baseline Profile journey coverage:

- open chat screen
- open rich history
- scroll rich feed up/down
- first native rich render
- first snapshot render
- first inline dynamic WebView attach

Acceptance:

- Benchmarks run against release/profileable target.
- Results are repeatable enough for before/after comparison.
- V6 report includes both fidelity and performance summaries.

### V6F: Render Platform Versioning

Add explicit versioning for renderer contracts.

Versioned areas:

- `RichContentAst`
- `RichRenderPlan`
- `RichHtmlRenderModel`
- `RichRenderHeightCache`
- snapshot cache keys
- fidelity report schema

Requirements:

- Version changes should be intentional.
- Height/snapshot caches must invalidate when renderer semantics change.
- Reports should record version numbers.
- Backward compatibility is required only where explicitly stated.

Acceptance:

- Developers can explain whether a fidelity/performance change is from content, route policy, or renderer version.
- Cache invalidation is not accidental.

### V6G: Selector/Cascade Index Gate

Goal:

Make selector/cascade cost measurable, then use indexed matching as the default compile path when safe.

This work complements V5 parser/cascade hardening. V5 can introduce the parser and basic rule index; V6 makes it benchmarked, reportable, and gated against real chat data.

Required model:

```kotlin
internal data class RichCssRuleIndex(
    val idRules: Map<String, List<RichIndexedCssRule>>,
    val classRules: Map<String, List<RichIndexedCssRule>>,
    val tagRules: Map<String, List<RichIndexedCssRule>>,
    val attrRules: Map<String, List<RichIndexedCssRule>>,
    val pseudoRules: Map<String, List<RichIndexedCssRule>>,
    val universalRules: List<RichIndexedCssRule>,
    val complexRules: List<RichIndexedCssRule>,
    val unsupportedRules: List<RichUnsupportedCssRule>,
    val stats: RichCssRuleIndexStats,
)

internal data class RichIndexedCssRule(
    val ruleId: Int,
    val selectorTextHash: String,
    val key: RichCssRuleKey,
    val specificity: RichCssSpecificity,
    val sourceOrder: Int,
    val declarations: RichCssDeclarations,
    val flags: Set<RichCssRuleFlag>,
)

internal sealed interface RichCssRuleKey {
    data class Id(val value: String) : RichCssRuleKey
    data class ClassName(val value: String) : RichCssRuleKey
    data class TagName(val value: String) : RichCssRuleKey
    data class AttributeName(val value: String) : RichCssRuleKey
    data class Pseudo(val value: String) : RichCssRuleKey
    data object Universal : RichCssRuleKey
    data object Complex : RichCssRuleKey
}
```

Key selector selection:

- Parse selector lists into individual selectors before indexing.
- Pick the rightmost compound selector as the initial matching key.
- Prefer keys in this order when they appear in the rightmost compound:
  - id
  - class
  - attribute exact/presence
  - tag
  - pseudo bucket
  - universal
- If the selector has unsupported, ancestor-sensitive, sibling-sensitive, or dynamic semantics that the native matcher cannot safely prove, put it in `complexRules`.
- Keep source order and specificity on every indexed rule.
- Never use raw selector text in telemetry; use hash and category.

Candidate collection per element:

```text
element id -> idRules[id]
each class -> classRules[class]
tagName -> tagRules[tagName]
attributes -> attrRules[attrName]
state/pseudo -> pseudoRules[pseudoName]
always -> universalRules
if needed -> complexRules
```

Then:

- Deduplicate by `ruleId`.
- Fast reject by key mismatch where possible.
- Run full selector matcher only on candidates.
- Sort matched rules by cascade layer/origin if supported, specificity, and source order.
- Apply declarations.

Conservative fallback rules:

- If index and legacy matcher disagree in debug/report mode, use legacy result and record mismatch.
- If a selector parser cannot classify a selector safely, route to `complexRules`.
- If complex rule count exceeds budget, the render plan should raise compile risk and may prefer snapshot for static visual-heavy content.
- If the HTML is streaming or incomplete, avoid persistent index cache writes.

Required telemetry:

- total CSS rules
- selector count after selector-list expansion
- buckets:
  - id
  - class
  - tag
  - attr
  - pseudo
  - universal
  - complex
  - unsupported
- average candidate rules per element
- p95 candidate rules per element
- legacy scan count in report mode
- index mismatch count
- selector match time
- cascade apply time
- style cache hit rate
- high-cost selector categories

Required fidelity/performance reports:

- For each fixture, include selector index stats in the per-fixture report.
- For aggregate reports, rank:
  - most common high-cost selector category
  - fixtures with worst candidate-rule p95
  - fixtures where indexed matcher disagreed with legacy matcher
  - fixtures where selector cost correlates with compile time

Acceptance:

- Indexed matcher is report-first before becoming authoritative.
- Debug/report mode can compare indexed candidate matching against the old full-scan matcher.
- Indexed mode produces identical `ComputedStyle` for supported selectors in curated fixtures.
- Complex/unsupported selectors are visible and routed conservatively.
- On `文字游龙` and synthetic long-card fixtures, average candidate rules per element drops materially versus scanning all rules.
- Compile-time reporting can separate selector matching from declaration application.

### V6H: AST Canonicalization And Transform Gate

Goal:

Promote `RichContentAst` from a report/helper tree into the canonical rich content document that drives TextFlow, subtree routing, render plan, height keys, and fidelity reports.

Target pipeline:

```text
source content
  -> source import AST
       RichHtmlAst / RichMarkdownAst / RichProtocolAst
  -> RichContentDocument
       normalized, schema-governed, immutable, versioned
  -> capability analysis
  -> RichRenderPlan
  -> render backend lowering
       TextFlow
       NativeBox
       Table
       Svg
       Media
       Action
       SnapshotIsland
       InlineWebView
  -> renderer output + telemetry
```

Required canonical model:

```kotlin
internal data class RichContentDocument(
    val documentId: String,
    val schemaVersion: Int,
    val sourceKind: RichContentSourceKind,
    val root: RichContentNodeV2,
    val imports: RichContentImportDiagnostics,
    val stats: RichContentDocumentStats,
)

internal sealed interface RichContentNodeV2 {
    val nodeId: String
    val stablePath: String
    val subtreeDigest: String
    val capabilities: RichNodeCapabilities
}

internal data class RichNodeCapabilities(
    val textFlowEligible: Boolean,
    val nativeBoxEligible: Boolean,
    val snapshotIslandEligible: Boolean,
    val inlineWebViewRequired: Boolean,
    val interactive: Boolean,
    val browserOnlyVisual: Boolean,
    val unsafeRuntime: Boolean,
    val unsupportedReasons: Set<String>,
)
```

Required node groups:

- document/root
- block container
- paragraph
- text run
- mark range:
  - bold
  - italic
  - underline
  - strike
  - code
  - link
  - color/background
  - font/size/line-height
- list/list item
- quote/code block
- table/row/cell
- image/media
- SVG
- formula/math
- action/button/input
- details/disclosure
- visual-only browser island candidate
- runtime/browser-only
- unsupported

Normalization rules:

- Document root always contains block nodes.
- Paragraphs contain inline runs, inline actions, inline media, or explicit unsupported inline nodes.
- Lists contain only list items.
- Tables contain rows and cells with block or inline content according to local renderer capability.
- Text runs must be merged when adjacent marks are identical.
- Empty containers should be removed unless they carry visual/layout meaning.
- Unsafe/runtime nodes must be explicit `BrowserOnly` or `Unsupported`, not hidden inside generic containers.
- Stable paths must be structural and must not contain text.
- `nodeId` must be stable for unchanged source structure.
- `subtreeDigest` must exclude raw text in telemetry, but may include content for cache identity internally.

Transform passes:

1. `Import`
   - Convert HTML/Markdown/Protocol into source-specific AST.
   - Record conversion loss.
2. `Normalize`
   - Apply schema constraints.
   - Fix malformed generated content.
3. `SanitizeReport`
   - Attach safety diagnostics.
   - Do not mutate rendering in report-only mode unless policy says so.
4. `StyleResolve`
   - Attach computed style/capability data.
   - Use V6G selector/cascade index where available.
5. `TextFlowPlan`
   - Convert text-heavy subtrees into paragraph/runs.
   - Preserve marks/actions/media boundaries.
6. `SubtreeRoutePlan`
   - Mark native/snapshot/dynamic/inline backend per subtree.
7. `LowerToRenderModel`
   - Produce existing `RichHtmlRenderModel` blocks or successor backend models.
8. `MeasureAndCache`
   - Bind height cache keys to document/render versions.

Required AST telemetry:

- source kind
- schema version
- source node count
- canonical AST node count
- normalized node count
- dropped/unsupported node count
- text run count
- mark range count
- paragraph count
- action count
- media count
- table count
- svg count
- browser-only count
- text-flow eligible subtree count
- snapshot island eligible subtree count
- native backend count
- snapshot backend count
- inline WebView required count
- import conversion loss count
- transform time by pass
- subtree cache hit rate

Privacy:

- Telemetry must not include raw text, HTML, CSS, URLs, action payloads, or button labels.
- Reports may include fixture id, digest, node counts, route reasons, and category names.

Required fixture report additions:

- planned source AST node count
- canonical AST node count
- TextFlow lowering count
- AST-driven route distribution
- AST route versus current RenderModel route mismatch
- subtree digest cache hit rate
- normalization warning summary
- import conversion loss summary

Required tests:

- `RichContentDocumentImportTest`
  - HTML paragraphs/lists/tables/buttons/media import correctly.
  - Markdown source does not need to roundtrip through HTML for basic semantics.
  - Protocol actions become action nodes.
- `RichContentDocumentNormalizationTest`
  - invalid nesting is normalized.
  - adjacent same-mark runs merge.
  - empty non-visual containers are removed.
  - visual empty containers are preserved.
- `RichContentDocumentPrivacyTest`
  - telemetry/report serialization does not include raw text/HTML/URLs/action payloads.
- `RichContentTransformPipelineTest`
  - transform pass order is deterministic.
  - same input produces same document id and stable paths.
  - subtree digest changes only for changed subtree.
- `RichTextFlowAstLoweringTest`
  - text-heavy AST lowers to TextFlow.
  - buttons/tables/SVG/media are not swallowed by TextFlow.
- `RichSubtreeRouteAstTest`
  - snapshot island candidates can be explained from AST capabilities.
  - dynamic/runtime nodes route to dynamic/inline.

Migration strategy:

- Step 1: keep current `RichContentAst` as V2 report tree and add `RichContentDocument` side by side.
- Step 2: emit both old AST stats and new document stats in reports.
- Step 3: compare current RenderModel route with AST-driven route in report-only mode.
- Step 4: route TextFlow from `RichContentDocument` for safe text-heavy fixtures.
- Step 5: route snapshot islands from `RichContentDocument` for safe visual-only fixtures.
- Step 6: make `RichContentDocument` the source of render plan and height/cache identity.

Acceptance:

- V6 reports can show whether V2/V4 behavior is AST-driven or still RenderModel-driven.
- TextFlow and snapshot islands have AST capability explanations.
- AST transform output is deterministic, versioned, and privacy-safe.
- Real fixture reports identify conversion loss and route mismatches.
- `文字游龙` produces AST stats and mismatch lists without exposing raw chat content.

## Non-Goals

- Do not block all development on perfect visual diff.
- Do not require pixel-perfect native output for browser-only features.
- Do not commit private chat databases.
- Do not replace the Compose cell pipeline.
- Do not make WebView the default renderer.
- Do not add broad parser rewrites in V6; V5 owns parser hardening.

## Required Reports

### Per-Fixture Report

Fields:

- fixture id
- digest
- category
- route
- plan reason
- native confidence
- visual hints
- unsupported reasons
- snapshot island count
- text flow count
- measured native height
- measured WebView height
- height delta
- visual diff class
- visual diff metrics
- render time
- fallback reason

### Aggregate Report

Fields:

- fixture count by category
- route distribution
- visual hint ranking
- unsupported reason ranking
- average and p95 height delta
- average and p95 render time
- janky frame summary for benchmark journeys
- top fidelity gaps
- top performance risks
- recommended next work items

Privacy:

- Reports must not include raw HTML/text/URLs/action payloads by default.
- Reports may include fixture id and digest.

## Required Tests

JVM tests:

- fixture metadata parser
- report schema serialization
- route metadata privacy
- diff metric classification
- frequency-weighted ranking
- renderer version cache-key invalidation
- CSS rule index key selection
- indexed matcher versus legacy matcher equivalence
- complex selector fallback
- selector index telemetry privacy
- canonical AST import/normalization
- AST transform determinism
- AST telemetry privacy
- AST-driven TextFlow and subtree route equivalence

Instrumentation/Compose tests:

- render selected fixture natively and capture screenshot artifact
- render WebView reference screenshot artifact
- verify report can join metadata and screenshots

Macrobenchmark:

- add the required rich chat journeys
- include at least one mixed native/snapshot feed
- include at least one upward history scroll journey

Validation commands should include:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest
```

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugKotlin
```

Device/perf validation should use release/profileable where practical.

## Acceptance Criteria

V6 is accepted when:

- Curated fixtures can be rendered through native and WebView reference paths.
- Fidelity reports include route, visual hint, height, screenshot, and diff metadata.
- Aggregate reports rank real gaps by frequency and impact.
- Macrobenchmark journeys cover rich chat scrolling, especially upward history scrolling.
- Baseline Profile includes rich render journeys.
- Render/platform schema versions are recorded and used in cache keys.
- Privacy rules are enforced in reports and telemetry.
- The team can decide the next renderer work item from report data, not guesswork.

## Developer Handoff Report Template

```text
Implemented scope:
- Fixture system:
- Screenshot reference pipeline:
- Visual diff metrics:
- Frequency-weighted report:
- Macrobenchmarks:
- Baseline profile:
- Versioning:

Artifacts generated:
-

Tests passed:
-

Known risks / deferred:
-
```
