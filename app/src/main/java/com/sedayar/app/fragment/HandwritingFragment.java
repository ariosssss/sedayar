package com.sedayar.app.fragment;

import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
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
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentHandwritingBinding;
import com.sedayar.app.util.DateUtils;
import com.sedayar.app.view.DrawingView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handwriting tab — a proper A4 notebook: bounded paper sheets (210:297) that
 * can never stretch under the status bar, multiple pages ("برگه"), and every
 * page holds both handwriting and typed text. With "Record class" (Lecture
 * Mode) the professor's voice is recorded and every page/stroke is linked to
 * the exact moment it appeared, so playback replays the class like a live
 * blackboard.
 */
public class HandwritingFragment extends Fragment {

    private static final int REQ_REC = 2002;
    private static final String TAG = "Handwriting";
    private static final int MAX_PAGES = 40;

    /** One notebook page: strokes + typed text + the moment it was born. */
    private static final class LecturePage {
        /** ms since recording start when this page was created; 0 for the first page; -1 when never recorded. */
        long bornMs = -1L;
        String text = "";
        List<DrawingView.StrokeData> strokes = new ArrayList<>();
    }

    private FragmentHandwritingBinding binding;

    private MediaRecorder recorder;
    private File audioFile;
    private boolean recording = false;
    private long recStart = 0;
    private final Handler recTimer = new Handler(Looper.getMainLooper());

    private final List<LecturePage> pages = new ArrayList<>();
    private int pageIndex = 0;
    private boolean textMode = false;

    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (recording && binding != null) {
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

        pages.clear();
        pages.add(new LecturePage());
        pageIndex = 0;
        textMode = false;

        setupToolbar();
        sizePaperOnLayout();
        updatePageLabel();
    }

    // ------------------------------------------------------------- paper A4

