# Android Hot Reload

UniVCP uses two different live loops during research:

- Native Compose UI: use Android Studio Live Edit and Apply Changes.
- Learning bubble renderer: use the local WebView renderer server below.

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

Debug builds load that URL inside `BubbleWebView`. If the server is not running, the WebView falls back to the bundled APK assets. Edit files under:

```text
C:\VCP\Eric\UniVCP\bubble-renderer\src\main\assets\renderer
```

The renderer shell polls `__version.txt`; when the script sees a file change, the Android WebView reloads and re-renders the current bubble.

Stop the background renderer server with:

```powershell
.\scripts\dev-renderer-server.ps1 -Stop
```

## Full rikkaHub Baseline Direction

The research direction is now rikkaHub-first: keep rikkaHub's app shell and provider/data capabilities as the baseline, then replace rich assistant message rendering with UniVCP's learning bubble WebView renderer. Because rikkaHub code is copied directly, keep the repository private until the copied code is replaced clean-room or separately licensed.
