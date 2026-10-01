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
import java.util.ArrayList;
import java.util.List;
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

    private VoskTranscriber() {
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
            // Resume from a completed-but-not-installed download
            tryInstallFromZip(context, listener);
            return;
        }
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
        listener.onProgress(0f);

        Model model = new Model(dir);
        try {
            Recognizer recognizer = new Recognizer(model, 16000f);

            WordBuffer buffer = new WordBuffer();
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

    private static void collect(String json, WordBuffer buffer, Transcript transcript,
                                TranscribeListener listener) {
        try {
            JSONObject result = new JSONObject(json);
            JSONArray words = result.optJSONArray("result");
            if (words == null) {
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
    private static void flushBuffer(WordBuffer buffer, long fallbackEnd,
                                    Transcript transcript, TranscribeListener listener) {
        for (Transcript.Segment s : buffer.flush(fallbackEnd)) {
            transcript.add(s.startMs, s.endMs, s.text);
            listener.onSegment(s);
        }
    }

    /** Accumulates words and splits them into human-sized segments. */
    private static final class WordBuffer {
        private final List<long[]> words = new ArrayList<>(); // {start, end}
        private final List<String> texts = new ArrayList<>();

        void add(long startMs, long endMs, String word) {
            if (word == null || word.trim().isEmpty()) {
                return;
            }
            words.add(new long[]{startMs, endMs});
            texts.add(word.trim());
        }

        List<Transcript.Segment> flush(long fallbackEnd) {
            List<Transcript.Segment> out = new ArrayList<>();
            long segStart = -1, segEnd = -1;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                long[] w = words.get(i);
                if (segStart < 0) {
                    segStart = w[0];
                    sb.setLength(0);
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(texts.get(i));
                segEnd = w[1];
                boolean breakAfter = i == words.size() - 1;
                if (!breakAfter) {
                    long[] next = words.get(i + 1);
                    boolean gap = next[0] - w[1] > 1200;
                    boolean longSeg = w[1] - segStart > 12_000;
                    boolean manyWords = i % 40 == 39;
                    breakAfter = gap || longSeg || manyWords;
                }
                if (breakAfter && sb.length() > 0) {
                    out.add(new Transcript.Segment(segStart, segEnd, sb.toString()));
                    segStart = -1;
                }
            }
            if (segStart >= 0 && sb.length() > 0) {
                out.add(new Transcript.Segment(segStart,
                        Math.max(segEnd, segStart + 500), sb.toString()));
            }
            words.clear();
            texts.clear();
            return out;
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
