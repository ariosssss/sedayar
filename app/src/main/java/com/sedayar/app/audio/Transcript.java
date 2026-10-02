package com.sedayar.app.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Result of transcribing an audio file: an ordered list of timed segments
 * (used for SRT and the HTML timeline) plus the plain joined text.
 */
public class Transcript {

    public static class Segment {
        public final long startMs;
        public final long endMs;
        public final String text;

        public Segment(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text == null ? "" : text.trim();
        }
    }

    public final List<Segment> segments = new ArrayList<>();
    public String language = "";

    public void add(long startMs, long endMs, String text) {
        if (text != null && !text.trim().isEmpty()) {
            segments.add(new Segment(startMs, endMs, text.trim()));
        }
    }

    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(segments.get(i).text);
        }
        return sb.toString().trim();
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public long endMs() {
        return segments.isEmpty() ? 0 : segments.get(segments.size() - 1).endMs;
    }

    /** Formats ms as HH:MM:SS,mmm (SRT) or HH:MM:SS.mmm. */
    public static String timecode(long ms, boolean comma) {
        long total = Math.max(0, ms);
        long h = total / 3600000;
        long m = (total % 3600000) / 60000;
        long s = (total % 60000) / 1000;
        long frac = total % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d%s%03d",
                h, m, s, comma ? "," : ".", frac);
    }

    /** Short mm:ss label for the UI. */
    public static String shortTime(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }
}
