# UniVCP 富 HTML 聊天渲染开发现状

记录日期：2026-05-25，已同步到 Compose Cell Feed Pipeline v3.1 与 WebView 静态快照兜底 v1.1 hardening 后的状态。

本文记录当前聊天消息富 HTML 渲染链路的真实状态、能力边界和后续方向。核心目标是不修改 UniVCP/VCPChat 的 prompt 和历史数据格式，同时坚持聊天列表内 Compose 原生渲染优先，避免把安全静态内容退回成 live WebView。

一句话现状：当前架构已经从“能不能渲染”推进到“大型 IM/feed 式 cell pipeline + 安全静态 HTML 默认原生渲染 + WebView 静态快照兜底复杂保真缺口”的阶段。近期重点修复了同步编译卡顿、缓存键、root 检测重复、历史消息首帧空白、`rem/font/line-height/grid/button` 等保真问题，完成聊天列表 cell 化，并新增了不常驻 live WebView 的 snapshot route。

当前开发阶段：IR 编译器与 Compose renderer 已落地，架构硬化与高保真校准 v1 已完成一轮，静态 CSS 覆盖 Phase 1/2 v2 已进入维护补洞，Phase 3 布局增强 v3 已迁移到官方 Compose FlexBox，Phase 4 表格高保真 v2/v3 已开始落地；WebView Snapshot v1.1 已作为复杂安全静态内容的兜底路径接入并完成首轮性能硬化；Compose Cell Feed Pipeline v3.1 已把聊天列表切换到稳定 cell、viewport-aware admission、near-viewport prewarm、prepared draw cache，并补上已缓存 prewarm 消噪、编译等待态稳定高度占位、首 native 呈现 recent-scroll idle gate、Lazy measure 失败非 0 高度兜底和 render model key 修正。当前主线进入“fidelity-driven quality gate + device benchmark closure”：用真实 VCP 高频样例、visual hints、snapshot policy、JVM/Android tests 和 Macrobenchmark 约束后续开发，而不是回到 live WebView。

长期原生架构路线已单独沉淀到 `docs/rich-render-native-architecture-roadmap-v1-v6.md`：主张在当前路径上继续演进为 `RichContentAst -> RichRenderPlan -> TextFlow/Box/Table/SVG/Media/Action/SnapshotIsland/InlineWebView` 的计划驱动架构，目标是保真优先，同时用子树级路由、持久高度缓存、统一 orchestrator 和 fidelity gate 控制性能与回归。

## 当前方向

当前路线是“切块 + 分类 + 原生编译渲染 + 静态快照兜底 + 动态预览兜底”：

- 普通文本和 Markdown 继续走 Compose `MarkdownBlock`。
- VCP 富 HTML 先由 `MessageTextBlocks` 切成独立块，再由 `RichHtmlClassification` 分类。
- 聊天列表不再以“一个消息一个巨大 Lazy item”为主路径，而是由 `ChatRenderCell` flatten 成 `Avatar/UserBubble/Markdown/RichHtml/Protocol/Thinking/Tool/Attachment/Translation/Annotation/Actions/BottomSpacer` 等稳定 cell，LazyColumn 使用 `stableKey + contentType` 回收。
- 静态/交互型富 HTML 进入 Compose 原生管线：`RichHtmlCompiler -> RichHtmlRenderModel -> RichHtmlRenderer`。
- 复杂但安全的静态 HTML 在原生路径明显不适合时进入 WebView 离屏 snapshot，聊天列表显示静态 bitmap，点击打开动态预览。
- 真动态 HTML 不在聊天列表常驻执行，显示稳定预览，并通过“打开动态预览”进入 `WebViewPage`。
- streaming 中未闭合的富 HTML root 只显示固定占位，等闭合后再编译完整块，避免半截 HTML 反复解析和测高。

这个方向保留 UniVCP 富内容能力，但把聊天列表里的 WebView 数量压到最低；WebView 现在是离屏静态快照、动态/全屏/调试兜底，不是安全静态 HTML 的默认 live 渲染路径。

## 渲染架构

新原生渲染器已从旧的“Composable 递归翻译器”改成两层：

```text
assistant text
  -> MessageTextBlocks
  -> RichHtmlClassification / RichHtmlSafety
  -> RichHtmlSnapshotPolicy
  -> RichHtmlCompiler.compile(html) -> RichHtmlRenderModel -> RichHtmlRenderer -> Compose UI
  -> BubbleSnapshotRenderer -> Bitmap snapshot -> Compose Image
  -> DynamicRichHtmlPreviewBlock -> WebViewPage
```

关键原则：

- `RichHtmlBubbleBlock` 只保留兼容入口，负责按当前气泡宽度异步获取 `RichHtmlRenderModel` 并调用 renderer。
- `RichHtmlCompiler` 在专属 bounded dispatcher 上完成 Jsoup 解析、CSS 级联、选择器匹配、样式继承、文本拍平、block 建模，当前并发限制为 `Dispatchers.Default.limitedParallelism(2)`。
- `RichHtmlRenderScheduler` 现在接收可见/近视口 cell range、滚动方向、fast-scroll 判定、cell risk 和首渲染状态；视口内允许 native 首渲染，近视口只做 compile prewarm，远处保持轻量摘要/占位。
- `RichHtmlRenderer` 只消费纯 Kotlin render model，不读取 Jsoup `Element`，不解析 style 字符串，不使用 `outerHtml()` 作为渲染 key。
- 行内文本在编译阶段合并为 `AnnotatedString`，避免把 `<span>/<b>/<i>` 逐个映射成多个 `Text`。
- 编译结果由 LRU cache 缓存，历史消息回到视口时优先用 `getCached()` 复用模型，避免再次显示“正在准备富内容...”。
- Snapshot 结果由 app 侧内存 LRU 缓存，key 绑定 HTML digest、宽度、density、fontScale、主题和 renderer version；首版不做磁盘持久化，v1.1 增加 in-flight join 观测和近视口启动。
- Compose 原生渲染的目标是“安全静态 VCP 卡片高保真近似”，不是完整浏览器引擎；复杂 JS、运行时动画和完整 CSS layout 继续由动态预览/WebView 兜底。
- 富 HTML async compile 有超时、协程取消、in-flight 去重和成功后写缓存；取消或失败的编译不会污染模型缓存。
- async compile 的 in-flight 任务带 waiters 计数；远离视口或离开 composition 后最后一个 waiter 释放会取消未完成 compile，避免快滚时后台继续堆积无用 Jsoup/CSS 编译。
- SVG path、Paint、dash effect、gradient shader 等 prepared draw command 已从 renderer draw 热路径前移到 `RichSvgPreparedDrawCache`；compiler 产出 SVG model 后会安全 warm cache，Compose 绘制阶段只消费 prepared command。
- Snapshot 渲染使用 `bubble-renderer` 离屏 WebView 单队列和进程内保留 session，shell 预热后复用；禁外部导航和网络加载，超时/尺寸/像素预算失败后回退动态预览。
- Cell pipeline v3.1 中，near-viewport prewarm 会先查 persistent compile cache；已缓存模型不再重新启动预热 job，也不会占用 `MaxPrewarmTargets` 窗口。native compile 等待态和轻量态共享 `digest + width + fontScale + contentType` 高度缓存/估算高度，减少快滚和首帧准备期间的跳高。已被 admission 接纳的首渲染在短暂 fast-scroll 状态切换中保持 `already-admitted`，避免同一 digest 在可见区内反复取消/重启 compile。compile 完成不再立刻切到 native：未首渲染过的富块必须等 Lazy scroll 和 recent-scroll 窗口都结束后才呈现 native，防止 `layout state is not idle before measure starts` 这类 Lazy placement/remeasure re-entry。`rememberRichHtmlRenderModel` 的 state key 已纳入 `html`，避免同 contentType cell 复用时短暂展示上一富块 model；原生测量异常兜底使用缓存/估算高度，不再向 LazyList 返回 0 高度。
- `baseline` profileable 变体显式启用 render seed 导入，避免 Macrobenchmark/Baseline Profile 只测到空聊天页；release 默认仍关闭 seed，debug 仍由 `local.properties` 控制。

