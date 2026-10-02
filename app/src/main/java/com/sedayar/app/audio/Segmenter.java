package com.sedayar.app.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns timed words (from any engine — the offline Vosk file decode or the
 * live dictation stream) into human-sized transcript segments for the
 * transcript views, the SRT file and the HTML timeline.
 *
 * Splitting rules (the same the file pipeline always used):
 *  - a pause longer than 1.2 s between two words starts a new segment;
 *  - a segment never grows beyond 12 seconds;
 *  - at most ~40 words per segment;
 *  - if nothing was timed at all, one segment spanning the whole audio is
 *    produced when the caller flushes with a fallback end time.
 *
 * Pure Java — covered by unit tests in src/test.
 */
public final class Segmenter {

    private final List<long[]> words = new ArrayList<>(); // {startMs, endMs}
    private final List<String> texts = new ArrayList<>();

    public void add(long startMs, long endMs, String word) {
        if (word == null || word.trim().isEmpty()) {
            return;
        }
        words.add(new long[]{Math.max(0, startMs), Math.max(0, endMs)});
        texts.add(word.trim());
    }

    public int wordCount() {
        return words.size();
    }

    /** Emits the pending words as segments and resets the buffer. */
    public List<Transcript.Segment> flush(long fallbackEnd) {
        List<Transcript.Segment> out = new ArrayList<>();
        long segStart = -1, segEnd = -1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            long[] w = words.get(i);
            if (segStart < 0) {
                segStart = w[0];
                sb.setLength(0);
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(texts.get(i));
            segEnd = w[1];
            boolean breakAfter = i == words.size() - 1;
            if (!breakAfter) {
                long[] next = words.get(i + 1);
                boolean gap = next[0] - w[1] > 1200;
                boolean longSeg = w[1] - segStart > 12_000;
                boolean manyWords = i % 40 == 39;
                breakAfter = gap || longSeg || manyWords;
            }
            if (breakAfter && sb.length() > 0) {
                out.add(new Transcript.Segment(segStart, Math.max(segEnd, segStart), sb.toString()));
                segStart = -1;
            }
        }
        if (segStart >= 0 && sb.length() > 0) {
            out.add(new Transcript.Segment(segStart,
                    Math.max(segEnd, segStart + 500), sb.toString()));
        }
        words.clear();
        texts.clear();
        return out;
    }
}
