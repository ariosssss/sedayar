package com.sedayar.app.fragment;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import com.sedayar.app.NoteEditorActivity;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentLiveVoiceBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.SpeechEngine;

import java.util.Locale;

/**
 * Live voice tab: a big pulsing mic that turns speech into text in real time.
 * The transcript can be saved as a regular note.
 */
public class LiveVoiceFragment extends Fragment implements SpeechEngine.Listener {

    private static final int REQ_MIC = 2001;

    private FragmentLiveVoiceBinding binding;
    private SpeechEngine engine;
    private String speechLang;

    private final StringBuilder transcript = new StringBuilder();
    private ObjectAnimator pulseX, pulseY;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private long recordStart = 0;
    private boolean recording = false;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (recording) {
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

        engine = new SpeechEngine(requireContext(), speechLang, this);

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
        if (recording) {
            engine.stop();
            stopSession();
            binding.tvStatus.setText(R.string.tap_mic_start);
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!engine.isAvailable()) {
            Toast.makeText(requireContext(), R.string.speech_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        startSession();
        engine.start();
    }

    private void startSession() {
        recording = true;
        recordStart = SystemClock.elapsedRealtime();
        timerHandler.post(timerTick);
    }

    private void stopSession() {
        recording = false;
        timerHandler.removeCallbacks(timerTick);
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
        if (recording) {
            // continuous dictation: keep the session going until the user stops it
            engine.start();
        } else {
            binding.tvStatus.setText(R.string.tap_mic_start);
        }
    }

    @Override
    public void onError(String message) {
        if (binding == null || !isAdded()) {
            return;
        }
        stopSession();
        binding.tvStatus.setText(R.string.tap_mic_start);
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------------- save

    private void appendTranscript(String text) {
        String add = text == null ? "" : text.trim();
        if (add.isEmpty()) {
            return;
        }
        if (transcript.length() > 0) {
            transcript.append(' ');
        }
        transcript.append(add);
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

    private void confirmSave() {
        final String body = transcript.toString().trim();
        if (body.isEmpty()) {
            Toast.makeText(requireContext(), R.string.nothing_recorded,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(R.string.hint_title);
        input.setTextSize(16f);
        input.setPadding(dp(20), dp(16), dp(20), dp(8));
        // suggest a title from the first words
        String[] words = body.split("\\s+");
        StringBuilder suggested = new StringBuilder();
        for (int i = 0; i < Math.min(6, words.length); i++) {
            if (i > 0) {
                suggested.append(' ');
            }
            suggested.append(words[i]);
        }
        input.setText(suggested.toString());
        input.setSelection(input.getText().length());

        holder[0] = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.save_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.save, (d, w) -> {
                    Note note = new Note();
                    note.createdAt = System.currentTimeMillis();
                    note.title = input.getText().toString().trim();
                    note.content = body;
                    SedayarApp.get().repository().save(note, id -> {
                        if (!isAdded()) {
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
                engine.start();
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
        stopSession();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
        timerHandler.removeCallbacks(timerTick);
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