## 渲染路径职责边界

当前明确分三条路径：

- 普通 Markdown：`MarkdownBlock` / `MarkdownNew`，负责 Markdown、普通 HTML 兼容片段和既有 LaTeX 路径。
- VCP 富 HTML：`MessageTextBlocks -> RichHtmlClassification -> RichHtmlCompiler -> RichHtmlRenderer`，这是聊天列表富气泡的默认路径。
- WebView snapshot：只接管复杂但安全的静态保真缺口，例如 `backdrop-filter`、mask、复杂 clip-path/mix-blend-mode、复杂 SVG 或原生非致命失败；输出静态图片，不做按钮 hit-test。
- WebView 动态预览：用于真动态内容、全屏检查和 debug renderer server；不作为安全静态 HTML 的聊天列表默认 live 渲染器。

`SimpleHtmlBlock` 和 `MarkdownNew` 仍保留兼容职责，但不再承载新的 VCP 富 HTML 能力。后续新增表格、公式、CSS 样式、按钮等富气泡能力，优先进入 `RichHtmlCompiler/RichHtmlRenderer`。

## 关键入口

主要代码入口：

- `app/src/main/java/me/rerere/rikkahub/ui/components/message/MessageTextBlocks.kt`
  - 负责把 assistant 文本按原始顺序切成 Markdown、VCP HTML、协议日志块。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
  - 保留旧聚合渲染入口，作为 `enableChatCellPipeline=false` 的 runtime fallback 和部分预览场景兼容。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatRenderCell.kt`
  - 聊天列表 cell model 与 builder，负责从 conversation flatten 出稳定 key/contentType/messageId/blockIndex/height class/risk。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatRenderCellRenderer.kt`
  - cell renderer，按 messageId 聚合业务行为，按连续 assistant cells 维持分组气泡背景。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlClassification.kt`
  - 负责判断 `NativeStatic`、`InteractiveStatic`、`ComplexDynamic`。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSnapshotPolicy.kt`
  - 负责按 classification、unsupported reason、visual hints 和 native failure 决定 native/snapshot/dynamic preview。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSafety.kt`
  - 负责白名单、安全 URL、节点/深度/表格/SVG 预算。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlRootDetector.kt`
  - 统一维护 rich root 识别、闭合匹配、扫描预算和 `script/style/comment` 跳过逻辑，供切块和 streaming arbiter 复用。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/StreamRenderArbiter.kt`
  - 负责流式文本发布节流和富 HTML 闭合边界判断。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlBubbleBlock.kt`
  - Compose 兼容入口，异步请求 render model，并在 native fallback 前路由 snapshot。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderScheduler.kt`
  - viewport-aware rich render scheduler、height cache、circuit breaker、near-viewport prewarm 队列和 native 首渲染 admission。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlSnapshotBlock.kt`
  - 聊天列表静态快照 UI：占位、缓存命中、离屏渲染、图片展示和点击打开动态预览。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlSnapshotCache.kt`
  - Snapshot 内存 LRU 和 in-flight 去重。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlCompiler.kt`
  - 原生富 HTML 编译器。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderModel.kt`
  - 原生 render IR：`RichBlock`、`ComputedStyle`、SVG model 等。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderer.kt`
  - Compose renderer，只消费 render IR。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichSvgCompiler.kt`
  - SVG 静态子集编译器。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichSvgPreparedDrawCache.kt`
  - SVG prepared draw cache，缓存 path/paint/shader/dash effect 等绘制资源。
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleWebView.kt`
  - 保留给动态预览、全屏预览和少量 WebView 场景。
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleSnapshotRenderer.kt`
  - 离屏 WebView 快照渲染器，复用 renderer shell、DOMPurify、主题变量和测高逻辑。

## 已支持的切块形态

当前切块层支持：

- `[--- VCP元思考链:` 到 `[--- 元思考链结束 ---]`，渲染为 Compose 折叠块。
- fenced `VCPToolCall` 和裸 `<<<[TOOL_REQUEST]>>>...<<<[END_TOOL_REQUEST]>>>`，渲染为 Compose 折叠块。
- `[[VCP调用结果信息汇总:` / `[[工具调用结果信息汇总` 到对应结束标记，渲染为 Compose 折叠块。
- `<div id="vcp-root">...</div>`。
- `<div id="response-root">...</div>`。
- `<div id="vcp-*-widget">...</div>`，例如 `vcp-net-widget`、`vcp-clock-widget`。
- `<details>...</details>`。
- 带明显视觉样式的 `<div class=... style=...>`，例如 `math-block`、card、widget、panel。
- 紧邻富 HTML 前面的 `<style>...</style>` 会合并到同一个 HTML 块，不再作为 Markdown 残留。
- Markdown 代码围栏中的 HTML 不会被误切成富 HTML。

## Render Model

`RichHtmlRenderModel` 是聊天富 HTML 的原生中间表示，当前主要 block 包括：

- `RichTextBlock`：拍平后的 `AnnotatedString`，可携带 inline math run。
- `RichContainerBlock`：普通容器、flex/grid 容器、视觉卡片。
- `RichImageBlock`：安全图片。
- `RichTableBlock`：HTML 表格模型。
- `RichSvgBlock`：静态 SVG model。
- `RichMathBlock`：块级/行内公式。
- `RichButtonBlock`：可展示或发送输入的按钮。
- `RichDetailsBlock`：`details/summary` 折叠块。
- `RichUnsupportedBlock`：安全失败或过复杂内容的稳定占位。

`ComputedStyle` 承载编译后的最终样式，包括 display、position、颜色、背景、盒模型、边框、阴影、尺寸、flex/grid、offset、transform、overflow、字体、文本等。Renderer 不再回头查 DOM 或 CSS 字符串。

背景渐变和 SVG 渐变现在会保留 `RichColorStop(color, offset)`，不再只保存颜色列表；这让 `linear-gradient(... 45% ...)` 和 SVG `linearGradient stop-opacity/offset` 能更接近 WebView 的静态视觉结果。

## 最近已完成的架构硬化

