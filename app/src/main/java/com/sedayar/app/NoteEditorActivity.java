package com.sedayar.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
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
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.ActivityNoteEditorBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.NoteColors;

import java.io.File;
import java.util.ArrayList;

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

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private String speechLang;

    private ObjectAnimator pulseX, pulseY;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNoteEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
            binding.btnErase.setIconTintList(ColorStateList.valueOf(
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
        binding.btnErase.setIconTintList(ColorStateList.valueOf(
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.btnMic.setEnabled(false);
            binding.btnMic.setText(R.string.speech_unavailable);
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                setListening(true);
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                setListening(false);
                binding.tvLive.setVisibility(View.GONE);
                binding.tvLive.setText("");
                Toast.makeText(NoteEditorActivity.this,
                        speechErrorMessage(error), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onResults(Bundle results) {
                setListening(false);
                binding.tvLive.setVisibility(View.GONE);
                binding.tvLive.setText("");
                ArrayList<String> list =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && list.get(0) != null) {
                    insertText(list.get(0));
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && list.get(0) != null) {
                    binding.tvLive.setVisibility(View.VISIBLE);
                    binding.tvLive.setText(
                            getString(R.string.listening) + " — " + list.get(0));
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void onMicClicked() {
        if (recognizer == null) {
            Toast.makeText(this, R.string.speech_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (listening) {
            recognizer.stopListening();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startRecognition();
    }

    private void startRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLang);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, speechLang);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (AppPrefs.offlineSpeechEnabled(this)) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.speech_error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private String speechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_SERVER:
                return getString(R.string.speech_error_network);
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return getString(R.string.speech_error_no_match);
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return getString(R.string.speech_error_busy);
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return getString(R.string.mic_rationale);
            default:
                return getString(R.string.speech_error_generic);
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

    private void setListening(boolean on) {
        listening = on;
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
        }
    }

    private void updateLangButton() {
        binding.btnLang.setText(
                (speechLang != null && speechLang.startsWith("fa"))
                        ? R.string.lang_fa
                        : R.string.lang_en);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        if (listening && recognizer != null) {
            recognizer.stopListening();
            setListening(false);
        }
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecognition();
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
