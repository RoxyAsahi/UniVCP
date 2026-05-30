# UniVCP Rich Render Target Architecture

Date: 2026-05-29

This document describes the ideal architecture for UniVCP rich rendering. It is not a task checklist. It explains the current system, the direction we are taking, and the target shape we want to reach.

The core product principle is:

```text
Fidelity first, with performance governed by architecture rather than by visual degradation.
```

That means:

- Native Compose should render content when it can preserve visual fidelity and interaction.
- WebView should remain available for browser-only visual or runtime behavior.
- WebView should be used as a scarce rendering backend, not as the default list item.
- Long chat history should scroll like a large IM/feed, not like a pile of giant WebViews.
- Every route decision should be explainable and measurable.

## Why This Renderer Exists

The project moved away from live WebView-first chat rendering because WebView is expensive in a scrolling feed:

- WebView creation initializes and talks to a full Chromium-based engine.
- Many live WebViews increase memory, CPU, GPU, and process pressure.
- Fast scrolling can trigger attach/load/measure/release storms.
- Upward history scrolling is especially sensitive because multiple rich bubbles may need restore, height reservation, and rendering at once.

At the same time, pure native Compose has a different problem:

- HTML/CSS/SVG are browser-native formats.
- A naive native renderer can lose fidelity for CSS layout, filters, masks, animations, SVG, tables, and inline text behavior.
- If every HTML node becomes a Compose node, text-heavy generated content gets too deep and too expensive.

So the target is not:

```text
all native
```

and not:

```text
all WebView
```

The target is:

```text
plan-driven multi-backend rendering
```

where each subtree uses the cheapest renderer that preserves the required fidelity.

## Current Architecture

The current system already has several important pieces.

### Chat Feed Layer

The chat list has moved toward a cell pipeline:

```text
Conversation / UIMessage
  -> MessageTextBlocks
  -> ChatRenderCell list
  -> LazyColumn items(key, contentType)
```

This is the right foundation. A long assistant message should not be one giant Lazy item. Markdown, HTML, Protocol, reasoning, tools, attachments, actions, and spacers should be stable cells that LazyColumn can reuse and measure independently.

What this gives us:

- stable keys
- content-type reuse
- smaller recomposition units
- better scroll hot-path control
- room for cell-level scheduling

Remaining direction:

- make every rich backend expose stable cell/content types
- ensure message-level business actions still aggregate by `messageId`
- keep visual grouping for assistant bubbles without making the whole message one item

### Rich HTML Native Path

The native path currently looks like:

```text
HTML
  -> RichHtmlCompiler
  -> RichHtmlRenderModel
  -> RichHtmlRenderer
  -> Compose blocks
```

This path supports many native blocks:

- text
- text flow
- containers
- images
- tables
- SVG
- math
- buttons/actions
- details
- unsupported fallback
- snapshot islands

This is the correct direction, but the compiler and renderer still carry too much responsibility directly.

Current problem:

```text
HTML DOM node
  -> native render block
  -> Compose node
```

is often too literal. Browser text layout is not a stack of nested containers. Browser text layout is closer to paragraphs and inline runs. The renderer needs a stronger content model between source HTML and Compose.

### RichRenderPlan

`RichRenderPlan` currently makes route decisions observable:

```text
route
confidence
risk
visual hints
estimated block counts
height cache state
```

This is a good V1 foundation, but the ideal architecture makes the plan a contract, not only a report.

Target:

```text
RichRenderPlan
  -> actual route
  -> route mismatch report if they differ
```

Every final renderer path should be able to answer:

- what was planned
- what actually happened
- why it changed
- whether the fallback was expected

### RichContentAst

The current `RichContentAst` is useful but not yet the center of the system.

Current state:

- HTML-only in practice
- good for stats and TextFlow hints
- not yet the canonical internal document
- TextFlow still mostly lowers from `RichHtmlRenderModel`
- snapshot island planning still mostly reads `RichHtmlRenderModel`

Target:

```text
HTML / Markdown / Protocol
  -> source AST
  -> canonical RichContentDocument
  -> transforms
  -> RichRenderPlan
  -> backend render models
```

This is the biggest architectural upgrade still ahead.

### Height Cache And Orchestrator

The current height cache is a real improvement:

```text
digest + width + fontScale + density + theme + contentType + rendererVersion
  -> measured height + confidence
```

This helps upward history scrolling because content can reserve realistic space before it finishes rendering.

The orchestrator is also the right concept:

```text
plan + risk + scroll state + height cache + visible range
  -> route/admission decision
```

But the current system is not fully orchestrator-owned yet. Some final decisions still live in scheduler, snapshot policy, inline WebView admission, or individual Composables.

Target:

```text
Orchestrator is the single route/admission authority.
Leaf renderers execute decisions; they do not invent new policy.
```

### Snapshot And Inline WebView

There are two different WebView-backed roles.

Snapshot:

```text
offscreen WebView
  -> bitmap
  -> Compose Image
  -> WebView released
```

This is good for static browser-only visuals.

Inline WebView:

```text
scarce live WebView
  -> visible dynamic runtime content
  -> governed by pool/admission/state machine
```

