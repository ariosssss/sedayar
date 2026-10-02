package com.sedayar.app.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single-owner microphone session behind the live dictation tab.
 *
 * The old build ran MediaRecorder AND SpeechRecognizer at the same time —
 * both fight over the microphone, so on most devices the recognition dies
 * and the audio comes out choppy. Here there is exactly ONE mic consumer:
 *
 *   AudioRecord (16 kHz mono PCM)
 *        ├─► writer thread — one continuous WAV file, never interrupted
 *        └─► Vosk thread   — streaming partial text + timed segments
 *
 * Because every byte of recorded audio also passes through the recognizer,
 * transcript timestamps are exact by construction ([mm:ss] of the file).
 * If the Persian model is not installed the session still runs (audio-only)
 * and the UI can offer the one-time model download.
 */
public final class LiveTranscriber {

    public interface Listener {
        /** Model loaded, recognizer streaming (or audio-only mode engaged). */
        void onEngineReady(boolean textEnabled);

        /** Recognizer or recorder failed; the other side keeps going. */
        void onEngineError(String message);

        /** Live, still-changing text. */
        void onPartial(String text);

        /** A finished phrase with its exact position inside the audio. */
        void onSegment(long startMs, long endMs, String text);
    }

    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AudioRecord record;
    private Thread recordThread;
    private Thread voskThread;
    private LinkedBlockingQueue<byte[]> queue;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private File wavFile;
    private volatile long bytesWritten;       // PCM bytes → exact duration
    private final Segmenter segmenter = new Segmenter();
    /** Every finished segment, kept so the note is complete even if the UI
     *  is torn down the same instant the engine flushes its tail. */
    private final List<Transcript.Segment> finalized = new ArrayList<>();
    private volatile boolean textEnabled = false;
    private volatile String lastPartial = "";

    public LiveTranscriber(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isTextEnabled() {
        return textEnabled;
    }

    public File file() {
        return wavFile;
    }

    /** Snapshot of all finished segments (safe from any thread). */
    public List<Transcript.Segment> segmentsSnapshot() {
        synchronized (finalized) {
            return new ArrayList<>(finalized);
        }
    }

    /** Exact recorded duration — derived from PCM bytes, not wall clock. */
    public long durationMs() {
        return bytesWritten * 1000L / (SAMPLE_RATE * 2);
    }

    /**
     * Starts recording + (when the model is installed) live transcription.
     * Returns false when the microphone could not be opened at all.
     */
    public boolean start(File outWav) {
        if (running.get()) {
            return true;
        }
        wavFile = outWav;
        bytesWritten = 0;
        segmenter.flush(0);
        synchronized (finalized) {
            finalized.clear();
        }
        textEnabled = false;
        lastPartial = "";

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            return false;
        }
        int bufSize = Math.max(minBuf * 2, 8192);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Exception | UnsatisfiedLinkError e) {
            return false;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            try {
                rec.release();
            } catch (Exception ignored) {
            }
            return false;
        }
        record = rec;

        boolean hasModel = VoskTranscriber.isModelDirValid(VoskTranscriber.modelDir(context));
        queue = hasModel ? new LinkedBlockingQueue<>(64) : null;

        running.set(true);
        rec.startRecording();

        recordThread = new Thread(this::recordLoop, "sedayar-record");
        recordThread.start();

