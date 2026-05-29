# Rich Render Streaming Optimizations

## Goal

Streaming output should feel incremental without repeatedly disposing already-rendered chat cells. The final, non-streaming
message path must keep using the existing rich render scheduler, height cache, snapshot, inline WebView, and native scroll
guards.

## Current Guardrails

- Only the active loading assistant text part is stabilized.
- Historical messages and final `loading=false` rich HTML keep their existing content-addressed keys.
- Partial rich HTML is sampled through `StreamRenderArbiter`; closed rich HTML flushes into the final render path.
- Streaming markdown cells use a stable key per message/part/block while loading, so LazyColumn can keep the item instead
  of treating every token as a new cell.
- The cell pipeline receives a stable text override map. This avoids changing the persistent message model and keeps
  business actions grouped by the original message id.
- Chat auto-scroll should not call `requestScrollToItem` for every streaming layout sample. During generation it may
  request once when the item count changes, but content-height changes inside the same active cell should remain a local
  render update.
- Partial rich HTML must display during generation. It stays in a lightweight streaming preview while generating and may
  parse a normalized preview fragment into simple text/list/table/image nodes. It must not wait for the root container to
  close before showing content.
- The closed `partial=false` block is only the point where full rich rendering starts. It is not the point where the user
  first sees the card.
- Partial rich HTML must not run transient full native rich compile or shimmer animation on every sampled token.

## Do Not Regress

- Do not create inline WebViews for partial streaming HTML.
- Do not write partial streaming heights into the persistent height cache.
- Do not relax the root detector just to make partial HTML render earlier.
- Do not change final `partial=false` admission, snapshot, or height-cache behavior from the streaming layer.
- Do not include live token text in streaming cell keys unless the item is intentionally replaced.
- Do not use a visible-items snapshot loop to force-scroll the list on every token. That can repaint the whole chat area
  below the title bar and looks like a regular flash.
- Do not animate the streaming rich placeholder by default. The animation itself can be perceived as card flicker during
  long generations.

## Next Steps

- Add debug telemetry for raw length, published length, suppression count, and forced flush count.
- Let partial rich HTML prefer a stable preview/placeholder when the preview route changes too frequently.
- Consider appending markdown paragraphs as sub-cells only after a paragraph boundary, leaving the active tail as one
  mutable cell.