This is necessary for true runtime content, but should stay rare.

Target:

- static visual browser features become snapshot islands or whole snapshots
- runtime content becomes governed inline/fullscreen WebView
- live WebView count remains small
- WebView crashes/release paths never leak admission slots
- cached height/frozen preview preserves layout when live WebView is not active

## What Still Feels Half Done

The earlier V1-V5 work added important pieces, but several are not yet closed loops.

### V1

Mostly done:

- plan exists
- telemetry exists

Not fully done:

- plan is not yet a contract
- planned route versus actual route is not always reconciled

### V2

Partially done:

- AST exists
- TextFlow exists

Not fully done:

- AST is not canonical
- TextFlow is not AST-driven
- Markdown/Protocol are not equally represented
- real-seed TextFlow benefit is not proven

### V3

Partially done:

- persistent height cache exists
- orchestrator exists

Not fully done:

- orchestrator is not the single decision authority
- release/profileable scroll validation is missing
- upward history scroll still needs measurable thresholds

### V4

Useful but incomplete:

- snapshot islands exist
- interactive siblings can be preserved

Not fully done:

- island planning is not AST-driven
- style boundary needs hardening
- real-data whole snapshot reduction is not proven
- visual diff does not prove fidelity gain

### V5

Planned, not achieved:

- compiler split
- CSS parser/cascade hardening
- selector index
- sanitizer report-only mode
- unified media loader
- SVG hardening
- prepared draw cache expansion

## Ideal Target Pipeline

The ideal renderer should look like this:

```text
Chat message content
  -> block extraction
  -> source import
       HTML -> RichHtmlAst
       Markdown -> RichMarkdownAst
       Protocol -> RichProtocolAst
  -> canonical RichContentDocument
       normalized
       schema-governed
       immutable
       versioned
       subtree digested
  -> style and capability analysis
       CSS parser/cascade
       selector index
       sanitizer report
       visual hints
       runtime/safety classification
  -> RichRenderPlan
       subtree routes
       backend assignment
       height estimate
       risk
       confidence
       fallback strategy
  -> RichRenderOrchestrator
       viewport-aware admission
       scroll pressure
       height cache
       queue budgets
       circuit breaker
  -> backend lowering
       TextFlowModel
       NativeBoxModel
       TableModel
       SvgModel
       MediaModel
       ActionModel
       SnapshotIslandModel
       InlineWebViewModel
  -> Compose cell renderers
  -> telemetry, benchmark, fidelity report
```

The key shift is:

```text
from "HTML compiles directly into Compose-ish blocks"
to "source content becomes a canonical document, then plans choose render backends"
```

## Canonical Document

The future `RichContentDocument` should be the internal truth.

It should contain:

- stable node ids
- structural stable paths
- subtree digests
- source kind
- schema version
- normalized tree
- inline marks/runs
- block segmentation
- actions
- media
- tables
- formulas
- visual-only browser candidates
- runtime/browser-only nodes
- unsupported nodes
- capability metadata

It should not treat raw HTML as the truth after import. HTML should be one source format.

### Why This Matters

Without a canonical document:

- TextFlow cannot reliably know what it is allowed to flatten.
- Snapshot islands cannot reliably know whether a visual subtree is interaction-free.
- Height cache keys can miss renderer semantic changes.
- Fidelity reports cannot compare route decisions across source formats.
- Markdown/Protocol/HTML rendering logic keeps drifting apart.

With a canonical document:

- source import becomes testable
- normalization becomes testable
- transforms become deterministic
- route decisions become explainable
- subtree cache becomes possible
- future structured VCP payloads can bypass fragile HTML parsing

## Transform Passes

The ideal renderer should use explicit passes:

```text
Import
Normalize
SanitizeReport
StyleResolve
CapabilityAnalyze
TextFlowPlan
SubtreeRoutePlan
LowerToBackendModels
MeasureAndCache
Render
Report
```

### Import

Convert source formats into source ASTs:

- HTML keeps DOM/CSS details.
- Markdown keeps markdown semantics.
- Protocol keeps structured actions.

### Normalize

Repair generated or malformed content:

- root contains blocks
- lists contain list items
- tables contain rows/cells
- inline text uses marks/runs
- empty non-visual containers are removed
- unsafe/runtime nodes become explicit

### SanitizeReport

Report dangerous or unsupported content:

- scripts
- event handlers
- unsafe protocols
- runtime media
- iframe/object/embed

This should initially be report-only unless policy says otherwise.

### StyleResolve

Apply CSS safely:

- parse declarations
- index selector rules
- match candidates
- compute style
- record unsupported selectors/properties

This is not a full browser engine. It is a bounded style engine with explicit fallback.

### CapabilityAnalyze

Decide what each subtree can support:

- native text flow
- native box layout
- table
- SVG
- media
- action
- snapshot island
- whole snapshot
- dynamic/inline WebView

### TextFlowPlan

Flatten text-heavy content into paragraph/runs.

TextFlow should preserve:

- links
- inline code
- marks
- inline math
- supported inline paint
- list markers

TextFlow must not swallow:

- buttons/actions
- tables
- SVG
- media
- browser-only visuals
- dynamic/runtime nodes