        if (hasModel) {
            voskThread = new Thread(this::voskLoop, "sedayar-vosk");
            voskThread.start();
        } else {
            main.post(() -> listener.onEngineReady(false));
        }
        return true;
    }

    /** Stops everything and finalizes the WAV header. Returns the file. */
    public File stop() {
        if (!running.getAndSet(false)) {
            finalizeHeader();
            return wavFile;
        }
        try {
            if (record != null) {
                record.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            if (record != null) {
                record.release();
            }
        } catch (Exception ignored) {
        }
        record = null;
        try {
            if (recordThread != null) {
                recordThread.join(3000);
            }
        } catch (InterruptedException ignored) {
        }
        recordThread = null;
        try {
            if (voskThread != null) {
                voskThread.join(10000); // model close can take a moment
            }
        } catch (InterruptedException ignored) {
        }
        voskThread = null;
        finalizeHeader();
        return wavFile;
    }

    // ------------------------------------------------------------ threads

    private void recordLoop() {
        final int chunkBytes = 4096; // 128 ms of 16 kHz mono
        byte[] buf = new byte[chunkBytes];
        try (FileOutputStream out = new FileOutputStream(wavFile)) {
            AudioDecoder.writeWavHeader(out);
            while (running.get()) {
                int n = record == null ? 0 : record.read(buf, 0, buf.length);
                if (n > 0) {
                    out.write(buf, 0, n);
                    bytesWritten += n;
                    if (queue != null) {
                        byte[] copy = new byte[n];
                        System.arraycopy(buf, 0, copy, 0, n);
                        if (!queue.offer(copy)) {
                            queue.poll(); // drop oldest, never stall the mic
                            queue.offer(copy);
                        }
                    }
                } else if (n < 0) {
                    break;
                }
            }
        } catch (Exception e) {
            main.post(() -> listener.onEngineError("record_failed"));
        }
    }

    private void voskLoop() {
        Model model = null;
        Recognizer recognizer = null;
        try {
            File dir = VoskTranscriber.modelDir(context);
            model = new Model(dir.getAbsolutePath());
            recognizer = new Recognizer(model, (float) SAMPLE_RATE);
            try {
                recognizer.setWords(true);
            } catch (Throwable ignored) {
            }
            textEnabled = true;
            main.post(() -> listener.onEngineReady(true));

            byte[] chunk;
            while (running.get() || (queue != null && !queue.isEmpty())) {
                chunk = queue == null ? null : queue.poll();
                if (chunk == null) {
                    if (running.get()) {
                        Thread.sleep(40);
                        continue;
                    }
                    break;
                }
                if (recognizer.acceptWaveForm(chunk, chunk.length)) {
                    emitResult(recognizer.getResult());
                } else {
                    emitPartial(recognizer.getPartialResult());
                }
            }
            emitResult(recognizer.getFinalResult());
            for (Transcript.Segment s : segmenter.flush(durationMs() + 1)) {
                emitSegment(s);
            }
        } catch (Throwable t) {
            main.post(() -> listener.onEngineError("engine_error"));
        } finally {
            textEnabled = false;
            try {
                if (recognizer != null) {
                    recognizer.close();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (model != null) {
                    model.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------- events

    private void emitResult(String json) {
        try {
            JSONObject result = new JSONObject(json);
            JSONArray words = result.optJSONArray("result");
            if (words != null) {
                for (int i = 0; i < words.length(); i++) {
                    JSONObject w = words.getJSONObject(i);
                    segmenter.add(
                            (long) (w.optDouble("start", 0) * 1000.0),
                            (long) (w.optDouble("end", 0) * 1000.0),
                            w.optString("word", ""));
                }
            }
            String text = result.optString("text", "").trim();
            if (!text.isEmpty()) {
                for (Transcript.Segment s : segmenter.flush(durationMs() + 1)) {
                    emitSegment(s);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void emitPartial(String json) {
        try {
            JSONObject partial = new JSONObject(json);
            String text = partial.optString("text", "").trim();
            if (!text.isEmpty() && !text.equals(lastPartial)) {
                lastPartial = text;
                main.post(() -> listener.onPartial(text));
            }
        } catch (Exception ignored) {
        }
    }

    private void emitSegment(Transcript.Segment s) {
        synchronized (finalized) {
            finalized.add(s);
        }
        main.post(() -> listener.onSegment(s.startMs, s.endMs, s.text));
    }

    private void finalizeHeader() {
        try {
            if (wavFile != null && wavFile.exists() && bytesWritten > 0) {
                AudioDecoder.patchWavHeader(wavFile, bytesWritten);
            }
        } catch (Exception ignored) {
        }
    }
}
