package com.haseeb.mediadownloader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class MainActivity extends AppCompatActivity {
    private static final String APP_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String DOWNLOAD_FOLDER = "Media Downloader";
    private static final int STORAGE_PERMISSION_REQUEST = 902;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 903;

    private WebView webView;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private static final long YTDLP_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final String PREFS_NAME = "media_downloader_prefs";
    private static final String PREF_LAST_YTDLP_UPDATE = "last_ytdlp_update";

    private final Set<String> activeProcessIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicBoolean engineReady = new AtomicBoolean(false);
    private final AtomicBoolean ffmpegReady = new AtomicBoolean(false);
    private final AtomicBoolean jobRunning = new AtomicBoolean(false);
    private final CountDownLatch ffmpegReadyLatch = new CountDownLatch(1);
    private volatile String ffmpegError = "";
    private volatile String pendingSharedUrl;
    private volatile String engineStatus = "Starting…";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
        }

        handleShareIntent(getIntent());

        webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(false);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                sendEngineState();
                if (pendingSharedUrl != null) {
                    JSONObject obj = new JSONObject();
                    try { obj.put("url", pendingSharedUrl); } catch (Exception ignored) { }
                    sendEvent("onSharedUrl", obj);
                    pendingSharedUrl = null;
                }
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidApp");
        webView.loadUrl(APP_URL);
        initializeDownloaderEngine();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
        if (pendingSharedUrl != null && webView != null) {
            JSONObject obj = new JSONObject();
            try { obj.put("url", pendingSharedUrl); } catch (Exception ignored) { }
            sendEvent("onSharedUrl", obj);
            pendingSharedUrl = null;
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                int idx = text.indexOf("http");
                pendingSharedUrl = idx >= 0 ? text.substring(idx).trim() : text.trim();
            }
        }
    }

    private void initializeDownloaderEngine() {
        ioExecutor.execute(() -> {
            try {
                // Only yt-dlp blocks readiness. The UI becomes usable as soon as the
                // downloader runtime is initialized; FFmpeg and update maintenance run
                // independently in the background.
                YoutubeDL.getInstance().init(getApplicationContext());
                engineReady.set(true);
                engineStatus = "Ready";
                sendEngineState();

                ioExecutor.execute(this::initializeFfmpegInBackground);
                ioExecutor.execute(this::maybeUpdateYoutubeDLInBackground);
            } catch (Exception e) {
                engineReady.set(false);
                engineStatus = "Engine error: " + safeMessage(e);
                sendEngineState();
            }
        });
    }

    private void initializeFfmpegInBackground() {
        try {
            FFmpeg.getInstance().init(getApplicationContext());
            ffmpegReady.set(true);
        } catch (Exception e) {
            ffmpegError = safeMessage(e);
        } finally {
            ffmpegReadyLatch.countDown();
        }
    }

    private void maybeUpdateYoutubeDLInBackground() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastUpdate = prefs.getLong(PREF_LAST_YTDLP_UPDATE, 0L);
            long now = System.currentTimeMillis();
            if (now - lastUpdate < YTDLP_UPDATE_INTERVAL_MS) return;

            // Give the user a chance to start a download first. Maintenance must never
            // make app launch feel blocked. If a download is active, simply retry next launch.
            Thread.sleep(5000L);
            if (jobRunning.get()) return;

            YoutubeDL.getInstance().updateYoutubeDL(
                    getApplicationContext(), YoutubeDL.UpdateChannel._NIGHTLY);
            prefs.edit().putLong(PREF_LAST_YTDLP_UPDATE, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
            // The bundled/current engine remains usable offline or when update servers
            // are unavailable. We deliberately keep this silent in the normal UI.
        }
    }

    private void awaitFfmpegReady(String scope, int total) throws Exception {
        if (ffmpegReady.get()) return;
        sendProgress(scope, 1, "Preparing media tools…", 0, Math.max(1, total));
        boolean finished = ffmpegReadyLatch.await(20, TimeUnit.SECONDS);
        if (!finished) throw new IllegalStateException("Media tools are taking too long to start. Please try again.");
        if (!ffmpegReady.get()) {
            throw new IllegalStateException("FFmpeg could not start" + (ffmpegError.isEmpty() ? "." : ": " + ffmpegError));
        }
    }

    private void sendEngineState() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ready", engineReady.get());
            obj.put("status", engineStatus);
        } catch (Exception ignored) { }
        sendEvent("onEngineState", obj);
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void inspectPlaylist(String url) {
            if (!engineReady.get()) {
                sendError("playlist", "Downloader engine is still starting.");
                return;
            }
            if (url == null || url.trim().isEmpty()) {
                sendError("playlist", "Paste a playlist URL first.");
                return;
            }
            ioExecutor.execute(() -> inspectPlaylistInternal(url.trim()));
        }

        @JavascriptInterface
        public void startSingle(String payloadJson) {
            if (!engineReady.get()) {
                sendError("download", "Downloader engine is still starting.");
                return;
            }
            if (!jobRunning.compareAndSet(false, true)) {
                sendError("download", "Another download is already running.");
                return;
            }
            maybeRequestNotificationPermission();
            ioExecutor.execute(() -> runSingleJob(payloadJson));
        }

        @JavascriptInterface
        public void startPlaylist(String payloadJson) {
            if (!engineReady.get()) {
                sendError("playlist", "Downloader engine is still starting.");
                return;
            }
            if (!jobRunning.compareAndSet(false, true)) {
                sendError("playlist", "Another download is already running.");
                return;
            }
            maybeRequestNotificationPermission();
            ioExecutor.execute(() -> runPlaylistJob(payloadJson));
        }

        @JavascriptInterface
        public void cancelCurrent() {
            for (String processId : new ArrayList<>(activeProcessIds)) {
                try { YoutubeDL.getInstance().destroyProcessById(processId); } catch (Exception ignored) { }
            }
            activeProcessIds.clear();
            jobRunning.set(false);
            DownloadForegroundService.stop(MainActivity.this);
            JSONObject obj = new JSONObject();
            try { obj.put("status", "Cancelled"); } catch (Exception ignored) { }
            sendEvent("onJobCancelled", obj);
        }

        @JavascriptInterface
        public void openDownloads() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setType("resource/folder");
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.setType("*/*");
                        startActivity(intent);
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    private void inspectPlaylistInternal(String url) {
        String processId = "inspect-" + UUID.randomUUID();
        activeProcessIds.add(processId);
        try {
            YoutubeDLRequest req = new YoutubeDLRequest(url);
            req.addOption("--flat-playlist");
            req.addOption("--dump-single-json");
            req.addOption("--skip-download");
            req.addOption("--no-warnings");
            req.addOption("--ignore-errors");
            String output = YoutubeDL.getInstance().execute(req, processId, null).getOut();
            JSONObject raw = parseLastJsonObject(output);

            JSONObject response = new JSONObject();
            response.put("title", raw.optString("title", "Playlist"));
            JSONArray resultItems = new JSONArray();
            JSONArray entries = raw.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e == null) continue;
                    String id = e.optString("id", "");
                    String itemUrl = e.optString("url", "");
                    if (!itemUrl.startsWith("http") && !id.isEmpty()) {
                        itemUrl = "https://www.youtube.com/watch?v=" + id;
                    }
                    if (itemUrl.isEmpty()) continue;
                    JSONObject item = new JSONObject();
                    item.put("position", i + 1);
                    item.put("id", id);
                    item.put("title", e.optString("title", "Playlist item " + (i + 1)));
                    item.put("url", itemUrl);
                    item.put("duration", e.optDouble("duration", 0));
                    resultItems.put(item);
                }
            }
            response.put("items", resultItems);
            sendEvent("onPlaylistLoaded", response);
        } catch (Exception e) {
            sendError("playlist", safeMessage(e));
        } finally {
            activeProcessIds.remove(processId);
        }
    }

    private void runSingleJob(String payloadJson) {
        File jobDir = null;
        try {
            JSONObject payload = new JSONObject(payloadJson);
            String url = payload.optString("url", "").trim();
            if (url.isEmpty()) throw new IllegalArgumentException("Missing media URL.");

            jobDir = createJobDir("single");
            DownloadForegroundService.start(this, "Media download");
            sendProgress("download", 0, "Preparing download…", 0, 1);
            awaitFfmpegReady("download", 1);

            File media = downloadItem(payload, url, jobDir, 1, 1, "download", null);
            if (media == null || !media.isFile()) throw new IllegalStateException("No finished media file was produced.");

            sendProgress("download", 98, "Saving to Downloads…", 1, 1);
            Uri saved = publishToDownloads(media, mimeFor(media));

            JSONObject done = new JSONObject();
            done.put("scope", "download");
            done.put("filename", media.getName());
            done.put("uri", saved == null ? "" : saved.toString());
            done.put("message", "Saved to Downloads/" + DOWNLOAD_FOLDER);
            sendEvent("onJobDone", done);
            DownloadForegroundService.update(this, "Media download", 100, "Saved to Downloads");
        } catch (Exception e) {
            sendError("download", safeMessage(e));
        } finally {
            jobRunning.set(false);
            activeProcessIds.clear();
            DownloadForegroundService.stop(this);
            if (jobDir != null) deleteRecursive(jobDir);
            ioExecutor.execute(this::maybeUpdateYoutubeDLInBackground);
        }
    }

    private void runPlaylistJob(String payloadJson) {
        File jobDir = null;
        ExecutorService playlistPool = null;
        try {
            JSONObject payload = new JSONObject(payloadJson);
            JSONArray items = payload.optJSONArray("items");
            if (items == null) throw new IllegalArgumentException("No playlist items supplied.");

            List<JSONObject> selected = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null && !"skip".equals(item.optString("mode", "full"))) selected.add(item);
            }
            if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one playlist item.");

            String playlistTitle = payload.optString("title", "Playlist");
            String packageMode = payload.optString("package_mode", "folder");
            jobDir = createJobDir("playlist");
            File finalJobDir = jobDir;
            int total = selected.size();
            ConcurrentHashMap<Integer, Float> progresses = new ConcurrentHashMap<>();
            AtomicInteger completed = new AtomicInteger(0);
            List<File> successes = Collections.synchronizedList(new ArrayList<>());
            List<String> failures = Collections.synchronizedList(new ArrayList<>());

            DownloadForegroundService.start(this, "Playlist download");
            sendProgress("playlist", 0, "Starting " + total + " selected items…", 0, total);
            awaitFfmpegReady("playlist", total);

            int workers = Math.max(1, Math.min(2, total));
            playlistPool = Executors.newFixedThreadPool(workers);
            ExecutorCompletionService<Void> ecs = new ExecutorCompletionService<>(playlistPool);

            for (int idx = 0; idx < selected.size(); idx++) {
                final int itemIndex = idx;
                final JSONObject item = selected.get(idx);
                ecs.submit(() -> {
                    File itemDir = new File(finalJobDir, String.format(Locale.US, "item_%03d", itemIndex + 1));
                    itemDir.mkdirs();
                    try {
                        String itemUrl = item.optString("url", "");
                        File result = downloadItem(payload, itemUrl, itemDir, itemIndex + 1, total, "playlist", (p, phase) -> {
                            progresses.put(itemIndex, p);
                            float sum = 0;
                            for (int x = 0; x < total; x++) sum += progresses.getOrDefault(x, 0f);
                            int overall = Math.min(95, Math.round((sum / total) * 0.95f));
                            int done = completed.get();
                            sendProgress("playlist", overall, phase, done, total);
                        });
                        if (result != null) successes.add(result);
                    } catch (Exception e) {
                        failures.add(item.optString("title", "Item " + (itemIndex + 1)) + ": " + safeMessage(e));
                    } finally {
                        progresses.put(itemIndex, 100f);
                        int done = completed.incrementAndGet();
                        float sum = 0;
                        for (int x = 0; x < total; x++) sum += progresses.getOrDefault(x, 0f);
                        int overall = Math.min(95, Math.round((sum / total) * 0.95f));
                        sendProgress("playlist", overall,
                                done + " of " + total + " items finished", done, total);
                    }
                    return null;
                });
            }

            for (int i = 0; i < total; i++) {
                Future<Void> f = ecs.take();
                try { f.get(); } catch (Exception ignored) { }
            }

            if (successes.isEmpty()) {
                throw new IllegalStateException(failures.isEmpty() ? "No playlist items downloaded successfully." : failures.get(0));
            }

            successes.sort(Comparator.comparing(File::getName));
            JSONObject done = new JSONObject();
            done.put("scope", "playlist");
            done.put("completed", successes.size());
            done.put("total", total);
            done.put("failed", failures.size());

            if ("zip".equalsIgnoreCase(packageMode)) {
                sendProgress("playlist", 97, "Creating playlist ZIP…", completed.get(), total);
                File zip = new File(jobDir, sanitizeFilename(playlistTitle) + ".zip");
                createZip(zip, successes);
                sendProgress("playlist", 99, "Saving ZIP to Downloads…", completed.get(), total);
                Uri saved = publishToDownloads(zip, "application/zip");
                done.put("filename", zip.getName());
                done.put("uri", saved == null ? "" : saved.toString());
                done.put("message", failures.isEmpty()
                        ? "Playlist ZIP saved to Downloads/" + DOWNLOAD_FOLDER
                        : "ZIP saved with " + successes.size() + " files; " + failures.size() + " failed.");
            } else {
                String subfolder = sanitizeFilename(playlistTitle);
                Uri lastSaved = null;
                for (int i = 0; i < successes.size(); i++) {
                    File media = successes.get(i);
                    int savePct = 96 + Math.min(3, Math.round(((i + 1f) / successes.size()) * 3f));
                    sendProgress("playlist", savePct,
                            "Saving file " + (i + 1) + " of " + successes.size() + "…",
                            completed.get(), total);
                    lastSaved = publishToDownloads(media, mimeFor(media), subfolder);
                }
                done.put("filename", subfolder);
                done.put("uri", lastSaved == null ? "" : lastSaved.toString());
                done.put("message", failures.isEmpty()
                        ? "Saved " + successes.size() + " files to Downloads/" + DOWNLOAD_FOLDER + "/" + subfolder
                        : "Saved " + successes.size() + " files; " + failures.size() + " failed.");
            }
            sendEvent("onJobDone", done);
        } catch (Exception e) {
            sendError("playlist", safeMessage(e));
        } finally {
            if (playlistPool != null) playlistPool.shutdownNow();
            jobRunning.set(false);
            activeProcessIds.clear();
            DownloadForegroundService.stop(this);
            if (jobDir != null) deleteRecursive(jobDir);
            ioExecutor.execute(this::maybeUpdateYoutubeDLInBackground);
        }
    }

    private interface ProgressRelay { void update(float value, String status); }

    private File downloadItem(JSONObject commonPayload, String url, File outputDir,
                              int itemIndex, int total, String scope, ProgressRelay relay) throws Exception {
        if (url == null || url.trim().isEmpty()) throw new IllegalArgumentException("Invalid media URL.");
        String processId = scope + "-" + itemIndex + "-" + UUID.randomUUID();
        activeProcessIds.add(processId);
        try {
            YoutubeDLRequest request = new YoutubeDLRequest(url.trim());
            request.addOption("--no-mtime");
            request.addOption("--newline");
            request.addOption("--retries", "10");
            request.addOption("--fragment-retries", "10");
            request.addOption("--extractor-retries", "3");
            request.addOption("--socket-timeout", "30");
            request.addOption("--concurrent-fragments", "download".equals(scope) ? "8" : "4");
            request.addOption("--trim-filenames", "180");
            if ("download".equals(scope)) request.addOption("--no-playlist");
            request.addOption("-o", new File(outputDir, "%(title).140B [%(id)s].%(ext)s").getAbsolutePath());

            if (isYouTube(url)) {
                request.addOption("--extractor-args", "youtube:player_client=default,-android_sdkless");
            }

            String format = commonPayload.optString("format", "mp3");
            String quality = commonPayload.optString("quality", "192");
            if ("mp3".equalsIgnoreCase(format)) {
                // Prefer an audio-only M4A source when available so the phone never
                // downloads an unnecessary video stream before MP3 conversion.
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best");
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
                request.addOption("--audio-quality", quality + "K");
            } else {
                String h = quality.matches("360|480|720|1080") ? quality : "";
                // Prefer native MP4 video + M4A audio at the requested quality. These
                // streams can normally be merged by FFmpeg with stream copy, avoiding
                // expensive video re-encoding while preserving the user's quality choice.
                String selector = h.isEmpty()
                        ? "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
                        : "bv*[ext=mp4][height<=" + h + "]+ba[ext=m4a]/" +
                          "b[ext=mp4][height<=" + h + "]/" +
                          "bv*[height<=" + h + "]+ba/b[height<=" + h + "]";
                request.addOption("-f", selector);
                request.addOption("--merge-output-format", "mp4");
                request.addOption("--remux-video", "mp4");
            }

            String mode = commonPayload.optString("mode", "full");
            String start = commonPayload.optString("start", "").trim();
            String end = commonPayload.optString("end", "").trim();

            // Playlist items carry their own trim mode/range.
            JSONArray itemArray = commonPayload.optJSONArray("items");
            if (itemArray != null) {
                for (int i = 0; i < itemArray.length(); i++) {
                    JSONObject p = itemArray.optJSONObject(i);
                    if (p != null && url.equals(p.optString("url", ""))) {
                        mode = p.optString("mode", "full");
                        start = p.optString("start", "").trim();
                        end = p.optString("end", "").trim();
                        break;
                    }
                }
            }

            if ("trim".equals(mode) && (!start.isEmpty() || !end.isEmpty())) {
                String trimMode = commonPayload.optString("trim_mode", "fast");
                String startPart = start.isEmpty() ? "0" : start;
                String endPart = end.isEmpty() ? "inf" : end;
                request.addOption("--download-sections", "*" + startPart + "-" + endPart);
                if ("exact".equals(trimMode)) request.addOption("--force-keyframes-at-cuts");
            }

            final String finalMode = mode;
            final String finalFormat = format;
            final long[] lastUi = {0L};
            final StringBuilder recentOutput = new StringBuilder();
            Function3<Float, Long, String, Unit> callback = (progress, eta, line) -> {
                float p = progress == null ? 0f : progress;
                if (line != null && !line.trim().isEmpty()) {
                    synchronized (recentOutput) {
                        recentOutput.append(line.trim()).append('\n');
                        if (recentOutput.length() > 5000) recentOutput.delete(0, recentOutput.length() - 3500);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastUi[0] > 250) {
                    lastUi[0] = now;
                    int percentage = Math.max(0, Math.min(95, Math.round(p * 0.95f)));
                    String phase = statusForLine(line, finalFormat, finalMode);
                    String status = total > 1
                            ? "Item " + itemIndex + "/" + total + " • " + phase
                            : phase;
                    if (relay != null) relay.update(p, status);
                    if ("download".equals(scope)) sendProgress(scope, percentage, status, 0, 1);
                    DownloadForegroundService.update(this,
                            total > 1 ? "Playlist download" : "Media download",
                            percentage,
                            status);
                }
                return Unit.INSTANCE;
            };

            try {
                YoutubeDL.getInstance().execute(request, processId, callback);
            } catch (Exception executeError) {
                String detail;
                synchronized (recentOutput) { detail = recentOutput.toString().trim(); }
                if (detail.length() > 1200) detail = detail.substring(detail.length() - 1200);
                String base = safeMessage(executeError);
                if (!detail.isEmpty()) base = base + "\n\nLast yt-dlp output:\n" + detail;
                throw new IllegalStateException(base, executeError);
            }
            File result = findNewestMediaFile(outputDir, format);
            if (result == null) throw new IllegalStateException("Download completed but the output file was not found.");
            return result;
        } finally {
            activeProcessIds.remove(processId);
        }
    }

    private JSONObject parseLastJsonObject(String text) throws Exception {
        if (text == null) throw new IllegalStateException("No playlist information was returned.");
        String trimmed = text.trim();
        try { return new JSONObject(trimmed); } catch (Exception ignored) { }
        int pos = trimmed.lastIndexOf('\n');
        while (pos >= 0) {
            String candidate = trimmed.substring(pos + 1).trim();
            if (candidate.startsWith("{") && candidate.endsWith("}")) return new JSONObject(candidate);
            trimmed = trimmed.substring(0, pos).trim();
            pos = trimmed.lastIndexOf('\n');
        }
        throw new IllegalStateException("Could not parse playlist information.");
    }

    private File createJobDir(String prefix) {
        File base = new File(getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir(), "jobs");
        base.mkdirs();
        File dir = new File(base, prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6));
        dir.mkdirs();
        return dir;
    }

    private File findNewestMediaFile(File dir, String format) {
        String expected = "mp3".equalsIgnoreCase(format) ? ".mp3" : ".mp4";
        List<File> files = new ArrayList<>();
        collectFiles(dir, files);
        File newest = null;
        for (File f : files) {
            if (!f.isFile() || f.length() <= 0 || !f.getName().toLowerCase(Locale.US).endsWith(expected)) continue;
            if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
        }
        return newest;
    }

    private void collectFiles(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    private Uri publishToDownloads(File source, String mimeType) throws Exception {
        return publishToDownloads(source, mimeType, null);
    }

    private Uri publishToDownloads(File source, String mimeType, String subfolder) throws Exception {
        String safeSubfolder = subfolder == null || subfolder.trim().isEmpty() ? "" : sanitizeFilename(subfolder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER;
            if (!safeSubfolder.isEmpty()) relativePath += "/" + safeSubfolder;
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Android could not create the Downloads file.");
            try (OutputStream os = resolver.openOutputStream(uri);
                 BufferedInputStream in = new BufferedInputStream(new FileInputStream(source), 4 * 1024 * 1024)) {
                if (os == null) throw new IllegalStateException("Android could not open the Downloads destination.");
                byte[] buffer = new byte[4 * 1024 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) os.write(buffer, 0, read);
                os.flush();
            } catch (Exception e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw new IllegalStateException("Storage permission is required on this Android version.");
        }
        File base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER);
        if (!safeSubfolder.isEmpty()) base = new File(base, safeSubfolder);
        base.mkdirs();
        File dest = uniqueFile(base, source.getName());
        copyFile(source, dest);
        return Uri.fromFile(dest);
    }

    private void createZip(File zipFile, List<File> files) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile), 2 * 1024 * 1024))) {
            // MP3/MP4 are already compressed. Re-compressing them wastes CPU and time
            // on a phone for almost no size benefit.
            zos.setLevel(Deflater.NO_COMPRESSION);
            byte[] buffer = new byte[2 * 1024 * 1024];
            for (File file : files) {
                ZipEntry entry = new ZipEntry(file.getName());
                zos.putNextEntry(entry);
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
                    int len;
                    while ((len = in.read(buffer)) > 0) zos.write(buffer, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private void copyFile(File source, File dest) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source), 4 * 1024 * 1024);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 4 * 1024 * 1024)) {
            byte[] buffer = new byte[4 * 1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private File uniqueFile(File dir, String name) {
        File dest = new File(dir, name);
        if (!dest.exists()) return dest;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int n = 2;
        while (dest.exists()) dest = new File(dir, base + " (" + n++ + ")" + ext);
        return dest;
    }

    private void sendProgress(String scope, int percent, String status, int completed, int total) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("scope", scope);
            obj.put("percent", Math.max(0, Math.min(100, percent)));
            obj.put("status", status == null ? "Processing…" : status);
            obj.put("completed", completed);
            obj.put("total", total);
        } catch (Exception ignored) { }
        sendEvent("onJobProgress", obj);
    }

    private void sendError(String scope, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("scope", scope);
            obj.put("message", message == null ? "Unknown error" : message);
        } catch (Exception ignored) { }
        sendEvent("onNativeError", obj);
        jobRunning.set(false);
        DownloadForegroundService.stop(this);
    }

    private void sendEvent(String method, JSONObject payload) {
        if (webView == null) return;
        String js = "window.AndroidCallbacks && window.AndroidCallbacks." + method + "(" + payload.toString() + ");";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST));
        }
    }

    private boolean isYouTube(String url) {
        String v = url.toLowerCase(Locale.US);
        return v.contains("youtube.com") || v.contains("youtu.be");
    }

    private String statusForLine(String line, String format, String mode) {
        if (line == null || line.trim().isEmpty()) return "Downloading…";
        String s = line.trim().replaceAll("\\s+", " ");
        String lower = s.toLowerCase(Locale.US);

        if (s.startsWith("[download]")) {
            String detail = s.substring("[download]".length()).trim();
            if (detail.toLowerCase(Locale.US).startsWith("destination:")) return "Starting media transfer…";
            if (detail.contains("%")) return "Downloading • " + trimStatus(detail, 78);
            return "Downloading…";
        }
        if (s.startsWith("[Merger]")) return "Merging audio & video…";
        if (s.startsWith("[ExtractAudio]")) return "Converting audio to MP3…";
        if (s.startsWith("[VideoRemuxer]")) return "Finalizing MP4…";
        if (s.startsWith("[Fixup") || s.startsWith("[Metadata]")) return "Finalizing media…";
        if (s.startsWith("[MoveFiles]")) return "Finalizing file…";
        if (lower.contains("deleting original file")) return "Cleaning temporary files…";
        if ("trim".equals(mode) && (lower.contains("ffmpeg") || lower.contains("section"))) return "Processing trim…";
        if ("mp3".equalsIgnoreCase(format) && lower.contains("audio")) return "Processing audio…";
        return simplifyStatus(s);
    }

    private String trimStatus(String value, int max) {
        String s = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private String simplifyStatus(String line) {
        String s = line.trim().replaceAll("\\s+", " ");
        if (s.length() > 90) s = s.substring(0, 90) + "…";
        return s;
    }

    private String mimeFor(File file) {
        String n = file.getName().toLowerCase(Locale.US);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private String sanitizeFilename(String value) {
        String s = value == null ? "Playlist" : value.replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "Playlist";
        if (s.length() > 120) s = s.substring(0, 120).trim();
        return s;
    }

    private String safeMessage(Throwable e) {
        if (e == null) return "Unknown error";
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        msg = msg.replace("ERROR:", "").trim();
        return msg.length() > 1600 ? msg.substring(0, 1600) + "…" : msg;
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        try { file.delete(); } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidApp");
            webView.destroy();
        }
        ioExecutor.shutdownNow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
