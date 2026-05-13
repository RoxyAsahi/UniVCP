# UniVCP

UniVCP is an Android research prototype for the UniStudy mobile direction.

The project uses a native Kotlin / Jetpack Compose app shell with a sandboxed WebView-based learning bubble renderer. The first milestone focuses on preserving UniStudy's rich AI learning bubble capability: Markdown, HTML, SVG, scoped CSS, Mermaid, KaTeX, code previews, and Three.js experiments using offline bundled assets.

## Status

This repository is a private research prototype.

## Architecture

- `:app` - Compose app shell, Room/DataStore/Koin bootstrap, chat prototype.
- `:bubble-renderer` - WebView learning bubble renderer and offline renderer assets.
- `:ai` - RikkaHub-derived provider abstraction and streaming client base.
- `:common` - RikkaHub-derived HTTP/JSON/common utilities.

## License Notice

This prototype directly reuses selected modules from `C:\VCP\Eric\rikkahub`, which is distributed under a segmented dual license including AGPLv3 terms. Keep this repository private unless the reused code is replaced through a clean-room rewrite or an appropriate commercial license is obtained.

See `docs/third-party-notices.md`.

## Build

```powershell
.\gradlew :app:assembleDebug
```
