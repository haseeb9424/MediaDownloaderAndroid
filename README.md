# Media Downloader Android v0.1.6 beta

ARM64 Android build of the Media Downloader with a fully reworked phone UI.

## v0.1.6 UI rebuild

- Fixed horizontal overflow when long media or playlist links are pasted.
- Added explicit `min-width: 0`, constrained grids, word breaking, and overflow-safe status/error text throughout the app.
- Rebuilt the top app bar for narrow phones with a compact engine-status pill.
- Replaced fragile Unicode navigation/format symbols with inline SVG icons for consistent Android rendering.
- Increased all important touch targets: URL actions, format tabs, trim controls, playlist actions, item modes, progress actions, and bottom navigation.
- Improved typography and wording for small phone screens.
- Rebuilt Audio / Video selection with clear MP3 / MP4 labels.
- Improved trim controls and renamed Exact to the clearer UI label Precise while preserving the backend `exact` value.
- Playlist titles and item titles now wrap safely instead of pushing the page out of bounds.
- Playlist item Full / Trim / Skip controls have larger buttons and trim fields stack automatically on narrow phones.
- Progress status can wrap to two lines instead of forcing long text off-screen.
- Added layouts for very narrow phones, normal portrait phones, and larger/landscape screens.
- ARM64-only (`arm64-v8a`) packaging is retained.
- Downloader, playlist, trimming, FFmpeg, progress callbacks, native bridge, and GitHub Release delivery remain unchanged.

## Build

Push to `main` or run **Actions -> Build Android APK -> Run workflow**.

After a successful build, download `MediaDownloader-Android-arm64-beta.apk` from **Releases -> Media Downloader Android Beta**. The Actions artifact remains available as a fallback.

## ABI

This build intentionally contains only `arm64-v8a` native libraries and is intended for modern 64-bit Android phones.
