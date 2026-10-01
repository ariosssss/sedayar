package com.sedayar.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * App-level preferences: speech language, offline speech toggle and the
 * Whisper-compatible API / LLM settings used by the audio-transcription screen.
 */
public final class AppPrefs {

    private static final String PREFS = "sedayar_prefs";
    private static final String KEY_SPEECH_LANG = "speech_lang";
    private static final String KEY_OFFLINE = "offline_speech";
    private static final String KEY_WELCOMED = "welcomed";

    private static final String KEY_USE_API = "audio_use_api";
    private static final String KEY_API_ENDPOINT = "audio_api_endpoint";
    private static final String KEY_API_KEY = "audio_api_key";
    private static final String KEY_API_MODEL = "audio_api_model";
    private static final String KEY_API_LANG = "audio_api_lang";

    private static final String KEY_LLM_ENDPOINT = "llm_endpoint";
    private static final String KEY_LLM_KEY = "llm_key";
    private static final String KEY_LLM_MODEL = "llm_model";

    /** Absolute path of the downloaded Vosk model directory, or empty. */
    private static final String KEY_VOSK_MODEL = "vosk_model_dir";

    public static final String DEFAULT_API_ENDPOINT = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_API_MODEL = "whisper-large-v3-turbo";
    public static final String DEFAULT_LLM_ENDPOINT = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_LLM_MODEL = "llama-3.3-70b-versatile";

    private AppPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ first run

    public static boolean isWelcomed(Context c) {
        return prefs(c).getBoolean(KEY_WELCOMED, false);
    }

    public static void setWelcomed(Context c) {
        prefs(c).edit().putBoolean(KEY_WELCOMED, true).apply();
    }

    // ------------------------------------------------------------- speech

    public static String speechLang(Context c) {
        Locale locale = Locale.getDefault();
        String def;
        if ("fa".equals(locale.getLanguage())) {
            def = "fa-IR";
        } else {
            String tag = locale.toLanguageTag();
            def = (tag == null || tag.isEmpty()) ? "en-US" : tag;
        }
        return prefs(c).getString(KEY_SPEECH_LANG, def);
    }

    public static void setSpeechLang(Context c, String lang) {
        prefs(c).edit().putString(KEY_SPEECH_LANG, lang).apply();
    }

    public static boolean offlineSpeechEnabled(Context c) {
        return prefs(c).getBoolean(KEY_OFFLINE, false);
    }

    public static void setOfflineSpeech(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_OFFLINE, on).apply();
    }

    // ------------------------------------------------------- audio engine

    public static boolean useApiEngine(Context c) {
        return prefs(c).getBoolean(KEY_USE_API, false);
    }

    public static void setUseApiEngine(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_USE_API, on).apply();
    }

    public static String apiEndpoint(Context c) {
        return prefs(c).getString(KEY_API_ENDPOINT, DEFAULT_API_ENDPOINT);
    }

    public static String apiKey(Context c) {
        return prefs(c).getString(KEY_API_KEY, "");
    }

    public static String apiModel(Context c) {
        return prefs(c).getString(KEY_API_MODEL, DEFAULT_API_MODEL);
    }

    public static void setApiConfig(Context c, String endpoint, String key, String model) {
        prefs(c).edit()
                .putString(KEY_API_ENDPOINT, endpoint)
                .putString(KEY_API_KEY, key)
                .putString(KEY_API_MODEL, model)
                .apply();
    }

    public static String apiLanguage(Context c) {
        return prefs(c).getString(KEY_API_LANG, "fa");
    }

    public static void setApiLanguage(Context c, String lang) {
        prefs(c).edit().putString(KEY_API_LANG, lang).apply();
    }

    // ------------------------------------------------------- LLM summary

    public static String llmEndpoint(Context c) {
        return prefs(c).getString(KEY_LLM_ENDPOINT, DEFAULT_LLM_ENDPOINT);
    }

    public static String llmKey(Context c) {
        return prefs(c).getString(KEY_LLM_KEY, "");
    }

    public static String llmModel(Context c) {
        return prefs(c).getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL);
    }

    public static void setLlmConfig(Context c, String endpoint, String key, String model) {
        prefs(c).edit()
                .putString(KEY_LLM_ENDPOINT, endpoint)
                .putString(KEY_LLM_KEY, key)
                .putString(KEY_LLM_MODEL, model)
                .apply();
    }

    // ------------------------------------------------------- Vosk model

    public static String voskModelDir(Context c) {
        return prefs(c).getString(KEY_VOSK_MODEL, "");
    }

    public static void setVoskModelDir(Context c, String dir) {
        prefs(c).edit().putString(KEY_VOSK_MODEL, dir).apply();
    }

    public static boolean voskModelReady(Context c) {
        String dir = voskModelDir(c);
        return dir != null && !dir.isEmpty() && new java.io.File(dir, "am/final.mdl").exists();
    }
}
