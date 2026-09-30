package com.sedayar.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * Small app-level preferences: speech language + offline speech toggle.
 */
public final class AppPrefs {

    private static final String PREFS = "sedayar_prefs";
    private static final String KEY_SPEECH_LANG = "speech_lang";
    private static final String KEY_OFFLINE = "offline_speech";

    private AppPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

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
}
