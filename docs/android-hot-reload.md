# Android Hot Reload

UniVCP uses these live loops during research:

- Native Compose UI: use Android Studio Live Edit and Apply Changes.
- Compose native rich HTML renderer: edit Kotlin under `app/src/main/java/me/rerere/rikkahub/ui/components/richtext`.
- Legacy/dynamic WebView renderer: use the local renderer server below only for `BubbleWebView` preview/debug paths.

## Compose Native Renderer Loop

The main chat-list rich HTML path is now Compose-native:

```text
RichHtmlBubbleBlock
  -> RichHtmlCompiler
  -> RichHtmlRenderModel
  -> RichHtmlRenderer
```

For renderer changes, prefer Android Studio Live Edit / Apply Changes or reinstall the debug build. The most relevant files are:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlBubbleBlock.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlCompiler.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderModel.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichHtmlRenderer.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/RichSvgCompiler.kt
```

Quick regression command:

```powershell
cd C:\VCP\Eric\UniVCP
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.ui.components.message.MessageTextBlocksTest" --tests "me.rerere.rikkahub.ui.components.message.StreamRenderArbiterTest" --tests "me.rerere.rikkahub.data.renderseed.RenderSeedFixtureTest" --tests "me.rerere.rikkahub.ui.components.message.RichHtmlHardeningTest" --console=plain
```

For visual fidelity changes, also reinstall a debug build and watch `RichHtmlRender` logcat lines for `route=native`, `route=fallback`, compile time, and `widthDp`. The Compose path now compiles with the actual bubble width, so width changes can legitimately produce a separate cached model.

## WebView Renderer Loop

Start an emulator first, then run:

```powershell
cd C:\VCP\Eric\UniVCP
.\scripts\dev-renderer-server.ps1 -Install -Launch
```

The script serves:

```text
http://127.0.0.1:5179/renderer-shell.html
```

and runs:

```powershell
adb reverse tcp:5179 tcp:5179
```

Debug builds load that URL inside `BubbleWebView`. If the server is not running, the WebView falls back to the bundled APK assets. This path is for dynamic/fullscreen preview and WebView-specific debugging, not the default safe static HTML chat-list renderer. Edit files under:

```text
C:\VCP\Eric\UniVCP\bubble-renderer\src\main\assets\renderer
```

The renderer shell polls `__version.txt`; when the script sees a file change, the Android WebView reloads and re-renders the current bubble.

On the rikkaHub baseline branch, debug builds install as:

```text
com.univcp.android.debug
```

Stop the background renderer server with:

```powershell
.\scripts\dev-renderer-server.ps1 -Stop
```

## Full rikkaHub Baseline Direction

The research direction is rikkaHub-first: keep rikkaHub's app shell and provider/data capabilities as the baseline, then render safe rich assistant messages through UniVCP's Compose-native HTML renderer. `BubbleWebView` remains available for dynamic preview, fullscreen inspection, and WebView fallback research. Because rikkaHub code is copied directly, keep the repository private until the copied code is replaced clean-room or separately licensed.
