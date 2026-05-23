# UniVCP 富 HTML 聊天渲染开发现状

记录日期：2026-05-23，已同步到最近一轮 Pixel 8 真机调试和质量门禁后的状态。

本文记录当前聊天消息富 HTML 渲染链路的真实状态、能力边界和后续方向。核心目标是不修改 UniVCP/VCPChat 的 prompt 和历史数据格式，同时坚持聊天列表内 Compose 原生渲染优先，避免把安全静态内容退回成 live WebView。

一句话现状：当前架构已经从“能不能渲染”推进到“安全静态 HTML 默认原生渲染，并持续校准浏览器观感”的阶段。近期重点修复了同步编译卡顿、缓存键、root 检测重复、历史消息首帧空白、以及 `rem/font/line-height/grid/button` 等导致气泡高度和字号不保真的问题。

当前开发阶段：IR 编译器与 Compose renderer 已落地，架构硬化与高保真校准 v1 已完成一轮，静态 CSS 覆盖 Phase 1/2 v2 已进入维护补洞，Phase 3 布局增强 v3 已迁移到官方 Compose FlexBox，且 CSS Flexbox 语义映射已进入补全阶段；Phase 4 表格高保真 v2/v3 已开始落地。当前主线进入“fidelity-driven quality gate”：用真实 VCP 高频样例、visual hints 和 JVM model tests 约束后续开发，而不是回到 live WebView。

## 当前方向

当前路线是“切块 + 分类 + 原生编译渲染 + 动态预览兜底”：

- 普通文本和 Markdown 继续走 Compose `MarkdownBlock`。
- VCP 富 HTML 先由 `MessageTextBlocks` 切成独立块，再由 `RichHtmlClassification` 分类。
- 静态/交互型富 HTML 进入 Compose 原生管线：`RichHtmlCompiler -> RichHtmlRenderModel -> RichHtmlRenderer`。
- 真动态 HTML 不在聊天列表常驻执行，显示稳定预览，并通过“打开动态预览”进入 `WebViewPage`。
- streaming 中未闭合的富 HTML root 只显示固定占位，等闭合后再编译完整块，避免半截 HTML 反复解析和测高。

这个方向保留 UniVCP 富内容能力，但把聊天列表里的 WebView 数量压到最低；WebView 现在是动态/全屏/调试兜底，不是安全静态 HTML 的默认渲染路径。

## 渲染架构

新原生渲染器已从旧的“Composable 递归翻译器”改成两层：

```text
assistant text
  -> MessageTextBlocks
  -> RichHtmlClassification / RichHtmlSafety
  -> RichHtmlCompiler.compile(html)
  -> RichHtmlRenderModel
  -> RichHtmlRenderer
  -> Compose UI
```

关键原则：

- `RichHtmlBubbleBlock` 只保留兼容入口，负责按当前气泡宽度异步获取 `RichHtmlRenderModel` 并调用 renderer。
- `RichHtmlCompiler` 在专属 bounded dispatcher 上完成 Jsoup 解析、CSS 级联、选择器匹配、样式继承、文本拍平、block 建模，当前并发限制为 `Dispatchers.Default.limitedParallelism(2)`。
- `RichHtmlRenderer` 只消费纯 Kotlin render model，不读取 Jsoup `Element`，不解析 style 字符串，不使用 `outerHtml()` 作为渲染 key。
- 行内文本在编译阶段合并为 `AnnotatedString`，避免把 `<span>/<b>/<i>` 逐个映射成多个 `Text`。
- 编译结果由 LRU cache 缓存，历史消息回到视口时优先用 `getCached()` 复用模型，避免再次显示“正在准备富内容...”。
- Compose 原生渲染的目标是“安全静态 VCP 卡片高保真近似”，不是完整浏览器引擎；复杂 JS、运行时动画和完整 CSS layout 继续由动态预览/WebView 兜底。
- 富 HTML async compile 有超时、协程取消、in-flight 去重和成功后写缓存；取消或失败的编译不会污染模型缓存。

## 渲染路径职责边界

当前明确分三条路径：

