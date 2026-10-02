package com.sedayar.app.fragment;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.NoteEditorActivity;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.audio.LiveTranscriber;
import com.sedayar.app.audio.VoskTranscriber;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentLiveVoiceBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live voice tab — ONE microphone owner ({@link LiveTranscriber}):
 * the whole session is captured as one continuous 16 kHz WAV while the same
 * audio streams through the offline Persian Vosk engine, so live text and
 * the audio file can never fight over the mic again (the old choppy-recording
 * bug). Segments carry exact [mm:ss] stamps measured from the PCM stream.
 */
public class LiveVoiceFragment extends Fragment implements LiveTranscriber.Listener {

    private static final int REQ_MIC = 2001;

    private FragmentLiveVoiceBinding binding;
    private LiveTranscriber session;

    private boolean sessionActive = false;
    private boolean modelDownloading = false;

    private final List<com.sedayar.app.audio.Transcript.Segment> segments = new ArrayList<>();
    private ObjectAnimator pulseX, pulseY;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (sessionActive && binding != null && session != null) {
                long s = session.durationMs() / 1000;
                binding.tvTimer.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
                timerHandler.postDelayed(this, 500);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLiveVoiceBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        session = new LiveTranscriber(requireContext(), this);

        binding.btnMic.setOnClickListener(v -> onMicClicked());
        // the offline Persian engine replaces the old fa/en switch here:
        // one model, no Google servers, no language toggle to break things
        binding.btnLang.setVisibility(View.GONE);
        binding.tvEngineNote.setVisibility(View.VISIBLE);
        refreshEngineNote();

        binding.btnClear.setOnClickListener(v -> {
            segments.clear();
            refreshTranscriptView();
        });

        binding.btnSave.setOnClickListener(v -> confirmSave());
    }

    private void refreshEngineNote() {
        boolean ready = VoskTranscriber.isModelDirValid(
                VoskTranscriber.modelDir(requireContext()));
        binding.tvEngineNote.setText(ready
                ? R.string.live_engine_note : R.string.live_engine_need_model);
    }

    // ------------------------------------------------------------------ mic

