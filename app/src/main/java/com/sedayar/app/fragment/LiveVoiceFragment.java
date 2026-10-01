package com.sedayar.app.fragment;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.NoteEditorActivity;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.audio.Transcript;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentLiveVoiceBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.SpeechEngine;

import java.io.File;
import java.util.Locale;

/**
 * Live voice tab — classroom-proof dictation: the whole session is captured
 * as ONE continuous audio file (m4a) while the recognizer keeps re-arming
 * itself through every silent pause, so nothing is chopped into pieces. The
 * transcript segments are stamped with their audio moment ([mm:ss]) and the
 * note stores voice + text together.
 */
public class LiveVoiceFragment extends Fragment implements SpeechEngine.Listener {

    private static final int REQ_MIC = 2001;
    private static final String TAG = "LiveVoice";

    private FragmentLiveVoiceBinding binding;
    private SpeechEngine engine;
    private String speechLang;

    private MediaRecorder recorder;
    private File audioFile;
    private boolean audioOk = false;
    private boolean sessionActive = false;
    private long recordStart = 0;

    private final StringBuilder transcript = new StringBuilder();
    private ObjectAnimator pulseX, pulseY;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (sessionActive && binding != null) {
                long s = (SystemClock.elapsedRealtime() - recordStart) / 1000;
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

        speechLang = AppPrefs.speechLang(requireContext());
        updateLangButton();

        engine = new SpeechEngine(requireContext(), speechLang, this, true);

        binding.btnMic.setOnClickListener(v -> onMicClicked());
        binding.btnLang.setOnClickListener(v -> {
            speechLang = (speechLang != null && speechLang.startsWith("fa")) ? "en-US" : "fa-IR";
            AppPrefs.setSpeechLang(requireContext(), speechLang);
            engine.setLanguage(speechLang);
            updateLangButton();
            Toast.makeText(requireContext(),
                    getString(R.string.lang_changed) + ": "
                            + (speechLang.startsWith("fa") ? "فارسی" : "English"),
                    Toast.LENGTH_SHORT).show();
        });

        binding.btnClear.setOnClickListener(v -> {
            transcript.setLength(0);
            refreshTranscriptView();
        });

        binding.btnSave.setOnClickListener(v -> confirmSave());
    }

    // ------------------------------------------------------------------ mic