- 普通 Markdown：`MarkdownBlock` / `MarkdownNew`，负责 Markdown、普通 HTML 兼容片段和既有 LaTeX 路径。
- VCP 富 HTML：`MessageTextBlocks -> RichHtmlClassification -> RichHtmlCompiler -> RichHtmlRenderer`，这是聊天列表富气泡的默认路径。
- WebView：只用于动态预览、全屏检查、debug renderer server，以及未来可能的静态快照兜底；不作为安全静态 HTML 的聊天列表默认渲染器。

`SimpleHtmlBlock` 和 `MarkdownNew` 仍保留兼容职责，但不再承载新的 VCP 富 HTML 能力。后续新增表格、公式、CSS 样式、按钮等富气泡能力，优先进入 `RichHtmlCompiler/RichHtmlRenderer`。

## 关键入口

主要代码入口：

- `app/src/main/java/me/rerere/rikkahub/ui/components/message/MessageTextBlocks.kt`
  - 负责把 assistant 文本按原始顺序切成 Markdown、VCP HTML、协议日志块。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
  - 负责按块选择渲染器。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlClassification.kt`
  - 负责判断 `NativeStatic`、`InteractiveStatic`、`ComplexDynamic`。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSafety.kt`
  - 负责白名单、安全 URL、节点/深度/表格/SVG 预算。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlRootDetector.kt`
  - 统一维护 rich root 识别、闭合匹配、扫描预算和 `script/style/comment` 跳过逻辑，供切块和 streaming arbiter 复用。
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/StreamRenderArbiter.kt`
  - 负责流式文本发布节流和富 HTML 闭合边界判断。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlBubbleBlock.kt`
  - Compose 兼容入口，异步请求 render model。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlCompiler.kt`
  - 原生富 HTML 编译器。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderModel.kt`
  - 原生 render IR：`RichBlock`、`ComputedStyle`、SVG model 等。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderer.kt`
  - Compose renderer，只消费 render IR。
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichSvgCompiler.kt`
  - SVG 静态子集编译器。
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleWebView.kt`
  - 保留给动态预览、全屏预览和少量 WebView 场景。

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
- Phase 3 v3 FlexBox spike：显式接入 `androidx.compose.foundation:foundation-layout`，`display:flex` 迁移到官方 Compose `FlexBox`，直接映射 direction/wrap/justify-content/align-items/align-content/gap/order/grow/shrink/basis/align-self；`flex` shorthand 已支持常见 grow/shrink/basis 解析，子项 width/max-width/basis 会按 FlexBox 主轴约束策略进入测量；grid 仍保留现有 FlowRow/多列近似。
- Phase 3 v3 FlexBox 语义补全：`row-reverse/column-reverse`、`wrap-reverse`、`flex-flow`、双值 `gap`、`row-gap/column-gap`、`justify-content:space-evenly`、`align-items/align-self:baseline` 已映射到官方 Compose FlexBox；`align-self:auto` 保持默认自动行为；`align-content:space-evenly` 因当前 Compose FlexBox API 无对应值，稳定降级到 `SpaceAround`。
- CSS painting v3：`background-clip:text` + gradient 背景不再只是安全降级，文本块会用 Compose `SpanStyle(brush=...)` 渲染渐变文字；URL 背景裁文字、复杂 text fill 仍暂不实现。
- Phase 4 v3 首片：`thead` 多行、`tfoot` 和 body section 类型会进入 `SpannedDataTable`；header/footer 行有默认背景差异，rowspan 高度不足时会把高度缺口分摊到跨越的多行。
- SVG v2.5 首片：SVG paint 扩展到 `radialGradient` shader；元素/stop 的 `opacity/fill-opacity/stroke-opacity` 会进入颜色 alpha；`g` 上的 fill/stroke/stroke-width 可被子图元继承；stroke linecap/linejoin/dasharray、text-anchor 和基础 font-weight 会进入 renderer。
- Fidelity foundation 首片：`RichHtmlRenderModel` 新增内部 visual hints，用于记录不含正文的静态保真缺口；render seed fidelity report 测试会统计标签、CSS 属性、SVG 特性、fallback、hint、编译耗时和 model block count。
- background repeat：`repeat/repeat-x/repeat-y/round/space` 不再只停在 model，URL/data 背景会在 renderer 中以安全 Canvas tile 近似绘制；`no-repeat` 仍走单图背景路径。
- CSS filter 首片：`filter/backdrop-filter` 不再只是不可见 hint；compiler 会记录安全静态子集 `blur()/brightness()/opacity()/grayscale()`，renderer 已低成本消费 `filter: opacity(...)` 到 alpha，并用颜色矩阵近似渲染 `filter: brightness()/grayscale()`；`blur()` 和 backdrop blur 仍保留结构化 model 与 visual hint，避免引入昂贵实时模糊。
- CSS color v3.1：颜色解析新增 `hsl()/hsla()`，覆盖 AI 生成卡片中常见的 hue/saturation/lightness 写法；hex/rgb/rgba/named colors 仍保持原有支持。
- Color Fidelity v4 首片：颜色解析抽出 `RichColorUtils`，新增 `RichCssColor` 语义，区分未声明、`transparent`、`currentColor` 和无法解析的颜色函数；CSS color parser 支持完整 named colors、modern `rgb(... / alpha)`、百分比 RGB、modern `hsl(... / alpha)`；renderer 新增 `RichRenderColorDefaults` 和 `RichColorResolver`，由 MaterialTheme 补缺省色，并用 AndroidX `ColorUtils.calculateContrast()` 对低对比纯色文本做最小可读性修正。
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
- Grid：固定列、`fr`、`repeat(n, ...)`、`repeat(auto-fit,minmax(...))` 的 FlowRow/多列近似；子项宽度以父容器 `grid-template-columns` 为准，支持 `grid-column: span N` 和简单 `grid-column: start / end` 的宽度倍数近似，`grid-row: span N` 会提升最小高度。
- 定位：`relative/absolute/fixed/sticky` 编译进 model；renderer 将 absolute/fixed/sticky 子节点叠到同一容器 overlay 层，按 left/top/right/bottom + transform 做安全静态近似。
- 变换：`translate`、`scale`、`rotate`、`skew` 编译进 model；renderer 对 translate/scale/rotate 做静态近似。
- Overflow：`hidden` 裁切，`scroll/auto` 降级为滚动容器。
- 阴影：多层 `box-shadow` 编译进 model；renderer 按 offset、blur、spread、color 绘制非 inset 静态近似。
- 透明度：`opacity`。
- CSS filter：`filter/backdrop-filter` 支持解析 `blur()/brightness()/opacity()/grayscale()` 到内部 model；renderer 当前将 `filter: opacity(...)` 合并到现有 alpha，并用低成本颜色矩阵近似渲染 `brightness()/grayscale()`；`blur()` 和 backdrop blur 继续作为结构化静态缺口由 visual hint 统计。
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
| Flex/Grid | Partial+ | flex 已迁到官方 Compose FlexBox，方向、反向方向、换行、反向换行、双轴 gap、order、grow/shrink/basis、baseline、align-self 等 CSS 语义基本接满；grid 仍降级到 FlowRow/多列，mobile `auto-fit/minmax` 有宽度估算，但不保证浏览器像素级布局。 |
| 定位与层叠 | Partial | absolute/fixed/sticky 走同容器 overlay 静态近似，不实现完整 stacking context。 |
| SVG | Partial | path/rect/circle/ellipse/line/polyline/polygon/text、基础 transform、linear/radial gradient、opacity、dash stroke 和 text-anchor；复杂 defs/filter/mask/clipPath/use/symbol 降级。 |
| 动态 HTML | Unsupported/Fallback | JS、canvas、iframe、video/audio、WebGL、Mermaid runtime 等进入动态预览/WebView。 |
| 浏览器交互态 | Static/Ignored | form controls、hover/active/focus、CSS 动画运行时、mask/clip-path 不在聊天列表执行；filter/backdrop-filter 的安全函数会记录，`filter: opacity(...)` 可低成本生效。 |