- root detector 收敛：`MessageTextBlocks` 和 `StreamRenderArbiter` 使用同一套 rich root 检测规则，避免 streaming、历史消息和最终切块之间识别不一致。
- 同步预检移除：聊天列表常规路径不再在 composition 中同步调用完整 `RichHtmlCompiler.compile()` 校验；分类后直接进入 async compile，非致命失败由现有动态预览兜底。
- 缓存键硬化：所有渲染文本缓存统一使用 `renderTextCacheKey(text)`，格式为 `length:sha256:<16-byte-hex>`，不再使用弱 `hashCode + head/tail`，也不泄露长正文片段。
- 缓存层统一：Markdown parse/html/document、MessageTextBlocks、RichHtmlSafety、RichHtmlClassification、RichHtmlCompiler 等内存 LRU 都使用同一个 cache helper，并暴露 hit/miss/eviction 统计。
- 首帧复用：`RichHtmlBubbleBlock` 初始 state 会先查 `RichHtmlCompiler.getCached()`，历史气泡回到视口时能直接复用模型。
- 编译并发收敛：async compile 走 bounded dispatcher + in-flight dedupe，避免切换聊天记录时同一批历史气泡重复启动大量 Jsoup/CSS 编译。
- Snapshot 兜底首片：`RichHtmlSnapshotPolicy` 默认启用，`NativeStatic + WebViewFallback`、安全 unsupported、关键 visual hints 和原生非致命渲染失败会优先尝试 WebView 离屏快照；`InteractiveStatic` 继续原生优先以保留按钮输入，`ComplexDynamic` 继续动态预览。
- Snapshot 安全边界：`BubbleSnapshotRenderer` 复用 `bubble-renderer` shell 和 DOMPurify，WebView 设置禁外部导航、禁网络加载、禁 file URL 跨域访问，单队列串行渲染并限制 timeout、最大高度和像素预算。
- Snapshot 缓存：`RichHtmlSnapshotCache` 首版只做内存 LRU，默认 24 entries 或 48MB，key 绑定 HTML digest、宽度、density、fontScale、主题 hash 和 renderer version，且同 key in-flight 渲染会去重。
- Snapshot v1.1 hardening：`BubbleSnapshotRenderer` 改为保留离屏 WebView session，`RichHtmlSnapshotBlock` 只给可见/近视口条目启动 snapshot；telemetry 记录 cache hit/miss、in-flight join、queue wait、render time、session reuse 和高度 delta warning。

## 最近已完成的保真校准

这轮修复主要针对“字体过小、行高不准、默认间距不对、手机上一列 grid 高度异常”的问题：

- UA baseline：编译阶段为 `p/h1-h6/ul/ol/li/pre/code/button/details/summary/table` 等标签补最低优先级浏览器默认样式，再叠加 `<style>`、选择器和 inline style。
- `font` shorthand：支持 `font: italic 700 0.75rem/1.6 'Fira Code', monospace` 这类写法，能解析 font-size、line-height、weight、style、family。
- `rem/em/%/px`：修复 `rem` 被 `em` 分支提前吞掉的问题；`0.75rem`、`0.9rem`、`0.95rem` 这类 VCP 高频小字现在能稳定解析。
- unitless line-height：`line-height: 1.6` 会作为倍率继承，子元素修改字号后按自己的 font-size 重新计算行高，更接近浏览器 computed style。
- `line-height: normal`：按约 `1.2em` 静态近似，不再完全依赖 Compose 默认行高。
- `text-transform`：支持 `uppercase/lowercase/capitalize/none`，`.label { text-transform: uppercase }` 这类视觉标签会生效。
- 按真实宽度编译：`RichHtmlBubbleBlock` 通过 `BoxWithConstraints` 传入当前气泡宽度，`@media`、百分比尺寸、grid 估算不再固定按 360dp。
- mobile `auto-fit`：`repeat(auto-fit, minmax(280px, 1fr))` 在 Pixel 8 这类手机宽度下会估算成单列满宽，减少因卡片被硬压到 280dp 造成的额外换行和高度膨胀。
- flex/grid item 保留：flex/grid 容器下的直系 inline 子元素不再被父容器拍平成单个文本块，`order`、`flex-basis`、`flex-grow`、`align-self`、`grid-column` 等子项样式可以进入各自 block。
- Phase 3 v1：`order`、`align-content`、`align-self`、`flex-basis/grow`、`grid-column: span N`、`grid-row: span N` 已进入 model；renderer 对普通流 children 按 `order` 稳定排序，对 Row/Column 的 `align-self` 和 `flex-grow/basis` 做 scope 内近似，对 FlowRow 的 `align-content` 做静态近似，对 grid column span 做父列数下的宽度近似。
- Phase 4 v1：`RichTableBlock` 现在保留 `caption`、`captionStyle`、`thead/tbody/tfoot` section、`th` 标记、`colspan/rowspan` 和 `border-collapse`；renderer 优先消费 section model，并通过 `SpannedDataTable` 原生接收 colspan/rowspan metadata，不再用空白 cell 展开模拟。cell background、text-align、vertical-align 会进入表格 cell 对齐/背景策略。
- Phase 1/2 v2 首片：背景新增 `background-origin/background-clip`、多背景层计数、`background-repeat: round/space`；renderer 已用 origin/clip 近似背景绘制区域，`round` 会调整 tile 尺寸，`space` 会分配 tile 间隔；列表图片 marker 改成固定 marker box；列表内简单 `counter(section)` 会静态编号。
- Phase 3 v2 首片：`grid-column: 1 / 3` 这类简单线号会解析为 span，`grid-row: span N` 会转为最小高度近似。
- Phase 4 v2 首片：支持 `caption-side: top/bottom`，collapse 表格边框减半近似以减少双线感，基础 rowspan 会参与单元格占位与跨行高度约束。
- Phase 3 v3 首片：非 wrap Row flex 会在父宽度已知且子项有 `flex-basis/width` 时按 `flex-shrink * basis` 做静态压缩；wrapping FlowRow 的 `align-content: stretch` 会给子项稳定最小高度，减少挤压塌陷。
- Phase 3 v3 FlexBox spike：显式接入 `androidx.compose.foundation:foundation-layout`，`display:flex` 迁移到官方 Compose `FlexBox`，直接映射 direction/wrap/justify-content/align-items/align-content/gap/order/grow/shrink/basis/align-self；`flex` shorthand 已支持常见 grow/shrink/basis 解析，子项 width/max-width/basis 会按 FlexBox 主轴约束策略进入测量。
- Phase 3 v3 FlexBox 语义补全：`row-reverse/column-reverse`、`wrap-reverse`、`flex-flow`、双值 `gap`、`row-gap/column-gap`、`justify-content:space-evenly`、`align-items/align-self:baseline` 已映射到官方 Compose FlexBox；`align-self:auto` 保持默认自动行为；`align-content:space-evenly` 因当前 Compose FlexBox API 无对应值，稳定降级到 `SpaceAround`。
- Phase 3 v4 Grid 首片：`display:grid` 从 `FlowRow` 近似迁移到项目内非 Lazy `Layout`，会全量测量子项，按列数/gap 分配轨道，支持 auto placement、`grid-column/grid-row` 的显式起始线、`span N`、二维 span 占位和 row span 自然高度回推。暂不引入 GridPad；GridPad 需要有限宽高，更适合预定义棋盘，不完全匹配聊天气泡的内容自然撑高语义。
- CSS painting v3：`background-clip:text` + gradient 背景不再只是安全降级，文本块会用 Compose `SpanStyle(brush=...)` 渲染渐变文字；URL 背景裁文字、复杂 text fill 仍暂不实现。
- Phase 4 v3 首片：`thead` 多行、`tfoot` 和 body section 类型会进入 `SpannedDataTable`；header/footer 行有默认背景差异，rowspan 高度不足时会把高度缺口分摊到跨越的多行。
- SVG v2.5 首片：SVG paint 扩展到 `radialGradient` shader；元素/stop 的 `opacity/fill-opacity/stroke-opacity` 会进入颜色 alpha；`g` 上的 fill/stroke/stroke-width 可被子图元继承；stroke linecap/linejoin/dasharray、text-anchor 和基础 font-weight 会进入 renderer。
- Fidelity foundation 首片：`RichHtmlRenderModel` 新增内部 visual hints，用于记录不含正文的静态保真缺口；render seed fidelity report 测试会统计标签、CSS 属性、SVG 特性、fallback、hint、编译耗时和 model block count。
- background repeat：`repeat/repeat-x/repeat-y/round/space` 不再只停在 model，URL/data 背景会在 renderer 中以安全 Canvas tile 近似绘制；`no-repeat` 仍走单图背景路径。
- CSS filter 首片：`filter/backdrop-filter` 不再只是不可见 hint；compiler 会记录安全静态子集 `blur()/brightness()/opacity()/grayscale()`，renderer 已低成本消费 `filter: opacity(...)` 到 alpha，并用颜色矩阵近似渲染 `filter: brightness()/grayscale()`；`blur()` 和 backdrop blur 仍保留结构化 model 与 visual hint，避免引入昂贵实时模糊。
- CSS color v3.1：颜色解析新增 `hsl()/hsla()`，覆盖 AI 生成卡片中常见的 hue/saturation/lightness 写法；hex/rgb/rgba/named colors 仍保持原有支持。
- Color Fidelity v4 首片：颜色解析抽出 `RichColorUtils`，新增 `RichCssColor` 语义，区分未声明、`transparent`、`currentColor` 和无法解析的颜色函数；CSS color parser 支持完整 named colors、modern `rgb(... / alpha)`、百分比 RGB、modern `hsl(... / alpha)`；renderer 新增 `RichRenderColorDefaults` 和 `RichColorResolver`，由 MaterialTheme 补缺省色，并用 AndroidX `ColorUtils.calculateContrast()` 对低对比纯色文本做最小可读性修正。
- Animation Static v1：CSS `animation/transition/@keyframes` 不再作为聊天列表原生渲染的一票否决条件；compiler 会记录 `CssAnimation/CssTransition/CssKeyframes/CssInfiniteAnimation/CssLayoutAnimation/CssInteractivePseudoClass/AnimationDependentVisibility` visual hints，并把安全动画默认静态化显示。`:hover/:active/:focus` 规则只记录 hint，不作为普通静态样式套到元素上；`opacity:0 + animation-fill-mode:forwards` 的有限 opacity/transform 动画会被保护为可见静态态，避免内容空白。
- Animation v3：有限 `@keyframes` 入场动画如果只包含 `opacity` 和 `transform: translate/scale/rotate`，且非 infinite、无布局属性、duration <= 1200ms、delay <= 1500ms，会被编译成 `RichNativeAnimation` 并由 Compose 播放一次；支持 `animation-timing-function` 的 `linear/ease/ease-in/ease-out/ease-in-out/cubic-bezier(...)`、`animation-direction: normal/reverse`、`animation-fill-mode: none/forwards/backwards/both`、有限整数 `animation-iteration-count` 和多 keyframe opacity/transform stops。多个 animation 只取第一个安全动画原生播放，其余进入 stats/hint。
- Animation Budget v3：`RichHtmlRenderModel` 的内部 `animationStats` 统计 animated/native/staticized/infinite/layout/transition/dependent visibility/budget exceeded/play-once suppressed/snapshot candidate/multi-keyframe/unsupported property；每个富 HTML 气泡默认最多保留 3 个原生播放动画，超出部分静态化并记录 `AnimationBudgetExceeded` hint。layout/filter/color/尺寸类动画、复杂 dependent visibility 和超预算动画会进入 snapshot candidate；fidelity report 会输出策略和计数，不包含正文。
- CSS 驱动按钮：`RichButtonBlock` 不再使用 Material Button 默认字号、内边距和最小高度覆盖作者 CSS，而是通过 `StyledContainer + Text + clickable` 渲染。
- 盒模型边界：renderer 不再给 root 容器偷偷加默认圆角或默认间距；margin 作为外层 spacing 应用，padding 只来自 UA/作者 CSS。
- 空视觉装饰盒保留：空的 `div/span` 如果带有 position、尺寸、背景、边框、阴影、透明度、filter 或 transform，会保留为 `RichContainerBlock`；这类节点常用于背景光斑、badge、圆点、分隔装饰，不再因为没有正文而被拍平成空文本丢失。

