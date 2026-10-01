package com.sedayar.app.util;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.sedayar.app.R;

import java.util.ArrayList;

/**
 * Robust wrapper around {@link SpeechRecognizer}.
 *
 * Fixes the "tap the mic and it instantly errors" problem by:
 *  - recreating the recognizer whenever the engine reports CLIENT / BUSY / SERVER
 *    errors (the service often gets stuck after one failure) and retrying once;
 *  - reporting a precise, user-facing Persian/English message for every error
 *    code instead of a generic toast;
 *  - guarding start/stop so double taps can never crash or wedge the engine.
 */
public class SpeechEngine {

    public interface Listener {
        void onListeningChanged(boolean listening);

        void onPartialText(String text);

        void onFinalText(String text);

        /** Called with a localized, presentable error message. */
        void onError(String message);
    }

    /** Errors worth one automatic recreate+retry. */
    private static final int RETRY_MAX = 1;
    private static final long RETRY_DELAY_MS = 400L;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private SpeechRecognizer recognizer;
    private String language;
    private boolean listening = false;
    private boolean destroyed = false;
    private int retries = 0;

    public SpeechEngine(Context context, String language, Listener listener) {
        this.context = context.getApplicationContext();
        this.language = language;
        this.listener = listener;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public boolean isListening() {
        return listening;
    }

    /** Creates the underlying recognizer (safe to call repeatedly). */
    private void create() {
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(internalListener);
    }

    public void start() {
        if (destroyed) {
            return;
        }
        if (!isAvailable()) {
            listener.onError(context.getString(R.string.speech_unavailable));
            return;
        }
        if (listening) {
            return; // already running; ignore double taps
        }
        if (recognizer == null) {
            create();
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (AppPrefs.offlineSpeechEnabled(context)) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }
        try {
            recognizer.startListening(intent);
        } catch (Exception e) {
            // The service can throw if it was killed mid-session: recreate and retry once.
            retries = 0;
            scheduleRetry();
        }
    }

    public void stop() {
        if (recognizer != null && listening) {
            try {
                recognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
        setListening(false);
    }

    public void destroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private void scheduleRetry() {
        if (destroyed || retries >= RETRY_MAX) {
            listener.onError(context.getString(R.string.speech_error_generic));
            return;
        }
        retries++;
        setListening(false);
        main.postDelayed(() -> {
            if (destroyed) {
                return;
            }
            create();
            start();
        }, RETRY_DELAY_MS);
    }

    private void setListening(boolean on) {
        if (listening != on) {
            listening = on;
            listener.onListeningChanged(on);
        }
    }

    private final RecognitionListener internalListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            retries = 0;
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
            switch (error) {
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                case SpeechRecognizer.ERROR_SERVER:
                    scheduleRetry();
                    return;
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_NETWORK:
                    listener.onError(context.getString(R.string.speech_error_network));
                    return;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    listener.onError(context.getString(R.string.speech_error_no_match));
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    listener.onError(context.getString(R.string.mic_rationale));
                    return;
                default:
                    listener.onError(context.getString(R.string.speech_error_generic));
            }
        }

        @Override
        public void onResults(Bundle results) {
            setListening(false);
            ArrayList<String> list =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty() && list.get(0) != null
                    && !list.get(0).trim().isEmpty()) {
                listener.onFinalText(list.get(0));
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> list = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && !list.isEmpty() && list.get(0) != null) {
                listener.onPartialText(list.get(0));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