## 高保真策略

- 优先覆盖真实 VCP 高频 HTML/CSS：卡片、工具按钮、状态徽章、网格信息块、表格、公式、图片、基础 SVG。
- 原生路径追求静态视觉一致、滚动稳定和低崩溃率；遇到完整浏览器能力需求时不强行模拟。
- 动态/超复杂内容保留 WebView 预览或未来快照兜底，但不恢复为聊天列表里的大量 live WebView。
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
- `ph-css` 已开始用于普通 CSS declaration list 解析；遇到 CSS 变量、自定义属性或解析失败时回退项目内置 parser，保留现有 selector matcher 和 computed style。
- Android packaging 已排除 `META-INF/buildinfo.xml`，避免 ph-css 传递依赖在 debug 打包时资源冲突。
- 真机日志里当前重点看 `RichHtmlRender`：`compile start/success/failure`、`route=native/route=fallback`、`widthDp`、渲染尺寸。日志不打印正文，只打印 digest id。

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
- 转义的 `<script>` 文本，验证代码块内 HTML 不被误执行。

## 已验证命令

当前这轮已通过：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --console=plain
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest"
.\gradlew.bat --no-daemon --max-workers=1 --console=plain "-Dkotlin.compiler.execution.strategy=in-process" :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.richtext.RichHtmlFlexRendererMappingTest"
.\gradlew.bat :app:assembleDebug --console=plain
```

最近一次 Pixel 8 smoke：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.6.121:46127 install -r "C:\VCP\Eric\UniVCP\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.6.121:46127 shell monkey -p com.univcp.android.debug -c android.intent.category.LAUNCHER 1
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 192.168.6.121:46127 logcat -d -v time | Select-String -Pattern "FATAL EXCEPTION|RichHtmlRender|route=native|route=fallback|compile failure"
```