## 原生富 HTML 能力

当前支持标签：

- 文本结构：`div`、`section`、`article`、`main`、`header`、`footer`、`aside`、`p`、`h1` 到 `h6`、`span`、`br`、`hr`。
- 行内样式：`strong/b`、`em/i`、`u/ins`、`del/s/strike`、`sub`、`sup`、`mark`、`kbd`、`code`、`a`。
- 列表和引用：`ul`、`ol`、`li`、`blockquote`。
- 代码：`pre/code`，以文本块稳定显示。
- 表格：`table`、`caption`、`thead`、`tbody`、`tfoot`、`tr`、`th`、`td`，通过 `SpannedDataTable` 展示，并保留 section、header cell、colspan/rowspan、caption style 和 cell style。
- 媒体：`img`，只允许安全 URL，复用 `ZoomableAsyncImage`。
- 控件：`button`。
- 折叠：`details/summary`。
- SVG 静态子集：`path`、`rect`、`circle`、`ellipse`、`line`、`polyline`、`polygon`、`text`、基础 `g/transform`、linear/radial gradient、opacity、stroke linecap/linejoin/dasharray、text-anchor。

当前支持 CSS/样式能力：

- 选择器：tag、class、id、后代选择器、子选择器、相邻/兄弟选择器、属性选择器、`:first-child`、`:last-child`、`:nth-child(...)`、`:not(...)` 的静态匹配。
- 级联：specificity、声明顺序、inline style 优先级、继承属性、`:root` 和 `var(--x)`。
- `@media`：支持按当前宽度和 dark/light 做静态分支选择。
- 颜色：`#rgb`、`#rgba`、`#rrggbb`、`#rrggbbaa`、legacy/modern `rgb()/rgba()`、百分比 RGB、legacy/modern `hsl()/hsla()`、`transparent`、`currentColor`、完整 CSS named colors；`color-mix()` 等复杂颜色函数会保留为 unresolved 语义并记录 visual hint，不会错误退成黑色。
- 背景：纯色、`background-color`、`background`、`background-image`、URL/data image 背景、带 color stop 的 linear/radial/conic gradient 静态近似；`background-size` 支持 `cover/contain/auto`、双值长度/百分比，`background-position` 支持常见关键字、百分比和 `right 12px bottom 8px` 这类四值偏移，`background-repeat` 支持 `no-repeat/repeat/repeat-x/repeat-y/round/space`，repeat 类 URL/data 背景会走 Canvas tile 近似，`background-origin/clip` 会影响背景绘制/裁剪区域；`background-clip:text` 的 gradient 背景会作为文字 Brush 渲染。
- 保真度提示：多背景层、CSS filter/backdrop-filter/mix-blend/mask/clip-path、复杂颜色函数、复杂 table span、SVG clip/mask/filter/use/symbol/pattern/marker 等会记录 debug-only visual hints，后续用真实样例频率决定实现优先级；其中 `filter/backdrop-filter` 的安全静态函数已进入结构化 model。
- 间距：`padding`/`margin` 的 1 到 4 值，以及 `padding-*`、`margin-*`；长度值支持 px/dp/rem/em/%，并对嵌套 `calc()/min()/max()/clamp()` 做编译期静态近似。
- 边框：`border`、`border-*`、`border-color`、`border-width`、`border-style`、四角 `border-radius`；renderer 已支持 solid/dashed/dotted/double 和分边绘制近似。
- 字体：`font` shorthand、`font-size`、`font-weight`、`font-style`、`font-family`、`line-height`、`letter-spacing`、`font-variant-numeric` 静态记录；`px/rem/em/%` 会基于继承字体或 root font 做静态换算。
- 文本：`text-align`、`text-shadow`、`text-decoration`、`text-transform`、`vertical-align` inline 近似、`white-space`、`word-break`、`overflow-wrap`、`text-overflow`。
- 尺寸：`width`、`height`、`min-width`、`max-width`、`min-height`、`max-height`，支持 px/rem/em/dp、百分比和嵌套长度函数的静态换算。
- 布局：`display:block/inline/inline-block/flex/grid/none`。
- Flex：`flex-direction`、`row-reverse/column-reverse`、`flex-wrap/wrap-reverse`、`flex-flow`、`align-items`、`align-self`、`align-content`、`justify-content`、双轴 `gap/row-gap/column-gap`、`order`、`flex-grow/shrink/basis` 和常见 `flex` shorthand；renderer 已接入官方 Compose `FlexBox`，普通流 children 的 order/grow/shrink/basis/align-self 由 FlexBox 参与测量，子项 width/max-width 约束会保留。
- Grid：固定列、`fr`、`repeat(n, ...)`、`repeat(auto-fit,minmax(...))` 的非 Lazy Compose `Layout`；子项宽度以父容器 `grid-template-columns` 为准，支持 `grid-column/grid-row: span N`、`start / end`、`start / span N` 和 longhand `grid-column-start/end`、`grid-row-start/end` 的安全静态子集。renderer 会自动找空格子并回推 row span 的自然行高。
- 定位：`relative/absolute/fixed/sticky` 编译进 model；renderer 将 absolute/fixed/sticky 子节点叠到同一容器 overlay 层，按 left/top/right/bottom + transform 做安全静态近似。
- 变换：`translate`、`scale`、`rotate`、`skew` 编译进 model；renderer 对 translate/scale/rotate 做静态近似。
- Overflow：`hidden` 裁切，`scroll/auto` 降级为滚动容器。
- 阴影：多层 `box-shadow` 编译进 model；renderer 按 offset、blur、spread、color 绘制非 inset 静态近似。聊天列表的最外层 rich root 不绘制外扩阴影，避免气泡边界外出现大面积晕染；内部子卡片/按钮阴影仍保留。
- 透明度：`opacity`。
- CSS filter：`filter/backdrop-filter` 支持解析 `blur()/brightness()/opacity()/grayscale()` 到内部 model；renderer 当前将 `filter: opacity(...)` 合并到现有 alpha，并用低成本颜色矩阵近似渲染 `brightness()/grayscale()`；`blur()` 和 backdrop blur 继续作为结构化静态缺口由 visual hint 统计。
- CSS animation/transition：`animation`、`animation-*`、`transition`、`transition-*` 和 `@keyframes` 会进入内部动画摘要；有限、非 infinite、只操作 `opacity` 与 `transform: translate/scale/rotate` 的入场动画会用 Compose 原生动画播放一次并停在确定静态态；支持多 keyframe stops、reverse、fill-mode、有限 iteration 和常见 easing。transition 状态机、hover/focus 动画、布局属性动画、filter/color 动画和无限动画不播放，只静态化并记录复杂度 hint；复杂动画会进入 snapshot candidate。
- 颜色默认与对比度：未声明文本色由 renderer 从当前 `MaterialTheme.colorScheme` 补齐；作者明确声明的颜色、背景、渐变、边框和阴影默认不重写；仅当纯色文本和有效纯色背景对比度明显不足时，renderer 会向黑/白中对比更高的一侧做最小混合修正。渐变/图片背景不做误判强修正。
- 列表：`list-style-type`、`list-style-position` 和安全 `list-style-image` 会进入 model；`ol start/reversed/type` 会影响 marker；安全图片 marker 会以小图近似渲染，失败回退文本 marker；嵌套列表按深度增加缩进。
- 伪元素：`::before/::after` 的静态纯文本 `content`，支持字符串拼接、多个 `attr(...)`、`\00xx` unicode escape，以及列表内简单 `counter()/counters()` 静态编号。
- 图片：`object-fit` 和背景图 `background-size/background-position/background-repeat` 的基础近似。
- 表格：`caption`、`caption-side`、`thead/tbody/tfoot`、`th`、cell style、`border-collapse`、简单横向 `colspan` 和基础 `rowspan` 占位进入 renderer；多行 header/footer 会保留 section 默认样式差异，rowspan 会做跨行高度分摊；宽表格仍以稳定横向滚动优先。
- z-index：同容器 positioned overlay 子节点的静态排序近似。

