# Media Downloader Android Beta

This is the Android WebView build of the Media Downloader project.

## Free cloud APK build (no Android Studio required)

1. Create a new empty GitHub repository.
2. Upload all files from this project, preserving the folders.
3. Open the repository's **Actions** tab.
4. Open **Build Android APK** and choose **Run workflow** (a push to `main` also builds automatically).
5. Open the completed workflow run and download the artifact named **MediaDownloader-Android-beta**.
6. Extract the artifact ZIP and install `MediaDownloader-Android-beta.apk` on an arm64 Android phone.

## Current beta features

- Local WebView UI (HTML/CSS/JavaScript bundled inside the APK)
- Single MP3 and MP4 downloads
- MP3 quality presets and MP4 resolution limits
- Optional Fast / Exact trim using yt-dlp download sections + FFmpeg
- Playlist inspection
- Full / Trim / Skip per playlist item
- Two-at-a-time playlist processing with aggregate progress
- Playlist ZIP packaging
- Public save location: `Downloads/Media Downloader`
- Android foreground-service download notification
- Share a URL to **Media Downloader** from other Android apps
- Cancel active yt-dlp processes
- yt-dlp nightly update attempt at app startup

## Notes

- This first build targets `arm64-v8a` only.
- The first launch may take longer while the downloader engine initializes/updates.
- Some sites may require cookies or additional anti-bot workarounds. This beta focuses on the same ordinary URL workflow as the Windows app.


## v0.1.4 build fix
Automatic yt-dlp self-update is disabled for this beta build to avoid Java API incompatibility with `YoutubeDL.UpdateChannel.NIGHTLY` in youtubedl-android 0.18.1. The bundled yt-dlp engine is initialized and used directly.


## v0.1.4 build fix

GitHub Actions previously failed at `:app:checkDebugDuplicateClasses` because `youtubedl-android:0.18.1` pulls `kotlin-stdlib-jdk7/jdk8:1.7.22` while newer AndroidX dependencies resolve `kotlin-stdlib:1.8.22`. Kotlin 1.8 merged the JDK 7/8 stdlib content into the main stdlib, so mixing the old 1.7.22 split artifacts with 1.8.22 creates duplicate classes. This build adds the Kotlin 1.8.22 BOM and an explicit 1.8.22 stdlib dependency to align the entire Kotlin stdlib family.


## v0.1.4 runtime reliability changes
- Restores yt-dlp self-update using the correct Java constant `YoutubeDL.UpdateChannel._NIGHTLY`.
- Removes `formats=missing_pot`, which can expose YouTube formats that fail with HTTP 403.
- Removes forced IPv4 so Android can work correctly on IPv6/NAT64 mobile networks.
- Requires FFmpeg initialization instead of silently ignoring an FFmpeg startup failure.
- Remuxes video output to MP4 so fallback formats still produce the expected `.mp4` file.
- Surfaces the recent yt-dlp output in the app when a runtime download fails.