    private void onMicClicked() {
        if (sessionActive) {
            finishSession(false); // stop mic + engine, keep everything for saving
            return;
        }
        if (modelDownloading) {
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                    REQ_MIC);
            return;
        }
        if (VoskTranscriber.isModelDirValid(VoskTranscriber.modelDir(requireContext()))) {
            startSession();
        } else {
            offerModelDownload();
        }
    }

    /** Model missing: download now (full experience) or record audio-only. */
    private void offerModelDownload() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.audio_no_model)
                .setMessage(R.string.live_no_model_msg)
                .setPositiveButton(R.string.vosk_download, (d, w) -> downloadModelThenStart())
                .setNeutralButton(R.string.live_audio_only, (d, w) -> startSession())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void downloadModelThenStart() {
        modelDownloading = true;
        ProgressBar bar = new ProgressBar(requireContext(), null,
                android.R.attr.progressBarStyleHorizontal);
        FrameLayout box = new FrameLayout(requireContext());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        android.app.AlertDialog progress = new android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.vosk_download)
                .setMessage(R.string.vosk_size_hint)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> modelDownloading = false)
                .create();
        progress.show();

        VoskTranscriber.startModelDownload(requireContext(),
                new VoskTranscriber.ModelDownloadListener() {
                    @Override
                    public void onProgress(int percent) {
                        if (isAdded() && bar.isAttachedToWindow()) {
                            bar.setProgress(percent);
                        }
                    }

                    @Override
                    public void onSuccess(File modelDir) {
                        modelDownloading = false;
                        if (progress.isShowing()) {
                            progress.dismiss();
                        }
                        if (!isAdded()) {
                            return;
                        }
                        Toast.makeText(requireContext(), R.string.vosk_install_done,
                                Toast.LENGTH_SHORT).show();
                        refreshEngineNote();
                        startSession();
                    }

                    @Override
                    public void onError(String message) {
                        modelDownloading = false;
                        if (progress.isShowing()) {
                            progress.dismiss();
                        }
                        if (!isAdded()) {
                            return;
                        }
                        Toast.makeText(requireContext(),
                                getString(R.string.vosk_download_failed) + " (" + message + ")",
                                Toast.LENGTH_LONG).show();
                        // still possible: audio-only session
                        startSession();
                    }
                });
    }

    /** Opens a fresh session: one continuous WAV + live Persian text on top. */
    private void startSession() {
        segments.clear();
        refreshTranscriptView();
        binding.tvLive.setVisibility(View.GONE);

        File dir = new File(requireContext().getFilesDir(), "dictation");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, "dictation_" + System.currentTimeMillis() + ".wav");

        boolean opened = session.start(out);
        if (!opened) {
            Toast.makeText(requireContext(), R.string.live_record_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }

        sessionActive = true;
        binding.tvStatus.setText(R.string.live_status_preparing);
        binding.tvEngineNote.setVisibility(View.GONE);
        timerHandler.post(timerTick);
    }

    private void finishSession(boolean silent) {
        File f = session.stop();
        sessionActive = false;
        timerHandler.removeCallbacks(timerTick);
        // pick up any tail segment the engine flushed while stopping
        syncFromSession();
        refreshEngineNote();
        binding.tvEngineNote.setVisibility(View.VISIBLE);
        if (binding != null) {
            binding.tvStatus.setText(R.string.tap_mic_start);
        }
        if (!silent && isAdded() && segments.isEmpty()
                && (f == null || !f.exists() || f.length() <= 44)) {
            Toast.makeText(requireContext(), R.string.nothing_recorded,
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------- LiveTranscriber events

    @Override
    public void onEngineReady(boolean textEnabled) {
        if (binding == null || !isAdded()) {
            return;
        }
        if (sessionActive) {
            binding.tvStatus.setText(textEnabled
                    ? R.string.listening : R.string.live_audio_only_note);
        }
    }

    @Override
    public void onEngineError(String message) {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.tvLive.setVisibility(View.GONE);
        // audio keeps rolling — nothing is lost
        binding.tvStatus.setText(R.string.live_engine_error);
        Toast.makeText(requireContext(), engineMessage(message),
                Toast.LENGTH_SHORT).show();
    }

    private String engineMessage(String key) {
        if ("record_failed".equals(key)) {
            return getString(R.string.live_record_failed);
        }
        return getString(R.string.live_engine_error);
    }

    @Override
    public void onPartial(String text) {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.tvLive.setVisibility(View.VISIBLE);
        binding.tvLive.setText(text);
        binding.tvStatus.setText(R.string.listening);
    }

    @Override
    public void onSegment(long startMs, long endMs, String text) {
        if (binding == null || !isAdded()) {
            return;
        }
        addSegment(new com.sedayar.app.audio.Transcript.Segment(startMs, endMs, text));
        binding.tvLive.setVisibility(View.GONE);
        binding.tvStatus.setText(R.string.listening);
    }

    // ----------------------------------------------------------------- save

    private void addSegment(com.sedayar.app.audio.Transcript.Segment s) {
        String add = s.text == null ? "" : s.text.trim();
        if (add.isEmpty()) {
            return;
        }
        for (com.sedayar.app.audio.Transcript.Segment existing : segments) {
            if (existing.text.equals(add) && existing.startMs == s.startMs) {
                return; // duplicate tail-flush guard
            }
        }
        segments.add(new com.sedayar.app.audio.Transcript.Segment(
                s.startMs, s.endMs, add));
        refreshTranscriptView();
    }

    /** Pull in anything the engine finalized that the UI has not seen yet. */
    private void syncFromSession() {
        if (session == null) {
            return;
        }
        segments.clear();
        for (com.sedayar.app.audio.Transcript.Segment s : session.segmentsSnapshot()) {
            if (!s.text.trim().isEmpty()) {
                segments.add(s);
            }
        }
        refreshTranscriptView();
    }

    private void refreshTranscriptView() {
        if (segments.isEmpty()) {
            binding.tvTranscript.setText(R.string.nothing_recorded);
            binding.tvTranscript.setTextColor(ContextCompat
                    .getColor(requireContext(), R.color.text_secondary));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            com.sedayar.app.audio.Transcript.Segment s = segments.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append('[').append(TranscriptTime.shortLabel(s.startMs))
                    .append("] ").append(s.text);
        }
        binding.tvTranscript.setText(sb.toString());
        binding.tvTranscript.setTextColor(ContextCompat
                .getColor(requireContext(), R.color.text_primary));
    }

    /** Builds a note from the current session (title suggested from the text). */
    private Note buildNoteFromSession() {
        if (session != null) {
            syncFromSession();
        }
        StringBuilder bodySb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                bodySb.append('\n');
            }
            bodySb.append('[').append(TranscriptTime.shortLabel(segments.get(i).startMs))
                    .append("] ").append(segments.get(i).text);
        }
        final String body = bodySb.toString().trim();
        final File audio = session == null ? null : session.file();
        final boolean hasAudio = audio != null && audio.exists() && audio.length() > 44;
        if (body.isEmpty() && !hasAudio) {
            return null;
        }
        Note note = new Note();
        note.createdAt = System.currentTimeMillis();
        String plain = body.replaceAll("\\[[0-9:]+\\] ", "");
        String[] words = plain.isEmpty() ? new String[]{getString(R.string.app_name)}
                : plain.split("\\s+");
        StringBuilder suggested = new StringBuilder();
        for (int i = 0; i < Math.min(6, words.length); i++) {
            if (i > 0) {
                suggested.append(' ');
            }
            suggested.append(words[i]);
        }
        note.title = suggested.toString();
        note.content = body;
        if (hasAudio) {
            note.audioPath = audio.getAbsolutePath();
        }
        return note;
    }

    private void confirmSave() {
        // stop everything first so the WAV header is finalized
        if (sessionActive) {
            finishSession(true);
        }
        final Note note = buildNoteFromSession();
        if (note == null) {
            Toast.makeText(requireContext(), R.string.nothing_recorded,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(R.string.hint_title);
        input.setTextSize(16f);
        input.setPadding(dp(20), dp(16), dp(20), dp(8));
        input.setText(note.title);
        input.setSelection(note.title.length());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.save_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    note.title = input.getText().toString().trim();
                    if (note.title.isEmpty()) {
                        note.title = getString(R.string.untitled);
                    }
                    SedayarApp.get().repository().save(note, this::transcriptSaved);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void transcriptSaved(Long id) {
        if (!isAdded() || binding == null) {
            return;
        }
        Snackbar.make(binding.getRoot(), R.string.saved_snack,
                Snackbar.LENGTH_LONG)
                .setAction(R.string.open_action, v -> {
                    Intent it = new Intent(requireContext(),
                            NoteEditorActivity.class);
                    it.putExtra(NoteEditorActivity.EXTRA_NOTE_ID,
                            id == null ? -1L : id);
                    startActivity(it);
                })
                .show();
        segments.clear();
        refreshTranscriptView();
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onMicClicked();
            } else {
                Toast.makeText(requireContext(), R.string.mic_rationale,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // keep the continuous audio + text rolling through tab switches /
        // screen off, exactly like the class mode — auto-saved if the view dies
        if (sessionActive) {
            Toast.makeText(requireContext(), R.string.rec_continue, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timerHandler.removeCallbacks(timerTick);
        // session still open when leaving the tab: save voice + text silently
        if (sessionActive) {
            session.stop();
            sessionActive = false;
            Note note = buildNoteFromSession();
            if (note != null) {
                SedayarApp.get().repository().save(note, id -> {
                });
            }
        }
        session = null;
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static final class TranscriptTime {
        static String shortLabel(long ms) {
            long total = Math.max(0, ms) / 1000;
            return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
        }
    }
}