## 当前缺口

这些项仍然是后续优先补齐的静态 CSS 能力，不属于当前“已完整支持”：

- `calc()/min()/max()/clamp()` 已支持嵌套和连续加减，但仍不做乘除、viewport runtime reflow 或真实 containing block 百分比重排。
- `background-repeat` 已有安全 tile 近似，`round/space` 已有尺寸/间隔近似；仍不实现浏览器完整 background painting area、多背景层绘制和 sub-pixel 级 repeat 算法。
- `background-position` 已支持常见两值/四值偏移，并会基于 `background-origin` 的近似绘制区域定位，但偏移仍基于静态 dp，不实现浏览器对 containing block 的完整重排算法。
- `background-clip:text` 已支持 gradient 文字 Brush；但 URL 背景裁文字、复杂 `-webkit-text-fill-color` 组合和多层背景裁文字仍降级。
- `filter/backdrop-filter` 已有安全函数解析，`filter: opacity()/brightness()/grayscale()` 已有渲染闭环；`blur()` 与 backdrop blur 暂不做实时像素滤镜，继续作为结构化 visual hint 排期。
- CSS animation 当前是 Animation v3：finite opacity/transform 动画已支持常见 easing、reverse、fill-mode、有限 iteration 和多 keyframe stops，并由 Compose 原生播放一次；每气泡有原生动画数量预算，超预算或 layout/filter/color/复杂 visibility 动画会进入 snapshot candidate。仍不实现 CSS transition 状态机、hover/focus runtime、无限动画 runtime 和浏览器完整 animation composition。
- `currentColor` 已用于普通文本色继承、背景色和边框/阴影的静态解析；但渐变 stop、SVG 外的复杂 paint server 和 `color-mix()/lab()/lch()/oklch()` 仍不做完整颜色空间计算，当前只记录 unsupported color hint。
- 对比度兜底只处理纯色背景下的文本色；不会对渐变、背景图、透明叠层或 backdrop-filter 做浏览器级有效背景采样，也不会自动重绘作者品牌配色。
- `z-index` 的完整 browser stacking context。
- `list-style-image` 已有小图 marker 近似，但尚未实现浏览器级 marker sizing、baseline 对齐和 marker box。
- `::before/::after` 已支持列表内简单 counter；复杂 counter reset/increment、URL content 和完整 generated content 仍降级。
- `vertical-align` 已做 baseline shift 近似，不实现浏览器 inline formatting context 的完整顶/底对齐算法。
- Flex 已接入官方 Compose FlexBox 并补齐主干 CSS 语义；剩余差异主要是复杂百分比高度、min-content/max-content、浏览器完整 shrink 细节、inline formatting baseline 与 `align-content:space-evenly` 的 API 降级。
- 表格已保留 `caption/caption-side/thead/tbody/tfoot`、cell style、`border-collapse`、简单横向 `colspan` 和基础 `rowspan` 占位，并对 rowspan 高度做跨行分摊近似；完整 border conflict resolution、复杂混合 rowspan/colspan placement 和浏览器级 table layout 仍未实现。
- SVG 的 `use`、`symbol`、`clipPath`、`mask`、filter、pattern、foreignObject 仍保持降级；`radialGradient` 已支持基础静态 shader，但不实现 browser/SVG spec 的完整 gradientUnits/spreadMethod/transform。