本次 smoke 结果：未看到启动 `FATAL EXCEPTION`；多条富 HTML 气泡显示 `route=native`，包含较长视觉卡片；部分样本按 `widthDp=352.0` 和横屏/宽布局 `widthDp=800.0` 分别重新编译。

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
- CSS Grid 是多列近似，不保证像素级浏览器 layout。
- 复杂 SVG 的 `use`、`symbol`、`clipPath`、`mask`、filter 等仍可能降级。
- 宽表格和复杂网格是稳定显示优先，不追求像素级浏览器还原。
- 当前 `com.helger:ph-css` 只用于声明解析硬化，现有 selector matcher 仍由项目内置实现负责。
- `background-size/position/repeat` 的完整浏览器语义、多背景层、`list-style-image` 的浏览器级 marker box、复杂 counter 伪元素和表格布局细化仍是后续优先补齐项。
- 原生渲染异常时会降级动态预览，用户可进 `WebViewPage` 查看完整动态内容。

## 下一步建议

优先级从高到低：

1. CSS Painting v3.2：支持 `clip-path: inset()/circle()/ellipse()` 的安全静态子集，先用于圆形头像、光斑裁剪和胶囊装饰；复杂 path/polygon 继续 hint。
2. Color Fidelity v4.1：补 `color-mix()` 的 sRGB 静态近似，只支持 safe 两色混合和百分比；`lab/lch/oklch` 继续 hint。
3. CSS Painting v3.3：多背景层从“第一层渲染 + 其余 hint”推进到最多两层安全静态绘制，覆盖图案纹理叠 gradient 的高频卡片。
4. CSS Painting v3.4：继续评估 `filter: blur()` 的低成本近似，只允许小半径/低频装饰层；`backdrop-filter` 仍优先保持 hint，避免聊天列表实时模糊开销。
5. CSS Painting v3.5：`mix-blend-mode`、mask、复杂 clip-path 保持 visual hint，并通过 render seed report 统计真实频率，频率不足则不进入主线实现。
6. SVG Paint v3：补 `gradientUnits/gradientTransform/spreadMethod`、安全 `clipPath` 子集和 marker 箭头，用于流程图、坐标轴和图标。
7. 质量闭环：真机视觉回归继续对比背景、色彩、滤镜、渐变文字、阴影和 SVG；debug-only report 只输出 hint/耗时/样例 id，不输出正文。
8. 继续扩大 ph-css 使用范围，但保持 selector matcher 可控，避免一次性替换成浏览器级 CSS 引擎。
