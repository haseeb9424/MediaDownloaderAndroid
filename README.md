# Media Downloader Android v0.1.5 beta

Phone-first Android WebView build of the Media Downloader.

## v0.1.5 changes

- Phone-first portrait UI rebuilt for Android screens.
- Larger touch targets and input controls.
- Audio / Video format segmented controls.
- Cleaner single-download trim workflow.
- Playlist items now stack vertically so titles and Full / Trim / Skip controls remain readable on narrow phones.
- Playlist trim fields expand only when needed.
- Sticky playlist Download button.
- Persistent download activity/progress above bottom navigation.
- ARM64-only packaging (`arm64-v8a`) retained for smaller APK size and compatibility with the target Tecno Spark 30 Pro and Redmi Note 12 4G.
- GitHub Actions still uploads an artifact and now also creates/updates a direct `android-beta` GitHub Release asset named `MediaDownloader-Android-arm64-beta.apk`.

## Build

The included GitHub Actions workflow builds with Java 17, Android API 35, and Gradle 8.13.

Push to `main` or run **Actions -> Build Android APK -> Run workflow**.

After a successful build, either:

1. Open **Releases -> Media Downloader Android Beta** and download `MediaDownloader-Android-arm64-beta.apk` directly, or
2. Use the Actions artifact as a fallback.

## ABI

This package intentionally contains only `arm64-v8a` native libraries. It will not install on 32-bit-only Android devices.