## 保真度报告

- Render seed fidelity report 只输出元数据：样例 id/hash、标签计数、CSS 属性计数、SVG 特性计数、visual hints、fallback reason、compile time、model block count。
- 报告不输出用户聊天正文，不记录完整 HTML/CSS 内容。
- 下一阶段所有 CSS/SVG/table 深化优先级应参考 visual hints 的真实样例频率，避免继续靠主观感觉补低频能力。

## 质量门禁

当前最终目标不是单个截图“看起来还行”，而是每轮渲染改动都至少通过以下门禁：

- 切块/分类门禁：`MessageTextBlocksTest` 必须保证 VCP root、style 合并、代码围栏、动态内容和协议日志不会串块。
- 流式门禁：`StreamRenderArbiterTest` 必须保证未闭合富 HTML 不反复编译，闭合后能 flush，滚动中不把已显示内容替换成占位。
- seed 门禁：`RenderSeedFixtureTest` 必须保证 debug render seed 中的真实样例能稳定分类、编译或安全降级。
- hardening 门禁：`RichHtmlHardeningTest` 必须覆盖安全预算、取消、缓存、fallback reason、CSS declaration fallback 等稳定性边界。
- fidelity report 门禁：`RichHtmlFidelityReportTest` 必须保证 visual hints/report 稳定输出，且不包含用户正文。
- quality gate 门禁：`RichHtmlQualityGateTest` 必须覆盖代表性静态高保真样例，包括玻璃卡片、flex/grid、表格、SVG，以及 script/iframe/canvas/javascript URL 的动态或不安全降级。
- 构建门禁：`:app:assembleDebug` 必须通过，避免只在 JVM model 层过关但 Android/Compose API 发生编译或打包回归。

## 能力矩阵

| 类别 | 状态 | 说明 |
| --- | --- | --- |
| 常见 HTML 结构 | Supported | `div/span/p/section/article/header/footer/ul/ol/li/table/img/button/details/svg` 等安全静态标签。 |
| CSS 选择器与级联 | Supported | tag/class/id、后代/子/兄弟选择器、属性选择器、部分伪类、inline style、变量和基础 `@media`。 |
| 基础视觉样式 | Supported | typography、spacing、color、background、border、radius、shadow、gradient、opacity、overflow、z-index、object-fit 的静态近似。 |
| Flex/Grid | Partial+ | flex 已迁到官方 Compose FlexBox，方向、反向方向、换行、反向换行、双轴 gap、order、grow/shrink/basis、baseline、align-self 等 CSS 语义基本接满；grid 已迁到项目内非 Lazy Layout，支持固定/auto-fit 列、显式线号、二维 span、auto placement 和自然行高回推，但仍不是完整浏览器 Grid。 |
| 定位与层叠 | Partial | absolute/fixed/sticky 走同容器 overlay 静态近似，不实现完整 stacking context。 |
| SVG | Partial | path/rect/circle/ellipse/line/polyline/polygon/text、基础 transform、linear/radial gradient、opacity、dash stroke 和 text-anchor；复杂 defs/filter/mask/clipPath/use/symbol 降级。 |
| WebView 静态快照 | Supported v1 | 复杂但安全的静态内容可离屏生成 bitmap；默认内存 LRU、禁网络、禁外部导航，点击整张图进入动态预览。 |
| 动态 HTML | Unsupported/Fallback | JS、canvas、iframe、video/audio、WebGL、Mermaid runtime 等进入动态预览/WebView。 |
| 浏览器交互态 | Static/Ignored | form controls、hover/active/focus、CSS 动画运行时、mask/clip-path 不在聊天列表执行；hover/focus 规则只记录 hint；filter/backdrop-filter 的安全函数会记录，`filter: opacity(...)` 可低成本生效。 |

## 高保真策略

- 优先覆盖真实 VCP 高频 HTML/CSS：卡片、工具按钮、状态徽章、网格信息块、表格、公式、图片、基础 SVG。
- 原生路径追求静态视觉一致、滚动稳定和低崩溃率；遇到完整浏览器能力需求时不强行模拟。
- 动态/超复杂内容保留 WebView 预览；复杂但安全静态内容可用离屏 snapshot 兜底，但不恢复为聊天列表里的大量 live WebView。
- 高保真优先级是先修浏览器 baseline：UA 默认样式、字体继承、`rem/em/%`、line-height、margin/padding、按钮盒模型、当前宽度下的 grid/flex 估算。

按钮策略：

- 只有 `data-send`、`data-input`、`onclick="input(...)"` 会触发 `onBubbleInput`。
- 只有 `data-action` 的按钮只展示，不发送，避免误触发真实工具动作。

公式策略：

- Markdown 仍使用既有 LaTeX 渲染路径。
- HTML 中的 `$...$`、`$$...$$`、`\(...\)`、`\[...\]` 会尝试转成 inline math。
- `formula-box`、`math-font`、`class="math"`、`math-block`、KaTeX 常见结构和 `<math>` 这类公式容器会尝试走 `MathInline` / `MathBlock`。
- `math-block` 内如果包含标题、正文、公式盒子和表格，会按容器继续拆分，不再强制当成单个公式。

## 分类和安全策略

静态 CSS 动画不再直接判为动态。以下内容会静态降级或忽略动态效果：

- `@keyframes`
- `animation`
- `transition`
- `:hover/:active/:focus/:focus-visible`
- `filter`
- `backdrop-filter`
- `box-shadow`
- `linear-gradient`
- `position:absolute/relative/fixed/sticky`

以下内容仍判为动态或不安全，不在聊天列表执行：

- `<script>`
- `<canvas>`
- `<iframe>`、`object`、`embed`
- `video/audio`
- `requestAnimationFrame`
- `setInterval`
- `THREE.` / `WebGLRenderer`
- `mermaid.`
- `javascript:` URL

动画策略当前处于 Animation v3：简单动画默认原生静态化，finite `opacity/transform` 动画可由 Compose 原生播放一次，并支持常见 easing、reverse、fill-mode、有限 iteration 和多 keyframe stops；每气泡默认最多播放 3 个原生动画，超预算会静态化并记录 hint；infinite、layout/filter/color 动画、交互伪类和复杂 dependent visibility 会进入 visual hints 或 snapshot candidate。不在当前聊天列表持续跑 CSS 动画。

事件属性策略：

- `onclick="input(...)"` 会被提取为按钮输入动作。
- `onmouseover/onmouseout` 等 hover-only 事件不会在聊天列表执行，也不会因为存在就强制 WebView；静态视觉仍走原生路径。
- 其它未知事件代码不会执行；如果内容依赖运行时事件效果，用户应通过动态预览查看。

安全预算：

- 最大节点数：`900`
- 最大嵌套深度：`48`
- 最大文本长度：`80_000`
- 最大表格单元格：`160`
- 最大 SVG command：`128`
- 最大 SVG path 字符数：`8_000`

超预算或不安全内容会降级为动态预览入口，不会让聊天页崩溃。

## 流式和滚动策略

