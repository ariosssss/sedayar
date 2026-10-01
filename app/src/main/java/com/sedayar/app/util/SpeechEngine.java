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

    /**
     * In continuous mode a silent gap (NO_MATCH / SPEECH_TIMEOUT) never ends
     * the session: the recognizer quietly re-arms so a whole class can be
     * dictated without touching the screen. The counter only guards against
     * an infinite hot loop on a broken device.
     */
    private static final int SILENT_RESTART_MAX = 60;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final boolean continuous;

    private SpeechRecognizer recognizer;
    private String language;
    private boolean listening = false;
    private boolean destroyed = false;
    private int retries = 0;
    private int silentRestarts = 0;

    /** Non-continuous engine (single-shot dictation, e.g. the note editor). */
    public SpeechEngine(Context context, String language, Listener listener) {
        this(context, language, listener, false);
    }

    /** Continuous engine: survives silent pauses until stop() is called. */
    public SpeechEngine(Context context, String language, Listener listener,
                        boolean continuous) {
        this.context = context.getApplicationContext();
        this.language = language;
        this.listener = listener;
        this.continuous = continuous;
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
        try {
            recognizer.startListening(buildIntent());
        } catch (Exception e) {
            // The service can throw if it was killed mid-session: recreate and retry once.
            retries = 0;
            scheduleRetry();
        }
    }

    private Intent buildIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (AppPrefs.offlineSpeechEnabled(context)) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }
        return intent;
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
            silentRestarts = 0; // real audio arrived — the engine works
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
                    if (continuous && silentRestarts < SILENT_RESTART_MAX) {
                        // a network hiccup mid-class must not kill the session
                        silentRestarts++;
                        main.postDelayed(this::quietRestart, 600L);
                        return;
                    }
                    listener.onError(context.getString(R.string.speech_error_network));
                    return;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    if (continuous && silentRestarts < SILENT_RESTART_MAX) {
                        silentRestarts++;
                        quietRestart();
                        return;
                    }
                    listener.onError(context.getString(R.string.speech_error_no_match));
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    listener.onError(context.getString(R.string.mic_rationale));
                    return;
                default:
                    listener.onError(context.getString(R.string.speech_error_generic));
            }
        }

        /** Re-arms the recognizer after a silent gap (no user-facing error). */
        private void quietRestart() {
            if (destroyed) {
                return;
            }
            main.postDelayed(() -> {
                if (destroyed || listening) {
                    return;
                }
                create();
                try {
                    recognizer.startListening(buildIntent());
                } catch (Exception ignored) {
                    // next onError/ready cycle will retry
                }
            }, 250L);
        }

        @Override
        public void onResults(Bundle results) {
            setListening(false);
            silentRestarts = 0;
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
            if (list != null && !list.isEmpty() && list.get(0) != null
                    && !list.get(0).trim().isEmpty()) {
                silentRestarts = 0;
                listener.onPartialText(list.get(0));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
