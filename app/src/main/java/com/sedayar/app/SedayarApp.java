package com.sedayar.app;

import android.app.Application;

import com.sedayar.app.data.NotesRepository;

/**
 * Application entry point: keeps a single shared NotesRepository.
 */
public class SedayarApp extends Application {

    private static SedayarApp instance;
    private NotesRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        repository = new NotesRepository(this);
    }

    public static SedayarApp get() {
        return instance;
    }

    public NotesRepository repository() {
        return repository;
    }
}
