package com.sedayar.app.fragment;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import com.sedayar.app.R;
import com.sedayar.app.SedayarApp;
import com.sedayar.app.audio.ApiClient;
import com.sedayar.app.audio.AudioDecoder;
import com.sedayar.app.audio.Transcript;
import com.sedayar.app.audio.TranscriptExporter;
import com.sedayar.app.audio.VoskTranscriber;
import com.sedayar.app.data.Note;
import com.sedayar.app.databinding.FragmentAudioBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.Summarizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Audio file tab: pick any audio file (mp3/m4a/wav/ogg...) and turn it into
 * text with the offline Vosk engine or a Whisper-compatible API, then
 * summarize it and export the result as TXT / SRT / MD / HTML.
 */
public class AudioFragment extends Fragment {

    private FragmentAudioBinding binding;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private Uri fileUri;
    private String fileName = "";
    private Transcript transcript;
    private String summary = "";
    private boolean summaryIsAi = false;

    private final ActivityResultLauncher<String> picker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onFilePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAudioBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        boolean api = AppPrefs.useApiEngine(requireContext());
        binding.rbApi.setChecked(api);
        binding.rbVosk.setChecked(!api);

        binding.cardPick.setOnClickListener(v -> picker.launch("audio/*"));
        binding.btnStart.setOnClickListener(v -> startTranscription());
        binding.btnCancel.setOnClickListener(v -> cancelled.set(true));
        binding.btnSummary.setOnClickListener(v -> summarize());
        binding.btnExport.setOnClickListener(v -> showExportSheet());
        binding.btnSaveNote.setOnClickListener(v -> saveAsNote());