    private void onMicClicked() {
        if (sessionActive) {
            finishSession(false); // stop mic + audio, keep everything for saving
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!engine.isAvailable()) {
            Toast.makeText(requireContext(), R.string.speech_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        startSession();
    }

    /** Opens a fresh session: one continuous m4a + a self-re-arming recognizer. */
    private void startSession() {
        transcript.setLength(0);
        refreshTranscriptView();

        // 1) the continuous audio file — one piece, no gaps
        audioOk = false;
        try {
            File dir = new File(requireContext().getFilesDir(), "dictation");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            audioFile = new File(dir, "dictation_" + System.currentTimeMillis() + ".m4a");
            recorder = buildRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            audioOk = true;
        } catch (Exception e) {
            Log.e(TAG, "continuous recorder failed; text-only mode", e);
            recorder = null;
            audioFile = null;
        }

        // 2) live transcript on top of it
        sessionActive = true;
        recordStart = SystemClock.elapsedRealtime();
        timerHandler.post(timerTick);
        engine.start();

        if (audioOk) {
            Toast.makeText(requireContext(), R.string.live_recording_toast,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Stops engine + audio. When autoSaving, the session was not user-stopped. */
    private void finishSession(boolean silent) {
        engine.stop();
        stopAudio();
        sessionActive = false;
        timerHandler.removeCallbacks(timerTick);
        if (binding != null) {
            binding.tvStatus.setText(R.string.tap_mic_start);
        }
        if (!silent && isAdded() && transcript.length() == 0 && !audioOk) {
            Toast.makeText(requireContext(), R.string.nothing_recorded,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAudio() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception e) {
                if (audioFile != null) {
                    audioFile.delete();
                }
                audioFile = null;
                audioOk = false;
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    @SuppressWarnings("deprecation")
    private MediaRecorder buildRecorder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new MediaRecorder(requireContext());
        }
        return new MediaRecorder();
    }

    // ----------------------------------------------------- SpeechEngine.Listener

    @Override
    public void onListeningChanged(boolean listening) {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.btnMic.setText(listening ? R.string.stop_listening : R.string.say_something);
        binding.btnMic.setBackgroundTintList(ColorStateList.valueOf(ContextCompat
                .getColor(requireContext(), listening ? R.color.mic_active : R.color.primary)));
        if (listening) {
            pulseX = ObjectAnimator.ofPropertyValuesHolder(binding.btnMic,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f));
            pulseY = ObjectAnimator.ofPropertyValuesHolder(binding.btnMic,
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f));
            for (ObjectAnimator a : new ObjectAnimator[]{pulseX, pulseY}) {
                a.setDuration(500);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.start();
            }
        } else {
            if (pulseX != null) {
                pulseX.cancel();
            }
            if (pulseY != null) {
                pulseY.cancel();
            }
            binding.btnMic.setScaleX(1f);
            binding.btnMic.setScaleY(1f);
            binding.tvLive.setVisibility(View.GONE);
        }
    }

    @Override
    public void onPartialText(String text) {
        if (binding == null || !isAdded()) {
            return;
        }
        binding.tvLive.setVisibility(View.VISIBLE);
        binding.tvLive.setText(text);
        binding.tvStatus.setText(R.string.listening);
    }

    @Override
    public void onFinalText(String text) {
        if (binding == null || !isAdded()) {
            return;
        }
        appendTranscript(text);
        binding.tvLive.setVisibility(View.GONE);
        binding.tvStatus.setText(R.string.listening);
        if (sessionActive) {
            // keep the session going until the user stops it
            engine.start();
        }
    }

    @Override
    public void onError(String message) {
        if (binding == null || !isAdded()) {
            return;
        }
        // fatal engine error: recognition pauses but the audio file keeps
        // rolling — the user can tap the mic to resume the transcript.
        binding.tvLive.setVisibility(View.GONE);
        if (sessionActive) {
            binding.tvStatus.setText(R.string.live_text_paused);
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        } else {
            binding.tvStatus.setText(R.string.tap_mic_start);
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------------- save

    private void appendTranscript(String text) {
        String add = text == null ? "" : text.trim();
        if (add.isEmpty()) {
            return;
        }
        if (audioOk) {
            long s = (SystemClock.elapsedRealtime() - recordStart) / 1000;
            if (transcript.length() > 0) {
                transcript.append('\n');
            }
            transcript.append('[')
                    .append(String.format(Locale.US, "%02d:%02d", s / 60, s % 60))
                    .append("] ").append(add);
        } else {
            if (transcript.length() > 0) {
                transcript.append(' ');
            }
            transcript.append(add);
        }
        refreshTranscriptView();
    }

    private void refreshTranscriptView() {
        if (transcript.length() == 0) {
            binding.tvTranscript.setText(R.string.nothing_recorded);
            binding.tvTranscript.setTextColor(ContextCompat
                    .getColor(requireContext(), R.color.text_secondary));
        } else {
            binding.tvTranscript.setText(transcript.toString());
            binding.tvTranscript.setTextColor(ContextCompat
                    .getColor(requireContext(), R.color.text_primary));
        }
    }

    /** Builds a note from the current session (title suggested from the text). */
    private Note buildNoteFromSession() {
        final String body = transcript.toString().trim();
        if (body.isEmpty() && !audioOk) {
            return null;
        }
        Note note = new Note();
        note.createdAt = System.currentTimeMillis();
        String[] words = body.isEmpty() ? new String[]{getString(R.string.app_name)}
                : body.split("\\s+");
        StringBuilder suggested = new StringBuilder();
        for (int i = 0; i < Math.min(6, words.length); i++) {
            if (i > 0) {
                suggested.append(' ');
            }
            suggested.append(words[i]);
        }
        note.title = suggested.toString();
        note.content = body;
        if (audioOk && audioFile != null && audioFile.exists()) {
            note.audioPath = audioFile.getAbsolutePath();
        }
        return note;
    }

    private void confirmSave() {
        // stop everything first so the audio file is finalized
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

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.save_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    note.title = input.getText().toString().trim();
                    if (note.title.isEmpty()) {
                        note.title = getString(R.string.untitled);
                    }
                    SedayarApp.get().repository().save(note, id -> {
                        if (!isAdded() || binding == null) {
                            return;
                        }
                        Snackbar.make(binding.getRoot(), R.string.saved_snack,
                                Snackbar.LENGTH_LONG)
                                .setAction(R.string.open_action, v -> {
                                    Intent it = new Intent(requireContext(),
                                            NoteEditorActivity.class);
                                    it.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, id);
                                    startActivity(it);
                                })
                                .show();
                        transcript.setLength(0);
                        refreshTranscriptView();
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateLangButton() {
        binding.btnLang.setText((speechLang != null && speechLang.startsWith("fa"))
                ? R.string.lang_fa : R.string.lang_en);
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
                startSession();
            } else {
                Toast.makeText(requireContext(), R.string.mic_rationale,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (engine != null && engine.isListening()) {
            engine.stop();
        }
        // keep the continuous audio rolling through tab switches / screen off,
        // exactly like the class mode — auto-saved if the view dies
        if (sessionActive) {
            Toast.makeText(requireContext(), R.string.rec_continue, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (sessionActive && engine != null && !engine.isListening()) {
            engine.start();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timerHandler.removeCallbacks(timerTick);
        // session still open when leaving the tab: save voice + text silently
        if (sessionActive) {
            engine.stop();
            stopAudio();
            sessionActive = false;
            Note note = buildNoteFromSession();
            if (note != null) {
                SedayarApp.get().repository().save(note, id -> { });
            }
        }
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
