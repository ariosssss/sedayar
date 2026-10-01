package com.sedayar.app;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.sedayar.app.data.NotesRepository;
import com.sedayar.app.util.AppPrefs;

/**
 * Application entry point: keeps a single shared NotesRepository and pins the
 * in-app language to Persian by default (the app is Persian-first; switchable
 * to English in Settings).
 */
public class SedayarApp extends Application {

    private static SedayarApp instance;
    private NotesRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        repository = new NotesRepository(this);
        applyAppLanguage();
    }

    /** Applies the stored app language (fa by default) to the whole app. */
    public void applyAppLanguage() {
        LocaleListCompat want = LocaleListCompat.forLanguageTags(AppPrefs.appLang(this));
        if (!want.equals(AppCompatDelegate.getApplicationLocales())) {
            AppCompatDelegate.setApplicationLocales(want);
        }
    }

    public static SedayarApp get() {
        return instance;
    }

    public NotesRepository repository() {
        return repository;
    }
}