        binding.btnModelDownload.setOnClickListener(v -> downloadModel());
        binding.btnModelDelete.setOnClickListener(v -> confirmDeleteModel());
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshModelCard();
    }

    // ---------------------------------------------------- offline model card

    private void refreshModelCard() {
        if (binding == null || !isAdded()) {
            return;
        }
        boolean ready = AppPrefs.voskModelReady(requireContext());
        binding.tvModelStatus.setText(ready
                ? R.string.vosk_status_ready : R.string.vosk_status_missing);
        binding.tvModelStatus.setTextColor(ContextCompat.getColor(requireContext(),
                ready ? R.color.success : R.color.danger));
        boolean dirExists = VoskTranscriber.modelDir(requireContext()).exists();
        binding.btnModelDelete.setVisibility(dirExists || ready
                ? View.VISIBLE : View.GONE);
        binding.btnModelDownload.setEnabled(!VoskTranscriber.isDownloading(requireContext()));
    }

    private void downloadModel() {
        if (VoskTranscriber.isDownloading(requireContext())) {
            return;
        }
        binding.btnModelDownload.setEnabled(false);
        binding.progressModel.setVisibility(View.VISIBLE);
        binding.progressModel.setIndeterminate(false);
        binding.progressModel.setProgress(0);
        binding.tvModelStatus.setText(getString(R.string.vosk_downloading, 0));
        VoskTranscriber.startModelDownload(requireContext(),
                new VoskTranscriber.ModelDownloadListener() {
                    @Override
                    public void onProgress(int percent) {
                        if (binding == null || !isAdded()) {
                            return;
                        }
                        binding.progressModel.setProgress(percent);
                        binding.tvModelStatus.setText(
                                getString(R.string.vosk_downloading, percent));
                    }

                    @Override
                    public void onSuccess(File modelDir) {
                        if (binding == null || !isAdded()) {
                            return;
                        }
                        binding.progressModel.setVisibility(View.GONE);
                        Toast.makeText(requireContext(), R.string.vosk_install_done,
                                Toast.LENGTH_SHORT).show();
                        refreshModelCard();
                    }

                    @Override
                    public void onError(String message) {
                        if (binding == null || !isAdded()) {
                            return;
                        }
                        binding.progressModel.setVisibility(View.GONE);
                        Toast.makeText(requireContext(),
                                getString(R.string.vosk_download_failed) + " ("
                                        + message + ")", Toast.LENGTH_LONG).show();
                        refreshModelCard();
                    }
                });
    }

    private void confirmDeleteModel() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.vosk_delete)
                .setMessage(R.string.vosk_size_hint)
                .setPositiveButton(R.string.vosk_delete, (d, w) -> {
                    VoskTranscriber.deleteModel(requireContext());
                    refreshModelCard();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // -------------------------------------------------------------- picking

    private void onFilePicked(Uri uri) {
        if (uri == null) {
            return;
        }
        fileUri = uri;
        fileName = queryName(uri);
        binding.tvFileName.setText(fileName);
        binding.tvFileHint.setText(humanSize(querySize(uri)));
        binding.cardResult.setVisibility(View.GONE);
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "audio_" + System.currentTimeMillis();
    }

    private long querySize(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private String humanSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    // -------------------------------------------------------- transcription

    private void startTranscription() {
        if (busy.get()) {
            return;
        }
        if (fileUri == null) {
            Toast.makeText(requireContext(), R.string.audio_no_file,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean apiEngine = binding.rbApi.isChecked();
        AppPrefs.setUseApiEngine(requireContext(), apiEngine);
        if (apiEngine && AppPrefs.apiKey(requireContext()).trim().isEmpty()) {
            Toast.makeText(requireContext(), R.string.audio_no_key,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!apiEngine && !AppPrefs.voskModelReady(requireContext())) {
            // the #1 cause of "transcription failed" — guide the user right here
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.audio_no_model)
                    .setMessage(R.string.audio_no_model_msg)
                    .setPositiveButton(R.string.vosk_download, (d, w) -> downloadModel())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        busy.set(true);
        cancelled.set(false);
        transcript = null;
        summary = "";
        binding.cardResult.setVisibility(View.GONE);
        binding.cardProgress.setVisibility(View.VISIBLE);
        binding.progress.setIndeterminate(true);
        binding.tvProgress.setText(R.string.status_decoding);
        binding.btnStart.setEnabled(false);

        final boolean apiEngineF = apiEngine;
        executor.execute(() -> {
            try {
                // 1) copy content uri to a local file
                File input;
                try {
                    input = copyInput(fileUri);
                } catch (Exception e) {
                    throw new Exception(getString(R.string.err_stage_read)
                            + ": " + rootMessage(e));
                }
                if (cancelled.get()) {
                    throw new Exception("cancelled");
                }

                // 2) decode to 16k mono WAV (Vosk only; API uses the original file)
                final File wav;
                if (apiEngineF) {
                    long size = input.length();
                    if (size > 24L * 1024 * 1024) {
                        main.post(() -> Toast.makeText(requireContext(),
                                R.string.file_too_large, Toast.LENGTH_LONG).show());
                        throw new Exception("file_too_large");
                    }
                    wav = input;
                } else {
                    File out = new File(requireContext().getCacheDir(),
                            "decoded_" + System.currentTimeMillis() + ".wav");
                    try {
                        wav = AudioDecoder.decodeToWav(requireContext(), Uri.fromFile(input),
                                out, new AudioDecoder.Progress() {
                                    @Override
                                    public void onProgress(float ratio) {
                                        postProgress((int) (ratio * 40), R.string.status_decoding);
                                    }

                                    @Override
                                    public boolean isCancelled() {
                                        return cancelled.get();
                                    }
                                });
                    } catch (Exception e) {
                        // precise "why": codec/extractor failures are reported verbatim
                        throw new Exception(getString(R.string.err_stage_decode)
                                + ": " + rootMessage(e));
                    }
                }

                // 3) transcribe
                main.post(() -> {
                    binding.progress.setIndeterminate(false);
                    binding.progress.setProgress(0);
                });

                Transcript result;
                if (apiEngineF) {
                    postStatus(R.string.status_transcribing);
                    result = ApiClient.transcribe(requireContext(), wav, mimeFor(fileName));
                } else {
                    main.post(() -> binding.tvProgress.setText(R.string.status_loading_model));
                    result = transcribeVosk(wav);
                }

                final Transcript done = result;
                main.post(() -> onTranscriptionDone(done));
            } catch (Exception e) {
                if (!"cancelled".equals(e.getMessage())) {
                    String msg = getString(R.string.transcription_failed) + "\n"
                            + rootMessage(e);
                    main.post(() -> Toast.makeText(requireContext(), msg,
                            Toast.LENGTH_LONG).show());
                }
                main.post(this::resetUi);
            }
        });
    }

    /** The deepest exception message — MediaCodec errors nest deeply. */
    private String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        String m = r.getMessage();
        return (m == null || m.trim().isEmpty()) ? r.getClass().getSimpleName() : m;
    }

    private Transcript transcribeVosk(File wav) throws Exception {
        final Transcript[] out = new Transcript[1];
        final Exception[] err = new Exception[1];
        // VoskTranscriber already runs on the calling thread and reports via listener
        VoskTranscriber.transcribe(requireContext(), wav, cancelled,
                new VoskTranscriber.TranscribeListener() {
                    @Override
                    public void onPartial(String text) {
                        main.post(() -> {
                            binding.tvPartial.setVisibility(View.VISIBLE);
                            binding.tvPartial.setText(text);
                        });
                    }

                    @Override
                    public void onSegment(Transcript.Segment segment) {
                        main.post(() -> {
                            binding.progress.setProgress(Math.min(100,
                                    binding.progress.getProgress() + 2));
                        });
                    }

                    @Override
                    public void onProgress(float ratio) {
                        postProgress((int) (40 + ratio * 60), R.string.status_transcribing);
                    }

                    @Override
                    public void onSuccess(Transcript t) {
                        out[0] = t;
                    }

                    @Override
                    public void onError(String message) {
                        err[0] = new Exception(message);
                    }
                });
        if (err[0] != null) {
            throw err[0];
        }
        return out[0];
    }

    private void onTranscriptionDone(Transcript result) {
        busy.set(false);
        binding.cardProgress.setVisibility(View.GONE);
        binding.btnStart.setEnabled(true);
        binding.tvPartial.setVisibility(View.GONE);

        if (result == null || result.isEmpty()) {
            Toast.makeText(requireContext(), R.string.audio_empty_result,
                    Toast.LENGTH_LONG).show();
            return;
        }
        transcript = result;
        summary = "";
        summaryIsAi = false;
        binding.cardSummary.setVisibility(View.GONE);
        binding.tvTranscript.setText(result.fullText());
        binding.cardResult.setVisibility(View.VISIBLE);
        Toast.makeText(requireContext(), R.string.audio_done, Toast.LENGTH_SHORT).show();
    }

    private void resetUi() {
        busy.set(false);
        if (binding != null) {
            binding.cardProgress.setVisibility(View.GONE);
            binding.btnStart.setEnabled(true);
        }
    }

    private void postProgress(final int percent, final int labelRes) {
        main.post(() -> {
            if (binding == null) {
                return;
            }
            binding.progress.setIndeterminate(false);
            binding.progress.setProgress(Math.min(100, percent));
            binding.tvProgress.setText(getString(labelRes) + " (" + Math.min(100, percent) + "%)");
        });
    }

    private void postStatus(int labelRes) {
        main.post(() -> {
            if (binding != null) {
                binding.tvProgress.setText(getString(labelRes));
            }
        });
    }

    // -------------------------------------------------------------- summary

    private void summarize() {
        if (transcript == null || transcript.isEmpty()) {
            Toast.makeText(requireContext(), R.string.summary_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String text = transcript.fullText();
        if (text.split("\\s+").length < 20) {
            Toast.makeText(requireContext(), R.string.summary_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnSummary.setEnabled(false);

        // 1) instant offline summary so the user always gets a result
        String offline = Summarizer.summarize(text, Math.max(3,
                Math.min(7, text.split("[.!?؟\\n]").length / 8)));
        showSummary(offline, false);

        // 2) if an LLM key is configured, upgrade to an AI summary
        if (!AppPrefs.llmKey(requireContext()).trim().isEmpty()) {
            executor.execute(() -> {
                try {
                    String prompt = "این متن پیاده‌شدهٔ یک کلاس درس است. یک خلاصهٔ ساختاریافته و کاربردی به فارسی بنویس: "
                            + "ابتدا ۳ تا ۵ نکتهٔ کلیدی، بعد یک پاراگراف جمع‌بندی. خلاصه را فقط با متن پاسخ بده.\n\nمتن:\n"
                            + (text.length() > 12000 ? text.substring(0, 12000) : text);
                    String ai = ApiClient.chat(requireContext(), null, prompt);
                    main.post(() -> showSummary(ai, true));
                } catch (Exception e) {
                    main.post(() -> Toast.makeText(requireContext(),
                            getString(R.string.summary_failed) + " (" + e.getMessage() + ")",
                            Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void showSummary(String text, boolean ai) {
        if (binding == null || !isAdded() || text == null || text.trim().isEmpty()) {
            return;
        }
        summary = text.trim();
        summaryIsAi = ai;
        binding.tvSummaryTitle.setText(ai ? R.string.summary_ai_title
                : R.string.summary_offline_title);
        binding.tvSummary.setText(summary);
        binding.cardSummary.setVisibility(View.VISIBLE);
        binding.btnSummary.setEnabled(true);
    }

    // --------------------------------------------------------------- export

    private void showExportSheet() {
        if (transcript == null || transcript.isEmpty()) {
            Toast.makeText(requireContext(), R.string.audio_no_file,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(8);
        box.setPadding(pad * 2, pad * 2, pad * 2, pad * 4);

        addExportRow(sheet, box, R.string.export_txt, TranscriptExporter.TXT);
        addExportRow(sheet, box, R.string.export_srt, TranscriptExporter.SRT);
        addExportRow(sheet, box, R.string.export_md, TranscriptExporter.MD);
        addExportRow(sheet, box, R.string.export_html, TranscriptExporter.HTML);

        sheet.setContentView(box);
        sheet.show();
    }

    private void addExportRow(BottomSheetDialog sheet, android.widget.LinearLayout box,
                              int labelRes, TranscriptExporter.ExportFormat format) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setText(labelRes);
        tv.setTextSize(16f);
        tv.setPadding(dp(20), dp(14), dp(20), dp(14));
        tv.setBackground(android.content.res.Resources.getSystem()
                .getDrawable(android.R.drawable.list_selector_background));
        tv.setOnClickListener(v -> {
            sheet.dismiss();
            String base = fileName.isEmpty() ? "sedayar_transcript"
                    : fileName.replaceAll("\\.[^.]+$", "");
            TranscriptExporter.share(requireContext(), format, base,
                    base, transcript, summary);
        });
        box.addView(tv);
    }

    // ------------------------------------------------------------ save note

    private void saveAsNote() {
        if (transcript == null || transcript.isEmpty()) {
            return;
        }
        Note note = new Note();
        note.createdAt = System.currentTimeMillis();
        String[] words = transcript.fullText().split("\\s+");
        StringBuilder suggested = new StringBuilder();
        for (int i = 0; i < Math.min(6, words.length); i++) {
            if (i > 0) {
                suggested.append(' ');
            }
            suggested.append(words[i]);
        }
        note.title = suggested.toString();
        StringBuilder content = new StringBuilder(transcript.fullText());
        if (!summary.isEmpty()) {
            content.append("\n\n— ").append(getString(summaryIsAi
                            ? R.string.summary_ai_title : R.string.summary_offline_title))
                    .append(" —\n").append(summary);
        }
        note.content = content.toString();
        SedayarApp.get().repository().save(note, id -> {
            if (isAdded()) {
                Snackbar.make(binding.getRoot(), R.string.note_saved,
                        Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    // -------------------------------------------------------------- helpers

    private File copyInput(Uri uri) throws Exception {
        File dir = new File(requireContext().getCacheDir(), "audio_input");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safe = fileName.replaceAll("[^\\p{L}\\p{Nd}._-]+", "_");
        if (safe.isEmpty()) {
            safe = "audio_" + System.currentTimeMillis();
        }
        File out = new File(dir, safe);
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.get()) {
                    throw new Exception("cancelled");
                }
                fos.write(buf, 0, n);
            }
        }
        return out;
    }

    private String mimeFor(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".aac")) {
            return "audio/aac";
        }
        return "audio/mpeg";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelled.set(true);
        binding = null;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
