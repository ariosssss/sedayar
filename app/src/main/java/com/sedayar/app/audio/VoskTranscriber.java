package com.sedayar.app.audio;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.sedayar.app.util.AppPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Offline transcription with Vosk: manages the Persian model download
 * (alphacephei.com — reachable without a VPN in Iran) and converts a
 * 16 kHz mono WAV into a timed {@link Transcript}.
 */
public final class VoskTranscriber {

    public static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip";

    public interface ModelDownloadListener {
        void onProgress(int percent);

        void onSuccess(File modelDir);

        void onError(String message);
    }

    public interface TranscribeListener {
        void onPartial(String text);

        void onSegment(Transcript.Segment segment);

        void onProgress(float ratio);

        void onSuccess(Transcript transcript);

        void onError(String message);
    }

    private static final long DOWNLOAD_ID_KEY = 47421L;
    private static final String DOWNLOAD_PREF = "vosk_download_id";

    /** The published zip is ~50 MB; anything smaller is a partial download. */
    private static final long MIN_ZIP_BYTES = 40L * 1024 * 1024;

    private VoskTranscriber() {
    }

    // ---------------------------------------------------------- validation

    /**
     * True when the directory really contains a usable Vosk model. Guards
     * against the native library aborting the whole process on a corrupt or
     * half-copied folder — the #1 hard-crash source of the file tab.
     */
    public static boolean isModelDirValid(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        File am = new File(dir, "am");
        File graph = new File(dir, "graph");
        boolean amOk = (new File(am, "final.mdl").isFile()
                || new File(am, "final.am").isFile());
        boolean graphOk = new File(graph, "HCLG.fst").isFile()
                || new File(graph, "HCLr.fst").isFile()
                || new File(graph, "HCLr_unweighted.fst").isFile();
        boolean confOk = new File(dir, "conf" + File.separator + "model.conf").isFile()
                || new File(dir, "conf" + File.separator + "mfcc.conf").isFile();
        return amOk && graphOk && confOk;
    }

    /** Zip magic + minimum size check — a partial download must never install. */
    private static boolean isPlausibleZip(File zip) {
        if (!zip.isFile() || zip.length() < MIN_ZIP_BYTES) {
            return false;
        }
        try (InputStream in = new FileInputStream(zip)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------- model download

    public static File modelZipFile(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "vosk_model_fa.zip");
    }

    public static File modelDir(Context context) {
        return new File(context.getFilesDir(), "model-fa");
    }

    public static boolean isDownloading(Context context) {
        long id = context.getSharedPreferences("sedayar_prefs", Context.MODE_PRIVATE)
                .getLong(DOWNLOAD_PREF, -1L);
        if (id < 0) {
            return false;
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            return false;
        }
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                return status == DownloadManager.STATUS_RUNNING
                        || status == DownloadManager.STATUS_PENDING
                        || status == DownloadManager.STATUS_PAUSED;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void startModelDownload(Context context, ModelDownloadListener listener) {
        File zip = modelZipFile(context);
        if (zip.exists()) {
            if (isPlausibleZip(zip)) {
                // Resume from a completed-but-not-installed download
                tryInstallFromZip(context, listener);
            } else {
                // A partial/interrupted download: drop it and start over
                zip.delete();
                enqueueDownload(context, listener);
            }
            return;
        }
        enqueueDownload(context, listener);
    }

    private static void enqueueDownload(Context context, ModelDownloadListener listener) {
        File zip = modelZipFile(context);
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            listener.onError("DownloadManager unavailable");
            return;
        }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(MODEL_URL));
        req.setTitle("Sedayar Persian model (Vosk)");
        req.setDescription("vosk-model-small-fa-0.42");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationUri(Uri.fromFile(zip));
        long id = dm.enqueue(req);
        context.getSharedPreferences("sedayar_prefs", Context.MODE_PRIVATE)
                .edit().putLong(DOWNLOAD_PREF, id).apply();
        pollProgress(context, id, listener);
    }