    /** Keeps the sheet at a real A4 ratio, centered inside its holder. */
    private void sizePaperOnLayout() {
        binding.paperHolder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> sizePaper());
        binding.paperHolder.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        binding.paperHolder.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        sizePaper();
                    }
                });
    }

    private void sizePaper() {
        if (binding == null) {
            return;
        }
        int availW = binding.paperHolder.getWidth() - dp(28);
        int availH = binding.paperHolder.getHeight() - dp(20);
        if (availW <= 0 || availH <= 0) {
            return;
        }
        final float ratio = 297f / 210f; // A4
        int w, h;
        if (availW * ratio <= availH) {
            w = availW;
            h = Math.round(w * ratio);
        } else {
            h = availH;
            w = Math.round(h / ratio);
        }
        ViewGroup.LayoutParams lp = binding.paperCard.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            binding.paperCard.setLayoutParams(lp);
        }
    }

    // ----------------------------------------------------------------- tools

    private void setupToolbar() {
        final int[] penColors = {0xFF26251E, 0xFF1D3A6D, 0xFFC0392B, 0xFF1E8449, 0xFFA8861D};
        final View[] pens = {binding.pen0, binding.pen1, binding.pen2, binding.pen3, binding.pen4};
        for (int i = 0; i < pens.length; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
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
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
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
            if (binding != null) {
                binding.btnUndo.setEnabled(canUndo);
                binding.btnRedo.setEnabled(canRedo);
            }
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
        binding.btnClear.setOnClickListener(v -> {
            binding.drawingView.clearAll();
            pages.get(pageIndex).strokes.clear();
        });
        binding.btnSaveCanvas.setOnClickListener(v -> save());
        binding.btnRec.setOnClickListener(v -> onRecClicked());

        // draw <-> type
        binding.btnTextMode.setOnClickListener(v -> setTextMode(!textMode));

        // pages
        binding.btnPageAdd.setOnClickListener(v -> addPage());
        binding.btnPagePrev.setOnClickListener(v -> switchPage(-1));
        binding.btnPageNext.setOnClickListener(v -> switchPage(1));
    }

    private void setTextMode(boolean on) {
        setTextMode(on, true);
    }

    private void setTextMode(boolean on, boolean notify) {
        textMode = on;
        binding.etTyped.setVisibility(on ? View.VISIBLE : View.GONE);
        binding.drawingView.setVisibility(on ? View.GONE : View.VISIBLE);
        binding.toolsDraw.setVisibility(on ? View.GONE : View.VISIBLE);
        binding.btnTextMode.setIconTint(ColorStateList.valueOf(ContextCompat.getColor(
                requireContext(), on ? R.color.gold_deep : R.color.on_primary_container)));
        if (notify) {
            Toast.makeText(requireContext(), on ? R.string.mode_type_on : R.string.mode_draw_on,
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------------- pages

    private void commitCurrentPage() {
        LecturePage p = pages.get(pageIndex);
        p.strokes = binding.drawingView.snapshotStrokes();
        p.text = binding.etTyped.getText() == null ? "" : binding.etTyped.getText().toString();
    }

    private void showPage(int index) {
        LecturePage p = pages.get(index);
        binding.drawingView.restoreStrokes(p.strokes);
        binding.etTyped.setText(p.text);
        binding.etTyped.setSelection(p.text.length());
        pageIndex = index;
        updatePageLabel();
    }

    private void updatePageLabel() {
        binding.tvPage.setText(String.format(Locale.US,
                getString(R.string.page_x_of_y), pageIndex + 1, pages.size()));
        boolean canPrev = pageIndex > 0;
        boolean canNext = pageIndex < pages.size() - 1;
        binding.btnPagePrev.setEnabled(canPrev);
        binding.btnPageNext.setEnabled(canNext);
        binding.btnPagePrev.setAlpha(canPrev ? 1f : 0.35f);
        binding.btnPageNext.setAlpha(canNext ? 1f : 0.35f);
    }

    private void addPage() {
        if (pages.size() >= MAX_PAGES) {
            Toast.makeText(requireContext(), R.string.page_limit, Toast.LENGTH_SHORT).show();
            return;
        }
        commitCurrentPage();
        LecturePage p = new LecturePage();
        p.bornMs = recording ? (SystemClock.elapsedRealtime() - recStart) : -1L;
        pages.add(p);
        binding.drawingView.restoreStrokes(p.strokes);
        binding.etTyped.setText("");
        pageIndex = pages.size() - 1;
        updatePageLabel();
        if (recording) {
            Toast.makeText(requireContext(), R.string.page_added, Toast.LENGTH_SHORT).show();
        }
    }

    private void switchPage(int dir) {
        int target = pageIndex + dir;
        if (target < 0 || target >= pages.size()) {
            return;
        }
        commitCurrentPage();
        showPage(target);
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
            androidx.core.app.ActivityCompat.requestPermissions(requireActivity(),
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
        // pages created before the recording belongs to the very beginning
        for (LecturePage p : pages) {
            if (p.bornMs < 0) {
                p.bornMs = 0L;
            }
        }
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

    private boolean notebookEmpty() {
        for (LecturePage p : pages) {
            if (!p.strokes.isEmpty() || !(p.text == null || p.text.trim().isEmpty())) {
                return false;
            }
        }
        return !recording && binding.drawingView.isEmpty();
    }

    /** Builds the lecture note (stops the recorder). Null when nothing to save. */
    private Note buildNote() {
        if (binding == null) {
            return null;
        }
        boolean empty = notebookEmpty();
        if (empty && !recording) {
            return null;
        }
        commitCurrentPage();
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
            int canvasW = Math.max(1, binding.drawingView.canvasWidth());
            int canvasH = Math.max(1, binding.drawingView.canvasHeight());

            if (audioFile != null && audioFile.exists()) {
                // --- full notebook export (JSON v2) ---
                note.audioPath = audioFile.getAbsolutePath();

                JSONArray pagesJson = new JSONArray();
                String firstImage = null;
                StringBuilder allText = new StringBuilder();
                for (int i = 0; i < pages.size(); i++) {
                    LecturePage p = pages.get(i);
                    JSONObject pj = new JSONObject();
                    pj.put("bornMs", Math.max(0, p.bornMs));

                    String text = p.text == null ? "" : p.text.trim();
                    if (!text.isEmpty()) {
                        pj.put("text", text);
                        if (allText.length() > 0) {
                            allText.append("\n\n");
                        }
                        allText.append(text);
                    }

                    boolean hasStrokes = !p.strokes.isEmpty();
                    if (hasStrokes) {
                        File png = new File(drawings, base + "_p" + i + ".png");
                        if (DrawingView.renderStrokes(p.strokes, canvasW, canvasH, png)) {
                            pj.put("image", png.getName());
                            if (firstImage == null) {
                                firstImage = png.getAbsolutePath();
                            }
                        }
                        JSONArray arr = new JSONArray();
                        for (DrawingView.StrokeData d : p.strokes) {
                            JSONObject o = new JSONObject();
                            o.put("c", d.color);
                            o.put("w", d.width);
                            o.put("e", d.eraser ? 1 : 0);
                            o.put("t", Math.max(0, d.timeMs));
                            JSONArray pts = new JSONArray();
                            for (float[] pt : d.points) {
                                pts.put(Math.round(pt[0]));
                                pts.put(Math.round(pt[1]));
                            }
                            o.put("p", pts);
                            arr.put(o);
                        }
                        pj.put("strokes", arr);
                    }
                    pagesJson.put(pj);
                }

                JSONObject root = new JSONObject();
                root.put("version", 2);
                root.put("canvasW", canvasW);
                root.put("canvasH", canvasH);
                root.put("durationMs", audioDuration);
                root.put("pages", pagesJson);

                File timing = new File(drawings, base + ".json");
                FileOutputStream fos = new FileOutputStream(timing);
                fos.write(root.toString().getBytes("UTF-8"));
                fos.flush();
                fos.close();
                note.timingPath = timing.getAbsolutePath();
                note.drawingPath = firstImage; // thumbnail = first drawn page
                if (allText.length() > 0) {
                    note.content = allText.toString();
                }
            } else {
                // --- no audio: keep the old single-image behaviour ---
                StringBuilder allText = new StringBuilder();
                String firstImage = null;
                for (int i = 0; i < pages.size(); i++) {
                    LecturePage p = pages.get(i);
                    if (!p.strokes.isEmpty()) {
                        File png = new File(drawings, base + "_p" + i + ".png");
                        if (DrawingView.renderStrokes(p.strokes, canvasW, canvasH, png)
                                && firstImage == null) {
                            firstImage = png.getAbsolutePath();
                        }
                    }
                    String text = p.text == null ? "" : p.text.trim();
                    if (!text.isEmpty()) {
                        if (allText.length() > 0) {
                            allText.append("\n\n");
                        }
                        allText.append(text);
                    }
                }
                note.drawingPath = firstImage;
                note.content = allText.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
        }
        return note;
    }

    private void save() {
        Note note = buildNote();
        if (note == null) {
            Toast.makeText(requireContext(), R.string.canvas_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        SedayarApp.get().repository().save(note, id -> {
            if (!isAdded() || binding == null) {
                return;
            }
            resetNotebook();
            Snackbar.make(binding.getRoot(), R.string.lecture_saved,
                    Snackbar.LENGTH_LONG).show();
        });
    }

    /** Saves silently when the view is being destroyed mid-recording. */
    private void autoSaveOnExit() {
        if (binding == null) {
            return;
        }
        Note note = buildNote();
        if (note != null) {
            SedayarApp.get().repository().save(note, id -> { });
        }
    }

    private void resetNotebook() {
        pages.clear();
        pages.add(new LecturePage());
        pageIndex = 0;
        binding.drawingView.clearAll();
        binding.etTyped.setText("");
        if (textMode) {
            setTextMode(false, false);
        }
        updatePageLabel();
    }

    // -------------------------------------------------------------- painting

    private void highlightPens(View[] pens, int selected) {
        for (int i = 0; i < pens.length; i++) {
            GradientDrawable d = (GradientDrawable) pens[i].getBackground();
            d.setStroke(dp(2), ContextCompat.getColor(requireContext(),
                    i == selected ? R.color.primary : R.color.stroke));
        }
        binding.drawingView.setEraseMode(false);
        binding.btnErase.setIconTint(ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.text_secondary)));
    }

    private void highlightWidths(View[] widths, int selected) {
        for (int i = 0; i < widths.length; i++) {
            GradientDrawable d = (GradientDrawable) widths[i].getBackground();
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
            // Keep recording through tab switches / screen lock — a class does
            // not stop when the screen turns off. The note is auto-saved when
            // the view is finally destroyed.
            Toast.makeText(requireContext(), R.string.rec_continue, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recTimer.removeCallbacks(recTick);
        // Leaving this tab mid-lecture: stop audio and persist everything
        // (pages + strokes + text + audio + timings) so nothing is lost.
        autoSaveOnExit();
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
        recording = false;
        pages.clear();
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
