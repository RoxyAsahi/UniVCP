# UniVCP

[简体中文](#univcp) | [English](#univcp-english)

UniVCP 是一款基于 RikkaHub / Rikka AI 架构开发的 Android AI 聊天客户端。它的核心方向可以概括为：

```text
Everything HTML.
```

UniVCP 不把 AI 输出只当作普通 Markdown 文本来显示，而是尝试把 HTML 作为 AI 交互界面的第一等输出格式。助手可以直接输出结构化 HTML 卡片、工具面板、状态组件、按钮、表格、公式、SVG 和富气泡，App 会把这些内容直接显示在聊天时间线中。

本项目开源，并遵循 [LICENSE](LICENSE) 中描述的许可义务。对于来自 RikkaHub / Rikka AI 的派生代码，仓库口径按 **AGPL v3** 处理；如果是商业用途，则需要单独的商业授权。

> UniVCP 当前仍是实验性质的 Android 客户端与富渲染平台。公开仓库应作为整理后的开源版本发布。

## 项目定位

UniVCP 不是单纯的“又一个多模型聊天客户端”。它的定位是：

- 基于 RikkaHub / Rikka AI 架构的 Android AI 客户端。
- 面向 VCPToolBox 与 VCPChat 工作流的 VCP 生态前端。
- 让 AI 输出可以表现为 HTML UI，而不仅仅是纯文本或 Markdown。
- 一个用于探索“AI 生成 HTML 气泡如何在 Android 聊天流中高性能、高保真显示”的渲染系统。

一句话总结：

```text
RikkaHub 基础 + VCP 生态适配 + HTML-first AI 交互界面。
```

## 为什么做 UniVCP

现在的 AI 助手越来越多地返回不只是文字：它们会生成工具结果、状态面板、流程摘要、数据卡片、操作按钮和结构化视觉内容。Markdown 很有用，但它并不足以承载所有交互。

UniVCP 把 HTML 当成一种原生的助手输出格式：

- AI 可以直接生成视觉气泡。
- 工具输出可以显示为卡片、组件和面板。
- VCP 协议和工具调用可以映射到原生聊天组件。
- 需要浏览器能力的内容仍然可以退回到 WebView 路径。

它的产品目标很直接：让 AI 的回复变成聊天里的“小型界面”，而不是只能显示成一段文本。

## 核心特性

- HTML-first 助手气泡，支持卡片、面板、组件、表格、按钮、图片、公式和 SVG。
- 安全静态 HTML 默认走 Native Compose 富渲染路径。
- 复杂但静态的浏览器视觉效果可以走 WebView 离屏快照。
- 真动态内容可以进入 WebView 预览页，例如 JavaScript、canvas、运行时交互和完整浏览器行为。
- 适配 VCPToolBox / VCPChat，包括 VCP root、协议块、工具调用摘要、元思考块和 VCP widget 结构。
- 继承 RikkaHub 风格的多 AI provider 架构。
- 支持 Markdown、代码高亮、LaTeX、表格、Mermaid、文档输入、图片输入、搜索、MCP、记忆、助手自定义和网页访问。
- 聊天列表使用稳定 cell、contentType、高度缓存、近视口预热和渲染调度，避免富内容拖垮长对话滚动。

## 富渲染架构

UniVCP 最核心的改造是重写了富 HTML 聊天气泡的渲染链路。当前渲染路径大致是：

```text
Assistant text
  -> MessageTextBlocks
  -> RichHtmlClassification / RichHtmlSafety
  -> RichHtmlSnapshotPolicy
  -> RichHtmlCompiler
  -> RichHtmlRenderModel
  -> RichHtmlRenderScheduler
  -> RichHtmlRenderer
  -> Compose chat cells
```

当内容不适合原生路径时，会按能力与风险切换路线：

```text
安全静态 HTML
  -> Native Compose renderer

复杂静态浏览器视觉效果
  -> Offscreen WebView snapshot
  -> 聊天列表中显示 bitmap
  -> 点击进入完整预览

动态 / runtime HTML
  -> 稳定预览入口
  -> WebView page 完整交互
```

这样可以避免在聊天列表里常驻大量 live WebView，同时仍然为需要浏览器保真的内容保留兜底路径。

## 渲染原则

UniVCP 的渲染系统遵循几个原则：

- 保真优先，但性能由架构治理，而不是靠粗暴砍掉视觉内容。
- Native Compose 能高保真表达时优先使用原生渲染。
- WebView 是稀缺后端，不是每个列表项的默认渲染器。
- 长聊天历史应该像大型 IM/feed 一样稳定滚动，而不是像一堆重量级 WebView。
- 每一次渲染路径选择都应该可观测、可解释、可测试。

长期目标是计划驱动的多后端渲染架构：

```text
HTML / Markdown / Protocol
  -> RichContentDocument
  -> RichRenderPlan
  -> RichRenderOrchestrator
  -> TextFlow / Box / Table / SVG / Media / Action / Snapshot / WebView backends
```

相关文档：

- [Rich Render Target Architecture](docs/rich-render-target-architecture.md)
- [Native Rich Render Architecture Roadmap V1-V6](docs/rich-render-native-architecture-roadmap-v1-v6.md)

## VCP 适配

UniVCP 对 VCP 生态中的常见输出结构做了专门适配，包括：

- `<div id="vcp-root">...</div>`
- `<div id="response-root">...</div>`
- `<div id="vcp-*-widget">...</div>`
- VCP 工具调用请求与结果块。
- 元思考、推理和折叠信息块。
- VCPToolBox / VCPChat prompt 生成的富 HTML 卡片。
- `data-send`、`data-input` 和受支持的 `input(...)` 按钮行为。

目标是让 VCP agent 可以直接返回 HTML 界面，并在 Android 聊天流中原生显示。

## 支持的富内容

当前原生富渲染器主要面向安全静态 HTML 和高频 VCP 卡片模式：

- 文本、段落、列表、行内样式、代码片段和链接。
- 容器、卡片、面板、徽章、标签和常见盒模型样式。
- 静态 flex / grid 布局子集。
- 带 caption、section、对齐、colspan、rowspan 的表格。
- 通过 App 图片加载链路显示图片。
- 带 prepared draw cache 的静态 SVG 子集。
- 行内和块级 LaTeX / 公式内容。
- 显式标记为安全动作的按钮。
- `details / summary` 折叠块。

聊天列表渲染器的非目标：

- 不实现完整浏览器引擎。
- 原生路径不执行任意 JavaScript。
- 不把每个富气泡都变成 live WebView。
- 不追求所有 CSS 特性的像素级浏览器兼容。

当内容超出原生渲染器的安全或保真预算时，UniVCP 会将其路由到 snapshot 或动态预览路径。

## 模块结构

- `app`：Android App、UI、ViewModel、聊天渲染集成、设置、仓库与运行时。
- `ai`：AI provider 抽象层。
- `common`：通用工具与扩展。
- `document`：文档解析能力。
- `highlight`：代码高亮。
- `search`：搜索 provider SDK 集成。
- `speech`：语音 / TTS 相关实现。
- `web`：内嵌 Ktor Web Server。
- `web-ui`：Web 前端资源。
- `bubble-renderer`：基于 WebView 的富气泡预览与离屏快照渲染。
- `material3`：Material color utilities / Material 相关支持。
- `docs`：公开架构文档、渲染路线和第三方声明。

## 构建

需要：

- Android Studio 与较新的 Android Gradle Plugin 工具链。
- JDK 17。
- 与项目 compile SDK 匹配的 Android SDK。
- Firebase 构建需要在 `app/` 下提供 `google-services.json`。

常用命令：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat connectedDebugAndroidTest
```

Unix-like shell：

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
./gradlew connectedDebugAndroidTest
```

开发 provider endpoint、API key、签名配置、聊天同步 token、render seed 开关等本地值应放在 `local.properties`，不要提交到公开仓库。

## 开发入口

富渲染相关的主要入口：

- `app/src/main/java/me/rerere/rikkahub/ui/components/message/MessageTextBlocks.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatRenderCell.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlClassification.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSnapshotPolicy.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSafety.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlCompiler.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderModel.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderer.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderScheduler.kt`
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleSnapshotRenderer.kt`
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleWebView.kt`

调试和 telemetry 应尽量只记录 digest、route reason、耗时、visual hints 和计数，不应默认记录原始聊天正文、完整 HTML、私有 URL 或 action payload。

## 测试建议

渲染相关改动建议优先运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --console=plain

.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichHtmlFlexRendererMappingTest" --console=plain

.\gradlew.bat :app:assembleDebug --console=plain
```

真机和模拟器验证建议按项目实际环境自行配置；公开仓库不包含内部设备 runbook。

## 许可证

详见 [LICENSE](LICENSE)。

UniVCP 包含来自 RikkaHub / Rikka AI 的派生代码和其他第三方组件。派生代码按仓库的 **AGPL v3** 口径处理；商业使用需要单独的商业授权。发布、再分发或商业使用前，请阅读 [Third-Party Notices](docs/third-party-notices.md)。

---

# UniVCP English

[简体中文](#univcp) | [English](#univcp-english)

UniVCP is an Android AI chat client derived from RikkaHub / Rikka AI, focused on one core idea:

```text
Everything HTML.
```

Instead of treating model output as plain Markdown with a few rich-text extensions, UniVCP explores an HTML-first chat experience. Assistants can output structured HTML cards, tool panels, status widgets, buttons, tables, formulas, SVG, and rich bubbles, and the app will render them directly inside the conversation.

The project is open source and follows the licensing obligations described in [LICENSE](LICENSE). For code derived from RikkaHub / Rikka AI, the repository license is **AGPL v3** for qualifying open-source / non-commercial use, and commercial use requires a separate commercial license.

> UniVCP is currently an experimental Android client and rendering platform. The public repository is intended to contain the cleaned open-source edition of the project.

## Project Positioning

UniVCP is not just another multi-provider LLM chat app. Its main direction is:

- An Android client based on the RikkaHub / Rikka AI architecture.
- A VCP-oriented chat frontend designed for VCPToolBox and VCPChat workflows.
- A rich message renderer where AI output can be represented as HTML UI, not only text.
- A research-grade rendering pipeline for turning generated HTML into fast, stable Android chat bubbles.

In short:

```text
RikkaHub foundation + VCP ecosystem adaptation + HTML-first AI interface.
```

## Why UniVCP Exists

Modern assistants increasingly return more than text: tool results, panels, cards, process summaries, dashboards, action buttons, and structured visual responses. Markdown is useful, but it is not expressive enough for every interaction.

UniVCP treats HTML as a first-class assistant output format:

- AI can produce visual bubbles directly.
- Tooling output can be shown as cards, widgets, and panels.
- VCP-specific protocols can map to native chat components.
- The same content can fall back to WebView when browser fidelity is required.

The product goal is simple: make AI responses feel like small, composable interfaces inside chat.

## Core Features

- HTML-first assistant bubbles with support for cards, panels, widgets, tables, buttons, images, formulas, and SVG.
- Native Compose rich renderer for safe static HTML.
- WebView snapshot fallback for complex but static browser-only visuals.
- Dynamic WebView preview for content that needs JavaScript, canvas, runtime interaction, or full browser behavior.
- VCPToolBox / VCPChat adaptation, including VCP roots, protocol blocks, tool-call summaries, meta-thinking blocks, and VCP widget patterns.
- Multi-provider AI support inherited from the RikkaHub-style provider architecture.
- Markdown, code highlighting, LaTeX, tables, Mermaid, document input, image input, search, MCP, memory, assistant customization, and web access.
- Chat feed virtualization with stable cells, content types, height caching, viewport-aware prewarming, and render scheduling.

## Rich Rendering Architecture

The key UniVCP change is the rewritten rich rendering chain. The current chat rendering path is approximately:

```text
Assistant text
  -> MessageTextBlocks
  -> RichHtmlClassification / RichHtmlSafety
  -> RichHtmlSnapshotPolicy
  -> RichHtmlCompiler
  -> RichHtmlRenderModel
  -> RichHtmlRenderScheduler
  -> RichHtmlRenderer
  -> Compose chat cells
```

For difficult content, the route can switch:

```text
Safe static HTML
  -> Native Compose renderer

Complex static browser visuals
  -> Offscreen WebView snapshot
  -> Bitmap in chat list
  -> Full preview on tap

Dynamic/runtime HTML
  -> Stable preview
  -> WebView page for full interaction
```

This avoids keeping many live WebViews inside a scrolling chat feed while still preserving browser-level fidelity where it matters.

## Rendering Principles

UniVCP's renderer follows several principles:

- Fidelity first, with performance controlled by architecture rather than by removing visual content.
- Native Compose is preferred when it can preserve the visual result and interaction.
- WebView is a scarce backend, not the default list item renderer.
- Long conversations should scroll like a large IM/feed, not like a stack of heavyweight WebViews.
- Every route decision should be observable, explainable, and testable.

The long-term target is a plan-driven multi-backend renderer:

```text
HTML / Markdown / Protocol
  -> RichContentDocument
  -> RichRenderPlan
  -> RichRenderOrchestrator
  -> TextFlow / Box / Table / SVG / Media / Action / Snapshot / WebView backends
```

See:

- [Rich Render Target Architecture](docs/rich-render-target-architecture.md)
- [Native Rich Render Architecture Roadmap V1-V6](docs/rich-render-native-architecture-roadmap-v1-v6.md)

## VCP Adaptation

UniVCP includes special handling for VCP-oriented output patterns, including:

- `<div id="vcp-root">...</div>`
- `<div id="response-root">...</div>`
- `<div id="vcp-*-widget">...</div>`
- VCP tool-call request/result blocks.
- Meta-thinking and reasoning sections.
- Rich cards generated by VCPToolBox / VCPChat style prompts.
- Button actions such as `data-send`, `data-input`, and supported `input(...)` patterns.

The goal is to let VCP agents return rich HTML interfaces that can be displayed directly in the Android chat timeline.

## Supported Rich Content

The native rich renderer currently targets safe static HTML and high-frequency VCP card patterns:

- Text, paragraphs, lists, inline styles, code spans, and links.
- Containers, cards, panels, badges, labels, and common box styles.
- Flex and grid-oriented static layouts.
- Tables with captions, sections, alignment, colspan, and rowspan support.
- Images through the app image loading path.
- Static SVG subset with prepared draw caching.
- Inline and block LaTeX / formula-like content.
- Buttons that can send or insert text when explicitly marked as safe actions.
- Details / summary collapsible sections.

Known non-goals for the chat list renderer:

- It is not a full browser engine.
- It does not execute arbitrary JavaScript in the native path.
- It does not keep every rich bubble as a live WebView.
- It does not attempt pixel-perfect CSS compatibility for every browser feature.

When content exceeds the native renderer's safe or fidelity budget, UniVCP routes it to snapshot or dynamic preview paths.

## Module Overview

- `app`: Android app, UI, ViewModels, chat rendering integration, settings, repositories, and app runtime.
- `ai`: AI provider abstraction layer.
- `common`: Shared utilities and extensions.
- `document`: Document parsing support.
- `highlight`: Code syntax highlighting.
- `search`: Search provider SDK integrations.
- `speech`: Speech / TTS related implementation.
- `web`: Embedded Ktor web server support.
- `web-ui`: Web frontend assets.
- `bubble-renderer`: WebView-backed rich bubble preview and snapshot renderer.
- `material3`: Material color utilities / Material-related support.
- `docs`: Public architecture notes, renderer roadmap, and third-party notices.

## Build

Requirements:

- Android Studio with a recent Android Gradle Plugin toolchain.
- JDK 17.
- Android SDK configured for the compile SDK used by the project.
- `app/google-services.json` for Firebase-enabled builds.

Common commands:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat connectedDebugAndroidTest
```

On Unix-like shells:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
./gradlew connectedDebugAndroidTest
```

Local-only values such as dev provider endpoints, API keys, signing config, chat sync tokens, and render seed toggles should be kept in `local.properties` and must not be committed.

## Development Notes

Useful renderer entry points:

- `app/src/main/java/me/rerere/rikkahub/ui/components/message/MessageTextBlocks.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatRenderCell.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlClassification.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSnapshotPolicy.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/RichHtmlSafety.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlCompiler.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderModel.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderer.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderScheduler.kt`
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleSnapshotRenderer.kt`
- `bubble-renderer/src/main/java/com/univcp/bubble/BubbleWebView.kt`

The renderer is developed with privacy-aware telemetry in mind. Debug reports should use digests, route reasons, timing, visual hints, and counters rather than raw chat text, raw HTML bodies, private URLs, or action payloads.

## Testing Focus

Recommended checks for renderer work:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --console=plain

.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.RichHtmlFidelityReportTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlQualityGateTest" --tests "me.rerere.rikkahub.ui.components.richtext.RichHtmlFlexRendererMappingTest" --console=plain

.\gradlew.bat :app:assembleDebug --console=plain
```

For device-level validation, configure Android devices for your own environment; the public repository does not include internal device runbooks.

## License

See [LICENSE](LICENSE).

UniVCP contains code derived from RikkaHub / Rikka AI and other third-party components. The derived upstream code is treated under the repository's **AGPL v3** terms for qualifying use; commercial use requires a separate commercial license. Please review [Third-Party Notices](docs/third-party-notices.md) before redistribution, public release, or commercial use.
