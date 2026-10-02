package com.sedayar.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.sedayar.app.audio.VoskTranscriber;
import com.sedayar.app.databinding.ActivitySettingsBinding;
import com.sedayar.app.util.AppPrefs;
import com.sedayar.app.util.InsetsUtil;

import java.util.Locale;

/**
 * Settings: speech language, offline recognition, the Vosk model lifecycle,
 * Whisper API configuration and the AI summary (LLM) configuration.
 */
public class SettingsActivity extends AppCompatActivity
        implements VoskTranscriber.ModelDownloadListener {

    private ActivitySettingsBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean downloading = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        InsetsUtil.apply(binding.getRoot(), null);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        loadPrefs();
        wireUi();
        refreshVoskStatus();
        binding.tvAbout.setText(getString(R.string.about_version) + " "
                + BuildConfig.VERSION_NAME + " — github.com/ariosssss/sedayar");
    }

    private void loadPrefs() {
        String lang = AppPrefs.speechLang(this);
        Locale sys = Locale.getDefault();
        boolean isFaSys = "fa".equals(sys.getLanguage());
        boolean usesSys = lang.equals(isFaSys ? "fa-IR" : sys.toLanguageTag())
                || AppPrefs.speechLang(this).equals(sys.toLanguageTag());
        if (usesSys) {
            binding.rbLangSys.setChecked(true);
        } else if (lang.startsWith("fa")) {
            binding.rbLangFa.setChecked(true);
        } else {
            binding.rbLangEn.setChecked(true);
        }
        binding.swOffline.setChecked(AppPrefs.offlineSpeechEnabled(this));

        if ("en".equals(AppPrefs.appLang(this))) {
            binding.rbAppEn.setChecked(true);
        } else {
            binding.rbAppFa.setChecked(true);
        }

        binding.swUseApi.setChecked(AppPrefs.useApiEngine(this));
        binding.apiFields.setVisibility(
                binding.swUseApi.isChecked() ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.etApiEndpoint.setText(AppPrefs.apiEndpoint(this));
        binding.etApiKey.setText(AppPrefs.apiKey(this));
        binding.etApiModel.setText(AppPrefs.apiModel(this));
        binding.etApiLang.setText(AppPrefs.apiLanguage(this));

        binding.etLlmEndpoint.setText(AppPrefs.llmEndpoint(this));
        binding.etLlmKey.setText(AppPrefs.llmKey(this));
        binding.etLlmModel.setText(AppPrefs.llmModel(this));
    }

    private void wireUi() {
        binding.swUseApi.setOnCheckedChangeListener((b, on) ->
                binding.apiFields.setVisibility(
                        on ? android.view.View.VISIBLE : android.view.View.GONE));

        binding.fabSave.setOnClickListener(v -> persist());

        binding.btnModelDownload.setOnClickListener(v -> {
            if (downloading || AppPrefs.voskModelReady(this)) {
                return;
            }
            downloading = true;
            binding.btnModelDownload.setEnabled(false);
            binding.btnModelDownload.setText(getString(R.string.vosk_downloading, 0));
            VoskTranscriber.startModelDownload(this, this);
        });

        binding.btnModelDelete.setOnClickListener(v -> {
            if (!AppPrefs.voskModelReady(this)
                    && !VoskTranscriber.modelDir(this).exists()) {
                Toast.makeText(this, R.string.vosk_status_missing, Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.vosk_delete)
                    .setMessage(R.string.vosk_size_hint)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        VoskTranscriber.deleteModel(this);
                        refreshVoskStatus();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        binding.cardPremium.setOnClickListener(v ->
                startActivity(new Intent(this, PremiumActivity.class)));
    }

    private void persist() {
        String lang;
        if (binding.rbLangFa.isChecked()) {
            lang = "fa-IR";
        } else if (binding.rbLangEn.isChecked()) {
            lang = "en-US";
        } else {
            Locale sys = Locale.getDefault();
            lang = "fa".equals(sys.getLanguage()) ? "fa-IR"
                    : (sys.toLanguageTag() == null || sys.toLanguageTag().isEmpty()
                    ? "en-US" : sys.toLanguageTag());
        }
        AppPrefs.setSpeechLang(this, lang);
        AppPrefs.setOfflineSpeech(this, binding.swOffline.isChecked());

        // in-app UI language (Persian by default)
        String newAppLang = binding.rbAppEn.isChecked() ? "en" : "fa";
        boolean langChanged = !newAppLang.equals(AppPrefs.appLang(this));
        AppPrefs.setAppLang(this, newAppLang);
        if (langChanged) {
            SedayarApp.get().applyAppLanguage(); // recreates activities
        }
        AppPrefs.setUseApiEngine(this, binding.swUseApi.isChecked());

        String apiEndpoint = text(binding.etApiEndpoint);
        String apiKey = text(binding.etApiKey);
        String apiModel = text(binding.etApiModel);
        String apiLang = text(binding.etApiLang);
        AppPrefs.setApiConfig(this,
                apiEndpoint.isEmpty() ? AppPrefs.DEFAULT_API_ENDPOINT : apiEndpoint,
                apiKey, apiModel.isEmpty() ? AppPrefs.DEFAULT_API_MODEL : apiModel);
        AppPrefs.setApiLanguage(this, apiLang.isEmpty() ? "fa" : apiLang);

        String llmEndpoint = text(binding.etLlmEndpoint);
        String llmModel = text(binding.etLlmModel);
        AppPrefs.setLlmConfig(this,
                llmEndpoint.isEmpty() ? AppPrefs.DEFAULT_LLM_ENDPOINT : llmEndpoint,
                text(binding.etLlmKey),
                llmModel.isEmpty() ? AppPrefs.DEFAULT_LLM_MODEL : llmModel);

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    private String text(com.google.android.material.textfield.TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    // -------------------------------------------------------- vosk model

    private void refreshVoskStatus() {
        boolean ready = AppPrefs.voskModelReady(this);
        binding.tvVoskStatus.setText(getString(ready
                ? R.string.vosk_status_ready : R.string.vosk_status_missing));
        binding.btnModelDownload.setEnabled(!ready && !downloading);
        binding.btnModelDownload.setText(ready
                ? R.string.vosk_status_ready : R.string.vosk_download);
    }

    @Override
    public void onProgress(int percent) {
        handler.post(() -> {
            if (binding == null) {
                return;
            }
            binding.btnModelDownload.setEnabled(false);
            binding.btnModelDownload.setText(
                    getString(R.string.vosk_downloading, percent));
            binding.tvVoskStatus.setText(
                    getString(R.string.vosk_downloading, percent));
        });
    }

    @Override
    public void onSuccess(java.io.File modelDir) {
        handler.post(() -> {
            downloading = false;
            refreshVoskStatus();
            Toast.makeText(this, R.string.vosk_status_ready, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onError(String message) {
        handler.post(() -> {
            downloading = false;
            refreshVoskStatus();
            Toast.makeText(this, getString(R.string.transcription_failed) + " "
                    + message, Toast.LENGTH_SHORT).show();
        });
    }

    // -------------------------------------------------------- lifecycle

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        binding = null;
    }
}