当前流式策略：

- 普通 Markdown streaming 约 `120ms` 采样发布，避免每个 token 都触发重组。
- 检测到未闭合 `vcp-root`、`response-root`、`vcp-*-widget`、`details` 时，先发布一次稳定占位。
- 未闭合富 HTML 的后续增量不会持续编译和测高。
- 富 HTML root 闭合后立即 flush 并进入最终分类/渲染。
- final frame 强制 flush，避免最后一段内容延迟显示。

当前滚动策略：

- 已移除“滑动中把富内容替换成暂缓渲染”的可见行为。
- 滚动过程中已经显示出来的富内容不能消失。
- 未来如果继续做滚动优化，只能暂停新解析/新加载，不能替换已显示内容。
- 富 HTML 编译入口支持协程取消；气泡滑出或输入变化导致 `LaunchedEffect` 取消时，未完成编译不会写入最终模型缓存。
- LRU cache 现在可输出 size/hit/miss/eviction 统计，用于 debug-only 聚合观察。

## 稳定性与观测

- `RichHtmlSafety`、`RichHtmlClassification`、`RichHtmlCompiler` 和 renderer 兜底会记录 debug-only fallback reason 聚合计数，不记录聊天正文。
- Compose 原生 renderer 出现非致命异常时会降级到稳定占位或动态预览入口，不让聊天页整体崩溃。
- Snapshot route 会记录 debug-only `snapshot start/success/failure`、宽高、耗时、queueWait、cacheHit、joinedInFlight、heightDelta warning 和 reason；日志只包含 digest id，不记录 HTML 正文、按钮文本或完整 CSS。
- Macrobenchmark/Baseline Profile 的 target app 使用 profileable `baseline` 变体时会自动导入 `render_seed/chat_render_seed.json`，保证富 HTML、长 Markdown、SVG、表格和 snapshot mix journey 有稳定样本。
- `ph-css` 已开始用于普通 CSS declaration list 解析；遇到 CSS 变量、自定义属性或解析失败时回退项目内置 parser，保留现有 selector matcher 和 computed style。
- Android packaging 已排除 `META-INF/buildinfo.xml`，避免 ph-css 传递依赖在 debug 打包时资源冲突。
- 真机日志里当前重点看 `RichHtmlRender`：`compile start/success/failure`、`route=native/route=fallback/route=snapshot`、`widthDp/widthPx`、渲染尺寸、snapshot queue/render 耗时、cache 命中和高度 warning。日志不打印正文，只打印 digest id。

## 真实数据覆盖情况

此前对 Pixel 8 debug 数据做过只读聚合统计，不输出聊天正文：

- 富 HTML root 约 `155` 条。
- 明显动态约 `10` 条。
- 包含 `<style>` 约 `62` 条。
- 包含按钮约 `107` 条。
- 包含图片约 `27` 条。
- 包含表格约 `14` 条。
- 包含 LaTeX/公式约 `6` 条。

高频标签包括：

`div`、`b`、`p`、`br`、`span`、`strong`、`button`、`li`、`h3`、`td`、`h2`、`sub`、`h4`、`ul`、`code`、`style`、`tr`、`img`、`th`、`table`、`details`、`svg`。

高频 CSS 包括：

`color`、`padding`、`background`、`font-size`、`border-radius`、`border`、`margin`、`text-align`、`font-weight`、`display`、`font-family`、`box-shadow`、`line-height`、`align-items`、`gap`、`opacity`、`position`、`border-bottom`、`border-left`、`width`、`justify-content`、`height`、`max-width`、`animation`、`grid-template-columns`、`overflow`、`flex-wrap`。

因此当前重点放在增强原生编译/渲染覆盖率，而不是回到全 WebView。

## 测试种子

Debug seed 文件：

```text
app/src/debug/assets/render_seed/chat_render_seed.json
```

该 seed 覆盖：

- Markdown、代码块、Mermaid、LaTeX、表格。
- `vcp-root`、`response-root`、`vcp-net-widget`、`vcp-clock-widget`。
- `<style>`、class 规则、`@keyframes`、按钮、图片、`details/summary`。
- `pre/code`、`svg/path/rect/circle/ellipse/line/polyline/polygon/text`、`sub/sup`、HTML 表格。
- VCPDesktop/window/process list 类样式。
- WebView snapshot 候选：`backdrop-filter`、`mask-image`、`clip-path`、`mix-blend-mode` 这类安全静态但浏览器特效明显的卡片，以及 Snapshot v1.1 的小字体/固定高度/cache 观测样本。
- 转义的 `<script>` 文本，验证代码块内 HTML 不被误执行。

## 已验证命令