    private static void pollProgress(Context context, long id, ModelDownloadListener listener) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                DownloadManager dm = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) {
                    return;
                }
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c == null || !c.moveToFirst()) {
                        handler.postDelayed(this, 800);
                        return;
                    }
                    int status = c.getInt(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        tryInstallFromZip(context, listener);
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        listener.onError("download failed");
                    } else {
                        long downloaded = c.getLong(c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (total > 0) {
                            listener.onProgress((int) (100 * downloaded / total));
                        }
                        handler.postDelayed(this, 800);
                    }
                } catch (Exception e) {
                    listener.onError(String.valueOf(e.getMessage()));
                }
            }
        }, 800);
    }

    private static void tryInstallFromZip(Context context, ModelDownloadListener listener) {
        File zip = modelZipFile(context);
        if (!zip.exists()) {
            listener.onError("zip missing");
            return;
        }
        new Thread(() -> {
            try {
                File tmp = new File(context.getFilesDir(), "model_tmp");
                deleteRecursive(tmp);
                tmp.mkdirs();
                unzip(zip, tmp);
                // The zip contains a single folder vosk-model-small-fa-0.42/
                File inner = tmp;
                File[] children = tmp.listFiles();
                if (children != null && children.length == 1 && children[0].isDirectory()) {
                    inner = children[0];
                }
                File dest = modelDir(context);
                deleteRecursive(dest);
                if (!inner.renameTo(dest)) {
                    // rename across mount points can fail; fall back to copy
                    copyRecursive(inner, dest);
                    deleteRecursive(tmp);
                }
                if (!isModelDirValid(dest)) {
                    // corrupt archive — never hand this to the native engine
                    deleteRecursive(dest);
                    zip.delete();
                    throw new Exception("model_install_invalid");
                }
                AppPrefs.setVoskModelDir(context, dest.getAbsolutePath());
                new Handler(Looper.getMainLooper()).post(
                        () -> listener.onSuccess(dest));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(
                        () -> listener.onError(String.valueOf(e.getMessage())));
            }
        }, "vosk-unzip").start();
    }

    public static void deleteModel(Context context) {
        deleteRecursive(modelDir(context));
        AppPrefs.setVoskModelDir(context, "");
        File zip = modelZipFile(context);
        if (zip.exists()) {
            zip.delete();
        }
    }

    // ------------------------------------------------------------ transcribe

    /** Runs on the caller's thread; call from a background worker. */
    public static void transcribe(Context context, File wavFile,
                                  AtomicBoolean cancelFlag,
                                  TranscribeListener listener) throws Exception {
        String dir = AppPrefs.voskModelDir(context);
        if (dir == null || dir.isEmpty()) {
            listener.onError("model not ready");
            return;
        }
        File modelPath = new File(dir);
        if (!isModelDirValid(modelPath)) {
            listener.onError("model_corrupt");
            return;
        }
        listener.onProgress(0f);

        Model model;
        Recognizer recognizer;
        try {
            model = new Model(modelPath.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            listener.onError("engine_missing");
            return;
        } catch (Throwable t) {
            listener.onError("model_load_failed");
            return;
        }
        try {
            recognizer = new Recognizer(model, 16000f);
            try {
                recognizer.setWords(true);
            } catch (Throwable ignored) {
                // older builds: word timings unavailable, plain text still works
            }

            Segmenter buffer = new Segmenter();
            Transcript transcript = new Transcript();
            transcript.language = "fa";

            long totalBytes = Math.max(1, wavFile.length() - 44);
            long consumed = 0;
            try (InputStream in = new FileInputStream(wavFile)) {
                long skipped = in.skip(44);
                while (skipped >= 0 && skipped < 44) {
                    long n = in.skip(44 - skipped);
                    if (n <= 0) {
                        break;
                    }
                    skipped += n;
                }

                byte[] buf = new byte[6400]; // 200 ms of 16 kHz mono
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        listener.onError("cancelled");
                        return;
                    }
                    if (recognizer.acceptWaveForm(buf, n)) {
                        collect(recognizer.getResult(), buffer, transcript, listener);
                    } else {
                        try {
                            JSONObject partial = new JSONObject(recognizer.getPartialResult());
                            String text = partial.optString("text", "");
                            if (!text.isEmpty()) {
                                listener.onPartial(text);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    consumed += n;
                    listener.onProgress(Math.min(0.99f,
                            consumed / (float) totalBytes));
                }
            }
            collect(recognizer.getFinalResult(), buffer, transcript, listener);
            flushBuffer(buffer, transcript.endMs() + 1, transcript, listener);

            listener.onProgress(1f);
            listener.onSuccess(transcript);
        } finally {
            model.close();
        }
    }

    private static void collect(String json, Segmenter buffer, Transcript transcript,
                                TranscribeListener listener) {
        try {
            JSONObject result = new JSONObject(json);
            JSONArray words = result.optJSONArray("result");
            if (words == null) {
                String text = result.optString("text", "");
                if (!text.trim().isEmpty()) {
                    // no timings available — one segment, engine-level fallback
                    Transcript.Segment s = new Transcript.Segment(0, 0, text);
                    transcript.add(0, 0, text);
                    listener.onSegment(s);
                }
                return;
            }
            for (int i = 0; i < words.length(); i++) {
                JSONObject w = words.getJSONObject(i);
                buffer.add(
                        (long) (w.optDouble("start", 0) * 1000.0),
                        (long) (w.optDouble("end", 0) * 1000.0),
                        w.optString("word", ""));
            }
        } catch (Exception ignored) {
        }
    }

    /** Groups consecutive words into ~12 s segments for the SRT / HTML timeline. */
    private static void flushBuffer(Segmenter buffer, long fallbackEnd,
                                    Transcript transcript, TranscribeListener listener) {
        for (Transcript.Segment s : buffer.flush(fallbackEnd)) {
            transcript.add(s.startMs, s.endMs, s.text);
            listener.onSegment(s);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void unzip(File zip, File targetDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(targetDir.getCanonicalPath())) {
                    continue; // zip-slip guard
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        f.delete();
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    copyRecursive(k, new File(dst, k.getName()));
                }
            }
        } else {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }
}
