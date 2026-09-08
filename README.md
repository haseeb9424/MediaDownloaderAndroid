# Media Downloader Android v0.1.7 beta

ARM64 Android build focused on faster startup and faster download/post-processing while retaining the phone-first v0.1.6 UI.

## v0.1.7 speed improvements

- yt-dlp readiness no longer waits for FFmpeg initialization or an online updater check.
- FFmpeg initializes in the background; a download only waits for it if media processing is actually needed before it is ready.
- yt-dlp update checks are silent, non-blocking, and limited to once every 24 hours after a successful update.
- Single downloads use up to 8 concurrent fragments; playlist items remain at 4 fragments each while two playlist files can download in parallel.
- MP3 prefers an audio-only M4A source so video is not downloaded unnecessarily.
- MP4 prefers native MP4 video + M4A audio so normal MP4 post-processing can usually be a fast stream merge instead of video re-encoding.
- Fast trim remains the default; Precise trim remains available when frame-accurate cuts are required.
- Progress text now identifies download, merge, MP3 conversion, trim, finalization, and saving stages more clearly.
- Playlist output now defaults to **Save as folder**, avoiding the expensive mandatory ZIP pass.
- Optional ZIP output is retained and uses no-compression ZIP packaging because MP3/MP4 files are already compressed.
- Android Downloads copy buffers were increased to reduce file-save overhead.
- Playlist folder output is saved under `Downloads/Media Downloader/<Playlist Name>`.
- ARM64-only (`arm64-v8a`) packaging remains enabled.

## Build

Push to `main` or run **Actions -> Build Android APK -> Run workflow**.

After a successful build, download `MediaDownloader-Android-arm64-beta.apk` from **Releases -> Media Downloader Android Beta**. The Actions artifact remains available as a fallback.

## ABI

This build intentionally contains only `arm64-v8a` native libraries and is intended for modern 64-bit Android phones.
