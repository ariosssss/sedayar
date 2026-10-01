package com.sedayar.app.audio;

import android.content.Context;

import com.sedayar.app.util.AppPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for Whisper-compatible speech APIs and OpenAI-compatible chat APIs
 * (Groq, OpenAI, or any self-hosted endpoint the user configures).
 */
public final class ApiClient {

    public static class ApiException extends Exception {
        public ApiException(String message) {
            super(message);
        }
    }

    private static OkHttpClient http;

    private ApiClient() {
    }

    private static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    /** POST /audio/transcriptions with verbose_json so segments keep timings. */
    public static Transcript transcribe(Context context, File audioFile, String mime)
            throws Exception {
        String endpoint = trimSlash(AppPrefs.apiEndpoint(context)) + "/audio/transcriptions";
        String key = AppPrefs.apiKey(context);
        if (key == null || key.trim().isEmpty()) {
            throw new ApiException("no_api_key");
        }
        String lang = AppPrefs.apiLanguage(context);

        RequestBody fileBody = RequestBody.create(audioFile,
                MediaType.parse(mime == null ? "audio/mpeg" : mime));
        MultipartBody.Builder mb = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", AppPrefs.apiModel(context))
                .addFormDataPart("response_format", "verbose_json");
        if (lang != null && !lang.isEmpty() && !"auto".equals(lang)) {
            mb.addFormDataPart("language", lang);
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + key.trim())
                .post(mb.build())
                .build();

        Transcript transcript = new Transcript();
        try (Response resp = http().newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new ApiException(mapError(resp.code(), body));
            }
            JSONObject json = new JSONObject(body);
            transcript.language = json.optString("language", "");
            JSONArray segments = json.optJSONArray("segments");
            if (segments != null) {
                for (int i = 0; i < segments.length(); i++) {
                    JSONObject s = segments.getJSONObject(i);
                    transcript.add(
                            (long) (s.optDouble("start", 0) * 1000.0),
                            (long) (s.optDouble("end", 0) * 1000.0),
                            s.optString("text", ""));
                }
            }
            if (transcript.isEmpty()) {
                String text = json.optString("text", "");
                if (!text.trim().isEmpty()) {
                    transcript.add(0, 0, text);
                }
            }
        }
        return transcript;
    }

    /** POST /chat/completions — used by the AI summary. */
    public static String chat(Context context, String systemPrompt, String userPrompt)
            throws Exception {
        String endpoint = trimSlash(AppPrefs.llmEndpoint(context)) + "/chat/completions";
        String key = AppPrefs.llmKey(context);
        if (key == null || key.trim().isEmpty()) {
            throw new ApiException("no_api_key");
        }

        JSONObject body = new JSONObject();
        body.put("model", AppPrefs.llmModel(context));
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system").put("content", systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        body.put("messages", messages);
        body.put("temperature", 0.3);

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + key.trim())
                .post(RequestBody.create(body.toString(),
                        MediaType.parse("application/json")))
                .build();

        try (Response resp = http().newCall(request).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new ApiException(mapError(resp.code(), text));
            }
            JSONObject json = new JSONObject(text);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                if (msg != null) {
                    return msg.optString("content", "").trim();
                }
            }
            throw new ApiException("bad_response");
        }
    }

    private static String mapError(int code, String body) {
        String snippet = body == null ? "" : body;
        if (snippet.length() > 300) {
            snippet = snippet.substring(0, 300);
        }
        if (code == 401) {
            return "invalid_api_key";
        }
        if (code == 413) {
            return "file_too_large";
        }
        if (code == 429) {
            return "rate_limited";
        }
        return "http_" + code + (snippet.isEmpty() ? "" : (": " + snippet));
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