当前这轮已通过：

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :bubble-renderer:compileDebugKotlin :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlSnapshotPolicyTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichHtmlSnapshotCacheTest"
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:compileDebugAndroidTestKotlin
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.components.richtext.RichHtmlSnapshotRendererInstrumentedTest"
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.components.richtext.RichHtmlBubbleBlockComposeTest"
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:assembleDebug
```

最近一次 Pixel 8/SM-S937B connected 回归：

```powershell
$env:ANDROID_SERIAL='192.168.6.121:40011'
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.components.richtext.RichHtmlSnapshotRendererInstrumentedTest"
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.components.richtext.RichHtmlBubbleBlockComposeTest"
```

本次 connected 结果：`RichHtmlSnapshotRendererInstrumentedTest` 2 个测试通过，`RichHtmlBubbleBlockComposeTest` 4 个测试通过，覆盖静态快照生成、像素预算失败、snapshot image 展示、点击打开预览、原生按钮交互和长列表滚动。

建议回归时继续运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichHtmlFlexRendererMappingTest" --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

## 已知边界

当前原生渲染仍不是完整浏览器 CSS/JS 引擎：

- 不执行 JS。
- 不运行 CSS 动画，只静态展示内容。
- `filter: opacity()/brightness()/grayscale()` 已静态生效；`filter: blur()`、`backdrop-filter`、`mix-blend-mode` 这类视觉特效仍会记录 hint 或静态降级。
- `color-mix()`、`lab()`、`lch()`、`oklch()`、相对颜色语法等现代颜色空间暂不计算；当前会保持 unresolved 语义并记录 hint，避免误解析成黑色。
- 对比度兜底只修正文本文字色，且仅在有效纯色背景可判断时介入；不会做图片取色、渐变采样或全局 Material You 重绘。
- `@font-face` 首版只识别并降级到系统字体族。
- 字体 metrics 仍由 Android/Compose 决定，无法做到浏览器像素级一致；当前目标是解决明显字号、行高和盒模型偏差。
- `position:absolute/fixed/sticky` 是安全静态近似，复杂 overlay 和 z-index stacking context 不保证浏览器级重叠效果。
- CSS Grid 已支持聊天气泡高频静态子集，但仍不实现完整浏览器 grid auto-flow dense、命名线、负线号、`grid-template-areas`、复杂 track sizing 和完整 stacking context。
- 复杂 SVG 的 `use`、`symbol`、`clipPath`、`mask`、filter 等仍可能降级。
- 宽表格和复杂网格是稳定显示优先，不追求像素级浏览器还原。
- Snapshot 是整张静态图片，不支持内部按钮 hit-test；有 `data-send/data-input/input(...)` 的交互型卡片仍优先走原生路径，原生失败时才 snapshot 或动态预览。
- Snapshot 首版禁网络加载，远程图片或外部纹理可能不会出现在聊天列表静态图里；需要完整动态效果时点击进入 WebViewPage。
- Snapshot 首版只做内存 LRU，不写磁盘；切换宽度、density、fontScale、深浅色或主题后会重新生成。
- 当前 `com.helger:ph-css` 只用于声明解析硬化，现有 selector matcher 仍由项目内置实现负责。
- `background-size/position/repeat` 的完整浏览器语义、多背景层、`list-style-image` 的浏览器级 marker box、复杂 counter 伪元素和表格布局细化仍是后续优先补齐项。
- 原生渲染异常时会降级动态预览，用户可进 `WebViewPage` 查看完整动态内容。

## 下一步建议

优先级从高到低：

1. Snapshot policy v1.2：基于 v1.1 telemetry 校准 `AnimationBudgetExceeded`、复杂 SVG、mask/clip/filter 的 snapshot 阈值，避免交互型卡片过早丢失按钮。
2. CSS Painting v3.2：`clip-path: inset()/circle()/ellipse()` 已进入原生安全子集，圆形头像、光斑裁剪和胶囊装饰优先原生显示；复杂 path/polygon 继续只记录 hint，原生失败后再 snapshot。
3. Color Fidelity v4.1：补 `color-mix()` 的 sRGB 静态近似，只支持 safe 两色混合和百分比；`lab/lch/oklch` 继续 hint。
4. Animation Runtime v3.1：在现有 opacity/translate/scale/rotate 多 keyframe 播放基础上继续完善滚动中禁播、离屏禁播和历史消息重组默认最终态的运行时门控；继续拒绝 infinite、布局属性、filter/color 动画和完整 CSS transition 状态机。
5. Snapshot v1.2：增加 debug-only route report，把 height warning、cache miss 峰值和 failure reason bucket 输出到 QA 页面或开发菜单。
6. CSS Painting v3.3：多背景层已从“第一层渲染 + 其余 hint”推进到“主 gradient + 一个安全 URL/data tile/pattern”原生静态绘制，按 CSS 层顺序覆盖图案纹理叠 gradient 的高频卡片；更复杂层数继续只记录 hint。
7. CSS Painting v3.4：`mask-image: linear-gradient()/radial-gradient()` 已作为 alpha mask 原生近似；`filter: blur()` 只允许小半径安全近似；`backdrop-filter` 默认用半透明背景、描边、阴影和亮度覆盖近似，不做聊天列表实时背景采样。
8. SVG Paint v3：补 `gradientUnits/gradientTransform/spreadMethod`、安全 `clipPath` 子集和 marker 箭头，用于流程图、坐标轴和图标。
9. 质量闭环：真机视觉回归继续对比 native/snapshot/dynamic preview 的背景、色彩、滤镜、渐变文字、阴影、CSS animation 静态化和 SVG；debug-only report 只输出 hint/耗时/样例 id，不输出正文。
10. 继续扩大 ph-css 使用范围，但保持 selector matcher 可控，避免一次性替换成浏览器级 CSS 引擎。

## 少造轮子路线

结论：不要找“替代 RichHtmlRenderer 的万能库”。聊天列表的安全静态路径仍保持 `Jsoup -> RichHtmlCompiler -> Compose Renderer`，但把容易翻车的子系统逐步交给成熟库或官方工具。

### P0：CSS 与安全输入硬化

- **ph-css 深化**：继续用 `com.helger:ph-css` 承担 CSS 声明解析、容错 tokenization、`@media`/shorthand/嵌套函数切分；computed style、selector 白名单、specificity、安全预算仍由项目控制。验收目标是减少手写 split/regex，尤其覆盖 `background`、`border`、`font`、`filter`、颜色函数和复杂 selector body。
- **jsoup Cleaner/Safelist 收口**：在现有 jsoup 解析基础上增加标准化安全清洗层，统一危险 tag、危险 attribute、URL protocol 白名单和 data image 策略。验收目标是把分散的 tag/attribute 检查变成一份可测试 safelist，同时不改变现有 root detector 和 WebView fallback 策略。

### P1：媒体与 SVG 分层

- **Coil 继续作为唯一图片加载底座**：`img`、安全远程图、data image、GIF、普通 SVG image 都优先走 Coil Compose/decoder/cache，不自建下载、缓存、占位和尺寸探测。验收目标是 HTML 图片和背景图复用同一安全 request builder、尺寸约束和缓存策略。
- **AndroidSVG 作为复杂静态 SVG spike**：简单 inline SVG 继续走当前 IR/Canvas，以便保留可观测 command budget；复杂但安全的静态 SVG 可研究 AndroidSVG 渲染到 `Picture`/bitmap 作为兜底。`script/filter/mask/foreignObject` 仍不在聊天列表执行。验收目标是用 render seed 对比 AndroidSVG 与当前 SVG IR 的命中率、耗时和内存。

### P2：布局研究，不急于替换

- **官方 Compose FlexBox 继续主线**：现有 FlexBox 映射已经承接 `direction/wrap/gap/order/grow/shrink/basis/align-self`，短期只补 CSS 语义翻译和真实样例差异，不再引入第三方 flex 库。
- **Taffy 作为 Grid/Flex research spike**：Taffy 支持无 DOM 的 Flexbox/Grid 布局计算，但 Android/Compose 接入需要 Rust/WASM/JNI/缓存桥接，工程成本高。仅在 grid/table visual hints 显示真实高频且 Compose 近似无法推进时，做离线 spike：输入我们的 IR style tree，输出测量 box，再交 Compose 绘制；不直接替换 renderer 主路径。

### P3：动画与美化效果边界

- **Compose Animation 用于 App 级过渡**：只用于富 HTML 首帧淡入、动态预览入口、展开/折叠、高度变化等 App 控制动画；不执行模型输出的 CSS `@keyframes`。
- **Lottie/Rive 只处理明确资源**：只有当模型输出或未来协议明确给出安全 Lottie/Rive 资源时再接入，不用它们解释 CSS animation。
- **Haze 暂作 backdrop blur 评估项**：项目已有 Haze 依赖，但聊天列表实时 blur 成本高；只有当真实样例中玻璃拟态高频且 perf gate 可控时，才在低频装饰层试验。

### P4：性能治理成为硬门禁

- **Baseline Profiles**：为聊天列表冷启动、打开历史会话、首个富 HTML 编译/渲染、动态预览入口建立 baseline profile，减少首次运行解释/JIT 抖动。
- **Macrobenchmark**：新增富 HTML 场景 benchmark，覆盖长列表滚动、富 HTML 首帧、SVG/表格/渐变卡片、历史消息切换；验收指标至少记录 frame timing、startup/interaction latency、compile time 和 memory snapshot。
- **质量闭环**：每次引入库或重构子系统，都必须同时比较 visual hints 数量、compile time、model block count、APK/DEX 变化和真机首帧体验；低频能力不进主线，只保留 hint。

### 实施顺序

1. ph-css declaration/shorthand 迁移扩大到 color/background/border/font/filter，补 parser equivalence tests。
2. jsoup Cleaner/Safelist spike，先只做 report-only 对比，不直接改变渲染路径。
3. Coil request builder 收口，统一 `img`、background image、list-style-image 的安全加载策略。
4. AndroidSVG spike，用 10-20 个复杂静态 SVG seed 评估命中率和性能。
5. Baseline Profile + Macrobenchmark 模块化接入，把富 HTML 首帧和长列表作为性能门禁。
6. Taffy 仅做离线 research，不进入默认构建，除非真实 grid/flex 缺口证明收益大于 JNI/桥接成本。
