package com.sedayar.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.audio.Transcript;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.ActivityNoteEditorBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.InsetsUtil;
import com.sedayar.app.util.NoteColors;
import com.sedayar.app.util.SpeechEngine;

import java.io.File;

/**
 * The note editor: rich text + live Google speech-to-text + drawing canvas.
 * Auto-saves on every pause, so the user never loses a note.
 */
public class NoteEditorActivity extends AppCompatActivity {

    public static final String EXTRA_NOTE_ID = "note_id";
    private static final int REQ_MIC = 1001;

    private ActivityNoteEditorBinding binding;
    private Note current;
    private boolean deleted = false;

    private SpeechEngine engine;
    private String speechLang;

    // audio player for voice-dictation notes (audio saved with the note)
    private MediaPlayer audioPlayer;
    private final Handler audioTick = new Handler(Looper.getMainLooper());
    private boolean audioPrepared = false;
    private boolean audioDragging = false;

    private ObjectAnimator pulseX, pulseY;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNoteEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.apply(binding.getRoot(), null);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        speechLang = AppPrefs.speechLang(this);
        updateLangButton();

        long noteId = getIntent().getLongExtra(EXTRA_NOTE_ID, -1);
        boolean editing = noteId > 0;
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(editing ? R.string.edit_note : R.string.new_note);
        }

        if (editing) {
            current = new Note();
            SedayarApp.get().repository().getById(noteId, loaded -> {
                if (loaded == null || isFinishing() || isDestroyed()) {
                    if (loaded == null) {
                        finish();
                    }
                    return;
                }
                current = loaded;
                binding.etTitle.setText(loaded.title);
                binding.etContent.setText(loaded.content);
                binding.etContent.setSelection(binding.etContent.getText().length());
                binding.drawingView.loadBase(
                        loaded.hasDrawing() && new File(loaded.drawingPath).exists()
                                ? loaded.drawingPath
                                : null);
                setupAudioPlayer(loaded);
            });
        } else {
            current = new Note();
            current.createdAt = System.currentTimeMillis();
        }

        // Text <-> drawing mode switch
        binding.toggleMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            boolean draw = checkedId == R.id.btn_mode_draw;
            binding.textArea.setVisibility(draw ? View.GONE : View.VISIBLE);
            binding.drawingArea.setVisibility(draw ? View.VISIBLE : View.GONE);
        });
        binding.toggleMode.check(R.id.btn_mode_text);

        setupDrawingToolbar();
        setupSpeech();

        binding.btnMic.setOnClickListener(v -> onMicClicked());
        binding.btnLang.setOnClickListener(v -> {
            speechLang = (speechLang != null && speechLang.startsWith("fa")) ? "en-US" : "fa-IR";
            AppPrefs.setSpeechLang(this, speechLang);
            engine.setLanguage(speechLang);
            updateLangButton();
            Toast.makeText(this,
                    getString(R.string.lang_changed) + ": "
                            + (speechLang.startsWith("fa") ? "فارسی" : "English"),
                    Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------- drawing toolbar

    private void setupDrawingToolbar() {
        final int[] penColors = {0xFF1F2937, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFFD97706};
        final View[] pens = {binding.pen0, binding.pen1, binding.pen2, binding.pen3, binding.pen4};
        for (int i = 0; i < pens.length; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(penColors[i]);
            d.setStroke(dp(2), ContextCompat.getColor(this, R.color.stroke));
            pens[i].setBackground(d);
            final int idx = i;
            pens[i].setOnClickListener(v -> {
                binding.drawingView.setPenColor(penColors[idx]);
                highlightPens(pens, idx);
            });
        }
        highlightPens(pens, 0);

        final View[] widths = {binding.width1, binding.width2, binding.width3};
        final float[] widthPx = {dp(3), dp(6), dp(10)};
        for (int i = 0; i < widths.length; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(ContextCompat.getColor(this, R.color.text_secondary));
            widths[i].setBackground(d);
            final int idx = i;
            widths[i].setOnClickListener(v -> {
                binding.drawingView.setStrokeWidth(widthPx[idx]);
                highlightWidths(widths, idx);
            });
        }
        highlightWidths(widths, 1);
        binding.drawingView.setStrokeWidth(dp(6));

        binding.drawingView.setStrokesChangedListener((canUndo, canRedo) -> {
            binding.btnUndo.setEnabled(canUndo);
            binding.btnRedo.setEnabled(canRedo);
        });

        binding.btnErase.setOnClickListener(v -> {
            boolean on = !binding.drawingView.isEraseMode();
            binding.drawingView.setEraseMode(on);
            binding.btnErase.setIconTint(ColorStateList.valueOf(
                    ContextCompat.getColor(this, on ? R.color.primary : R.color.text_secondary)));
        });
        binding.btnUndo.setOnClickListener(v -> binding.drawingView.undo());
        binding.btnRedo.setOnClickListener(v -> binding.drawingView.redo());
        binding.btnClear.setOnClickListener(v -> binding.drawingView.clearAll());
    }

    private void highlightPens(View[] pens, int selected) {
        for (int i = 0; i < pens.length; i++) {
            GradientDrawable d = (GradientDrawable) pens[i].getBackground();
            d.setStroke(dp(2), ContextCompat.getColor(this,
                    i == selected ? R.color.primary : R.color.stroke));
        }
        binding.drawingView.setEraseMode(false);
        binding.btnErase.setIconTint(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.text_secondary)));
    }

    private void highlightWidths(View[] widths, int selected) {
        for (int i = 0; i < widths.length; i++) {
            GradientDrawable d = (GradientDrawable) widths[i].getBackground();
            d.setColor(ContextCompat.getColor(this,
                    i == selected ? R.color.primary : R.color.text_secondary));
        }
    }

    // ---------------------------------------------------------------- speech

    private void setupSpeech() {
        engine = new SpeechEngine(this, speechLang, new SpeechEngine.Listener() {
            @Override
            public void onListeningChanged(boolean listening) {
                setListeningVisual(listening);
            }

            @Override
            public void onPartialText(String text) {
                binding.tvLive.setVisibility(View.VISIBLE);
                binding.tvLive.setText(text);
            }

            @Override
            public void onFinalText(String text) {
                insertText(text);
            }

            @Override
            public void onError(String message) {
                Toast.makeText(NoteEditorActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
        if (!engine.isAvailable()) {
            binding.btnMic.setEnabled(false);
            binding.btnMic.setText(R.string.speech_unavailable);
        }
    }

    private void onMicClicked() {
        if (engine.isListening()) {
            engine.stop();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!engine.isAvailable()) {
            Toast.makeText(this, R.string.speech_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        engine.start();
    }

    private void setListeningVisual(boolean on) {
        binding.btnMic.setText(on ? R.string.listening : R.string.say_something);
        binding.btnMic.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, on ? R.color.mic_active : R.color.primary)));
        if (on) {
            pulseX = ObjectAnimator.ofPropertyValuesHolder(binding.btnMic,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f));
            pulseY = ObjectAnimator.ofPropertyValuesHolder(binding.btnMic,
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f));
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
            binding.tvLive.setText("");
        }
    }

    private void insertText(String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) {
            return;
        }
        String text = binding.etContent.getText().toString();
        String add = spoken.trim();
        if (!text.isEmpty() && !text.endsWith(" ") && !text.endsWith("\n")) {
            add = " " + add;
        }
        binding.etContent.append(add + " ");
    }

    private void updateLangButton() {
        binding.btnLang.setText(
                (speechLang != null && speechLang.startsWith("fa"))
                        ? R.string.lang_fa
                        : R.string.lang_en);
    }

    // ------------------------------------------------------------ audio player

    /** Small inline player for notes that carry a voice recording (dictation). */
    private void setupAudioPlayer(Note note) {
        if (binding == null || !note.hasAudio()
                || !(new File(note.audioPath).exists())) {
            return;
        }
        binding.cardAudio.setVisibility(View.VISIBLE);
        try {
            audioPlayer = new MediaPlayer();
            audioPlayer.setDataSource(note.audioPath);
            audioPlayer.setOnPreparedListener(mp -> {
                audioPrepared = true;
                binding.seekAudio.setMax(mp.getDuration());
                binding.tvAudioTime.setText(Transcript.shortTime(0));
            });
            audioPlayer.setOnCompletionListener(mp -> {
                binding.btnAudioPlay.setIconResource(R.drawable.ic_play);
                binding.seekAudio.setProgress(0);
                binding.tvAudioTime.setText(Transcript.shortTime(0));
            });
            audioPlayer.prepareAsync();
        } catch (Exception e) {
            binding.cardAudio.setVisibility(View.GONE);
            return;
        }

        binding.btnAudioPlay.setOnClickListener(v -> {
            if (!audioPrepared || audioPlayer == null) {
                return;
            }
            if (audioPlayer.isPlaying()) {
                audioPlayer.pause();
                binding.btnAudioPlay.setIconResource(R.drawable.ic_play);
            } else {
                audioPlayer.start();
                binding.btnAudioPlay.setIconResource(R.drawable.ic_pause);
                audioTick.post(audioUiTick);
            }
        });
        binding.seekAudio.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    binding.tvAudioTime.setText(Transcript.shortTime(value));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                audioDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                audioDragging = false;
                if (audioPrepared && audioPlayer != null) {
                    audioPlayer.seekTo(bar.getProgress());
                }
            }
        });
    }

    private final Runnable audioUiTick = new Runnable() {
        @Override
        public void run() {
            if (audioPlayer != null && audioPrepared && !audioDragging
                    && binding != null) {
                int pos = audioPlayer.getCurrentPosition();
                binding.seekAudio.setProgress(pos);
                binding.tvAudioTime.setText(Transcript.shortTime(pos));
            }
            if (audioPlayer != null && audioPlayer.isPlaying() && binding != null) {
                audioTick.postDelayed(this, 400);
            }
        }
    };

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null && engine.isListening()) {
            engine.stop();
        }
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioTick.removeCallbacks(audioUiTick);
        if (audioPlayer != null) {
            try {
                audioPlayer.release();
            } catch (Exception ignored) {
            }
            audioPlayer = null;
        }
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                engine.start();
            } else {
                Toast.makeText(this, R.string.mic_rationale, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (current == null || deleted) {
            return;
        }

        String title = binding.etTitle.getText().toString().trim();
        String content = binding.etContent.getText().toString();
        boolean hasText = !title.isEmpty() || !content.trim().isEmpty();
        boolean hasDrawing = !binding.drawingView.isEmpty();
        if (current.id == 0 && !hasText && !hasDrawing) {
            return; // never persist an empty note
        }

        current.title = title;
        current.content = content;

        File drawings = new File(getFilesDir(), "drawings");
        if (!drawings.exists()) {
            drawings.mkdirs();
        }

        if (hasDrawing) {
            String name = "note_" + (current.id > 0
                    ? String.valueOf(current.id)
                    : "tmp_" + System.currentTimeMillis()) + ".png";
            File file = new File(drawings, name);
            if (binding.drawingView.saveTo(file)) {
                if (current.drawingPath != null
                        && !current.drawingPath.equals(file.getAbsolutePath())) {
                    new File(current.drawingPath).delete();
                }
                current.drawingPath = file.getAbsolutePath();
            }
        } else if (current.drawingPath != null && new File(current.drawingPath).exists()) {
            // The user erased the whole canvas of a note that had a drawing
            new File(current.drawingPath).delete();
            current.drawingPath = null;
        }

        SedayarApp.get().repository().save(current, id -> current.id = id);
    }

    // ------------------------------------------------------------- actions

    private void shareNote() {
        save();
        String title = binding.etTitle.getText().toString().trim();
        String content = binding.etContent.getText().toString().trim();
        boolean hasImage = current != null && current.hasDrawing()
                && new File(current.drawingPath).exists();
        if (title.isEmpty() && content.isEmpty() && !hasImage) {
            Toast.makeText(this, R.string.share_text_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        String text = (title.isEmpty() ? "" : title + "\n\n") + content;
        Intent send = new Intent(Intent.ACTION_SEND);
        if (hasImage) {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files",
                    new File(current.drawingPath));
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_TEXT, text.trim());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, text.trim());
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)));
    }

    private void showColorPicker() {
        if (current == null) {
            return;
        }
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER);
        row.setPadding(dp(24), dp(16), dp(24), dp(8));

        for (int i = 0; i < NoteColors.count(); i++) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(NoteColors.color(i));
            d.setStroke(dp(2), ContextCompat.getColor(this, R.color.stroke));
            dot.setBackground(d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(dp(6), 0, dp(6), 0);
            dot.setLayoutParams(lp);
            final int idx = i;
            dot.setOnClickListener(v -> {
                current.colorIndex = idx;
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                save();
                Snackbar.make(binding.getRoot(), R.string.color_saved,
                        Snackbar.LENGTH_SHORT).show();
            });
            row.addView(dot);
        }

        holder[0] = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.color)
                .setView(row)
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_msg)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    deleted = true;
                    if (current != null) {
                        if (current.hasDrawing()) {
                            new File(current.drawingPath).delete();
                        }
                        SedayarApp.get().repository().delete(current, null);
                    }
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ----------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_editor, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem offline = menu.findItem(R.id.action_offline);
        if (offline != null) {
            offline.setChecked(AppPrefs.offlineSpeechEnabled(this));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_save) {
            save();
            Snackbar.make(binding.getRoot(), R.string.saved, Snackbar.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_share) {
            shareNote();
            return true;
        }
        if (id == R.id.action_color) {
            showColorPicker();
            return true;
        }
        if (id == R.id.action_offline) {
            boolean on = !item.isChecked();
            item.setChecked(on);
            AppPrefs.setOfflineSpeech(this, on);
            Snackbar.make(binding.getRoot(),
                    on ? R.string.offline_on_toast : R.string.offline_off_toast,
                    Snackbar.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_delete) {
            confirmDelete();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