### SubtreeRoutePlan

Assign backend per subtree:

```text
native card
  native text flow
  native action button
  snapshot island for complex SVG
```

This is where V4 should eventually live.

### LowerToBackendModels

Create renderer-specific models:

- TextFlow backend receives paragraphs/runs.
- NativeBox backend receives layout and style.
- Table backend receives structured rows/cells.
- SnapshotIsland backend receives source fragment and style boundary.
- InlineWebView backend receives runtime payload and lifecycle policy.

Compose should consume these models, not rediscover policy.

## Backend Roles

### TextFlow Backend

Best for:

- paragraphs
- spans
- lists
- inline formatting
- generated prose

Goal:

- fewer Compose nodes
- browser-like inline flow
- stable text measurement

### NativeBox Backend

Best for:

- simple cards
- block layout
- flex/grid subset
- borders/backgrounds/shadows within supported range

Goal:

- interactive native UI
- good enough visual fidelity
- no WebView cost

### Table Backend

Best for:

- static tables
- data summaries
- structured output

Goal:

- predictable layout
- bounded measuring
- horizontal scroll when needed

### SVG Backend

Best for:

- simple static SVG
- controlled commands

Goal:

- precompiled paths/paints
- no draw-stage parsing
- snapshot fallback for complex SVG

### Snapshot Island Backend

Best for:

- browser-only static visuals inside otherwise native cards
- complex SVG/filter/mask/background visuals

Goal:

- WebView fidelity for visual-only subtrees
- preserve native text/actions around it
- bitmap output in list
- no live WebView in normal scroll

### Inline WebView Backend

Best for:

- JavaScript runtime
- canvas
- interactive browser widgets
- content that cannot be faithfully represented statically

Goal:

- very small live count
- explicit admission
- freeze/release when offscreen
- crash-safe state machine
- stable height placeholder

## Orchestration

The orchestrator should own:

- native first-render admission
- compile/prewarm priority
- snapshot queue budget
- inline WebView admission
- fast-scroll behavior
- already-rendered stability
- height cache usage
- circuit breaker
- fallback route

Leaf renderers should not decide policy. They should report facts:

- measured height
- render success/failure
- snapshot cache hit
- WebView crash
- fallback reason

Then the orchestrator updates state.

## Caches

The target system needs layered caches:

```text
analysisCache
  -> source risk, preview, safety classification

astCache
  -> source digest to canonical document

styleCache
  -> CSS rule index, computed style signatures

compileCache
  -> render models

heightCache
  -> measured heights by digest/width/font/theme/route/version

preparedDrawCache
  -> paths, paints, gradients, borders, shadows

snapshotCache
  -> bitmap snapshots

circuitBreaker
  -> repeated failure/timeout/crash route override
```

Every cache needs:

- versioning
- bounded size
- privacy-safe keys
- confidence/source metadata where relevant

## Fidelity Strategy

The renderer should not guess which browser features to implement next.

It should measure:

- visual hints
- route distribution
- native/WebView height difference
- screenshot diff
- whole snapshot count
- snapshot island count
- TextFlow reduction
- WebView live count
- janky frames
- crash/fallback reasons

Then prioritize:

```text
high frequency + high impact
  -> native capability or snapshot policy priority

high frequency + low impact
  -> approximation acceptable

low frequency + high impact
  -> fallback route priority

low frequency + low impact
  -> observe only
```

`文字游龙` should be a first-class real seed for this.

## Performance Strategy

Performance should come from structural control:

- cell virtualization
- stable Lazy keys/content types
- TextFlow for text-heavy content
- height placeholders from measured cache
- viewport-aware admission
- offscreen snapshot to bitmap
- small live WebView pool
- prepared draw caches
- selector/cascade indexing
- release/profileable macrobenchmarks

Performance should not primarily come from hiding content too aggressively. Visible content should remain stable whenever possible.

## Debugging And Reports

A rich render debug report should answer:

- What source format was used?
- What did the canonical document look like?
- What route was planned?
- What route actually happened?
- Why did it differ?
- What visual hints were found?
- What was unsupported?
- Was TextFlow applied?
- Were snapshot islands applied?
- Was height cache hit?
- What was placeholder versus measured height?
- Did WebView attach/release/crash?
- Was content rendered during fast scroll?
- How many Compose/render nodes were produced?
- How long did parse/compile/render take?

No report should leak raw chat text, raw HTML, CSS bodies, URLs, or action payloads by default.

## End State

The ideal renderer is a platform:

```text
RichContentDocument is the truth.
RichRenderPlan is the contract.
RichRenderOrchestrator is the authority.
Renderer backends are executors.
Telemetry and fidelity reports are the judge.
```

When this is achieved:

- V1 is no longer just observation; it is route contract.
- V2 is no longer a helper AST; it is canonical content.
- V3 is no longer partial orchestration; it is the route authority.
- V4 is no longer a post-processing snapshot trick; it is AST-driven subtree routing.
- V5 is no longer a parser wish list; it is a hardened compiler platform.
- V6 is no longer manual QA; it is the fidelity and performance gate.

That is the shape we should keep steering toward.
