package com.sedayar.app.fragment;

import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentHandwritingBinding;
import com.sedayar.app.util.DateUtils;
import com.sedayar.app.view.DrawingView;

import java.io.File;
import java.util.Locale;

/**
 * Handwriting tab: a full-screen canvas for writing during class. With
 * "Record class" (Lecture Mode) it also records the professor's voice and
 * links every stroke to a moment in the audio, so playback can jump to
 * exactly where something was written.
 */
public class HandwritingFragment extends Fragment {

    private static final int REQ_REC = 2002;
    private static final String TAG = "Handwriting";

    private FragmentHandwritingBinding binding;

    private MediaRecorder recorder;
    private File audioFile;
    private boolean recording = false;
    private long recStart = 0;
    private final Handler recTimer = new Handler(Looper.getMainLooper());

    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (recording) {
                long s = (SystemClock.elapsedRealtime() - recStart) / 1000;
                binding.tvRecTime.setText(String.format(Locale.US, "REC %02d:%02d",
                        s / 60, s % 60));
                recTimer.postDelayed(this, 500);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHandwritingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupToolbar();
        binding.tvRecHint.setVisibility(View.GONE);
    }

    private void setupToolbar() {
        final int[] penColors = {0xFF1F2937, 0xFF4F6DF5, 0xFFDC2626, 0xFF16A34A, 0xFFD97706};
        final View[] pens = {binding.pen0, binding.pen1, binding.pen2, binding.pen3, binding.pen4};
        for (int i = 0; i < pens.length; i++) {
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(penColors[i]);
            d.setStroke(dp(2), ContextCompat.getColor(requireContext(), R.color.stroke));
            pens[i].setBackground(d);
            final int idx = i;
            pens[i].setOnClickListener(v -> {
                binding.drawingView.setPenColor(penColors[idx]);
                highlightPens(pens, idx);
            });
        }
        highlightPens(pens, 0);

        final View[] widths = {binding.width1, binding.width2, binding.width3};
        final float[] widthPx = {dp(4), dp(7), dp(11)};
        for (int i = 0; i < widths.length; i++) {
            android.graphics.drawable.GradientDrawable d =
                    new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(ContextCompat.getColor(requireContext(), R.color.text_secondary));
            widths[i].setBackground(d);
            final int idx = i;
            widths[i].setOnClickListener(v -> {
                binding.drawingView.setStrokeWidth(widthPx[idx]);
                highlightWidths(widths, idx);
            });
        }
        highlightWidths(widths, 1);
        binding.drawingView.setStrokeWidth(dp(7));

        binding.drawingView.setStrokesChangedListener((canUndo, canRedo) -> {
            binding.btnUndo.setEnabled(canUndo);
            binding.btnRedo.setEnabled(canRedo);
        });

        binding.btnErase.setOnClickListener(v -> {
            boolean on = !binding.drawingView.isEraseMode();
            binding.drawingView.setEraseMode(on);
            binding.btnErase.setIconTint(ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(),
                            on ? R.color.primary : R.color.text_secondary)));
        });
        binding.btnUndo.setOnClickListener(v -> binding.drawingView.undo());
        binding.btnRedo.setOnClickListener(v -> binding.drawingView.redo());
        binding.btnClear.setOnClickListener(v -> binding.drawingView.clearAll());
        binding.btnSaveCanvas.setOnClickListener(v -> save());
        binding.btnRec.setOnClickListener(v -> onRecClicked());
    }

    // ---------------------------------------------------------- lecture mode

    private void onRecClicked() {
        if (recording) {
            save(); // stop + persist everything
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_REC);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        File dir = new File(requireContext().getFilesDir(), "lectures");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        audioFile = new File(dir, "lecture_" + System.currentTimeMillis() + ".m4a");
        try {
            recorder = buildRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            Log.e(TAG, "recorder start failed", e);
            recorder = null;
            Toast.makeText(requireContext(), R.string.speech_error_generic,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        recording = true;
        recStart = SystemClock.elapsedRealtime();
        binding.drawingView.startTiming(recStart);
        binding.recCard.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.rec_bg)));
        binding.tvRecHint.setVisibility(View.VISIBLE);
        binding.btnRec.setIconResource(R.drawable.ic_stop);
        recTimer.post(recTick);
    }

    @SuppressWarnings("deprecation")
    private MediaRecorder buildRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new MediaRecorder(requireContext());
        }
        return new MediaRecorder();
    }

    /** Stops the recorder; returns the recorded duration in ms (0 if none). */
    private long stopRecording() {
        if (!recording || recorder == null) {
            return 0L;
        }
        long elapsed = SystemClock.elapsedRealtime() - recStart;
        boolean stopped = false;
        try {
            recorder.stop();
            stopped = true;
        } catch (Exception e) {
            // no valid audio data captured
            if (audioFile != null) {
                audioFile.delete();
                audioFile = null;
            }
        } finally {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        recording = false;
        recTimer.removeCallbacks(recTick);
        binding.drawingView.stopTiming();
        binding.recCard.setBackgroundTintList(null);
        binding.recCard.setCardBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.surface));
        binding.tvRecHint.setVisibility(View.GONE);
        binding.btnRec.setIconResource(R.drawable.ic_mic);
        binding.tvRecTime.setText("REC 00:00");
        return stopped ? elapsed : 0L;
    }

    // ------------------------------------------------------------------ save

    private void save() {
        boolean hasDrawing = !binding.drawingView.isEmpty();
        if (!hasDrawing && !recording) {
            Toast.makeText(requireContext(), R.string.canvas_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        long audioDuration = stopRecording();

        Note note = new Note();
        note.createdAt = System.currentTimeMillis();
        note.title = getString(R.string.tab_draw) + " — " + DateUtils.format(note.createdAt);

        try {
            File drawings = new File(requireContext().getFilesDir(), "drawings");
            if (!drawings.exists()) {
                drawings.mkdirs();
            }
            String base = "lecture_" + note.createdAt;

            File png = new File(drawings, base + ".png");
            if (hasDrawing && binding.drawingView.saveTo(png)) {
                note.drawingPath = png.getAbsolutePath();
            }

            if (audioFile != null && audioFile.exists()) {
                note.audioPath = audioFile.getAbsolutePath();
                File timing = new File(drawings, base + ".json");
                if (binding.drawingView.exportTimings(timing, audioDuration)) {
                    note.timingPath = timing.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
        }

        SedayarApp.get().repository().save(note, id -> {
            if (!isAdded()) {
                return;
            }
            binding.drawingView.clearAll();
            Snackbar.make(binding.getRoot(), R.string.lecture_saved,
                    Snackbar.LENGTH_LONG).show();
        });
    }

    // -------------------------------------------------------------- painting

    private void highlightPens(View[] pens, int selected) {
        for (int i = 0; i < pens.length; i++) {
            android.graphics.drawable.GradientDrawable d =
                    (android.graphics.drawable.GradientDrawable) pens[i].getBackground();
            d.setStroke(dp(2), ContextCompat.getColor(requireContext(),
                    i == selected ? R.color.primary : R.color.stroke));
        }
        binding.drawingView.setEraseMode(false);
        binding.btnErase.setIconTint(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)));
    }

    private void highlightWidths(View[] widths, int selected) {
        for (int i = 0; i < widths.length; i++) {
            android.graphics.drawable.GradientDrawable d =
                    (android.graphics.drawable.GradientDrawable) widths[i].getBackground();
            d.setColor(ContextCompat.getColor(requireContext(),
                    i == selected ? R.color.primary : R.color.text_secondary));
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_REC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(requireContext(), R.string.rec_rationale,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (recording) {
            // leaving the tab mid-recording: stop audio, keep the drawing
            stopRecording();
            Toast.makeText(requireContext(), R.string.rec_stop, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recTimer.removeCallbacks(recTick);
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
