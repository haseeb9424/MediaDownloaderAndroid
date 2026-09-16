# Media Downloader Android v0.1.8 beta

Phone-first ARM64 Android downloader with a redesigned mobile UI, a pinned playlist workflow, and faster download/post-processing paths.

## v0.1.8 highlights

- Full mobile UI refresh with larger touch targets, cleaner cards, safer long-link handling, and clearer progress.
- Native **Paste** buttons read the Android clipboard directly.
- Pinned playlist preloaded in the app:
  `https://youtube.com/playlist?list=PLUtowSKL77PM&si=-S-AgpEWXfbULz_7`
- The pinned playlist is **automatically preloaded in the background** on first use. Its list is cached after the first successful load, so it reopens instantly on later launches. A refresh button fetches the latest playlist contents.
- Playlist search, Keep all / Skip all, per-item Full / Trim / Skip controls, and sticky Download Selected action.
- Added **M4A Fast Audio** mode. It keeps AAC/M4A directly when available, avoiding MP3 transcoding and reducing processing time.
- 360p MP4 now prefers a combined MP4 stream when YouTube provides one, avoiding a separate audio download and merge.
- Added the official optional **aria2c** component from youtubedl-android for accelerated full-file transfers. Single downloads use up to 8 aria2 connections; parallel playlist items use up to 4 each. If aria2c/CDN compatibility fails, the app retries once with yt-dlp’s native downloader.
- Native yt-dlp fragmented downloads remain tuned to up to 12 concurrent fragments for single files and 6 per playlist item when aria2c is not used.
- Normal playlist downloads can use up to 3 parallel item workers. Precise trims automatically fall back to one worker to avoid CPU contention.
- Playlist **folder mode saves each file immediately as it finishes**, overlapping Android storage copy with the remaining downloads instead of doing one long saving stage at the end.
- Larger Android storage copy buffers reduce final save overhead.
- Folder mode remains the default. ZIP mode remains optional.
- Fast trim remains the default; Precise trim remains available but can be much slower because exact cuts may require re-encoding.
- yt-dlp startup remains non-blocking and updates remain silent/background maintenance.
- ARM64-only (`arm64-v8a`) packaging is retained.

## Performance tips

For the fastest audio downloads, choose **M4A**. Choose MP3 only when you specifically need MP3 compatibility because MP3 requires an audio conversion step.

For the fastest video path, 360p can often use a ready-made combined MP4 stream. Higher resolutions normally require separate video/audio streams followed by a fast FFmpeg merge. Full-file transfers use aria2c acceleration when it is available; trims stay on the yt-dlp/FFmpeg path for compatibility.

Use **Fast** trim unless you need frame-accurate cut points. yt-dlp documents that forcing exact keyframes requires re-encoding and is much slower.

## Build

Push to `main` or run **Actions -> Build Android APK -> Run workflow**.

After a successful build, download `MediaDownloader-Android-arm64-beta.apk` from **Releases -> Media Downloader Android Beta**. The Actions artifact remains available as a fallback.

## ABI

This build intentionally contains only `arm64-v8a` native libraries and is intended for modern 64-bit Android phones.

## GitHub Actions build fix

The workflow now uses `android-actions/setup-android@v4` with `packages: ''` so the action does not request the retired Android SDK `tools` package. The workflow then installs only the packages this app actually needs: `platform-tools`, Android 35, and Build Tools 35.0.0.
