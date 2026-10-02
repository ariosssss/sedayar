package com.sedayar.app.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny offline extractive summarizer (TF scoring, no network, no API key).
 * Works for Persian and English: splits sentences on punctuation, drops
 * common stop-words and picks the highest-scoring sentences in their
 * original order. Quality is modest but it never fails and never costs.
 */
public final class Summarizer {

    private static final String[] STOP_FA = {
            "و", "در", "به", "از", "که", "این", "را", "با", "است", "برای", "آن",
            "یک", "های", "می", "شود", "کرد", "کند", "بود", "خوب", "هم", "تا",
            "بر", "اما", "یا", "اگر", "هر", "چه", "همه", "باید", "دیگر", "بین",
            "روی", "شود", "باشد", "دارد", "دارند", "کند", "کنیم", "بشود", "اینکه"
    };

    private static final String[] STOP_EN = {
            "the", "a", "an", "and", "or", "but", "if", "then", "of", "to", "in",
            "on", "at", "by", "for", "with", "about", "is", "are", "was", "were",
            "be", "been", "being", "it", "its", "this", "that", "these", "those",
            "as", "so", "we", "you", "he", "she", "they", "them", "his", "her",
            "their", "our", "your", "my", "me", "him", "us", "do", "does", "did",
            "not", "no", "yes", "can", "will", "would", "should", "could", "have",
            "has", "had", "from", "into", "than", "when", "what", "which", "who",
            "how", "why", "there", "here", "also", "just", "very", "much", "more"
    };

    private Summarizer() {
    }

    public static String summarize(String text, int maxSentences) {
        List<String> sentences = splitSentences(text);
        if (sentences.size() <= maxSentences) {
            return text == null ? "" : text.trim();
        }
        Map<String, Integer> freq = termFrequency(sentences);
        double best = 0;
        double[] scores = new double[sentences.size()];
        for (int i = 0; i < sentences.size(); i++) {
            String[] words = tokenize(sentences.get(i));
            double score = 0;
            int count = 0;
            for (String w : words) {
                Integer f = freq.get(w);
                if (f != null) {
                    score += f;
                    count++;
                }
            }
            scores[i] = count == 0 ? 0 : score / Math.sqrt(count);
            best = Math.max(best, scores[i]);
        }

        // top-K sentence indices
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            idx.add(i);
        }
        final double[] s = scores;
        idx.sort((a, b) -> Double.compare(s[b], s[a]));
        List<Integer> picked = idx.subList(0, Math.min(maxSentences, idx.size()));
        List<Integer> ordered = new ArrayList<>(picked);
        ordered.sort(Integer::compareTo);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(sentences.get(ordered.get(i)).trim());
        }
        return sb.toString();
    }

    public static List<String> keywords(String text, int count) {
        Map<String, Integer> freq = termFrequency(splitSentences(text));
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            if (out.size() >= count) {
                break;
            }
            out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------- internals

    private static Map<String, Integer> termFrequency(List<String> sentences) {
        Map<String, Integer> freq = new HashMap<>();
        for (String sentence : sentences) {
            for (String w : tokenize(sentence)) {
                Integer n = freq.get(w);
                freq.put(w, n == null ? 1 : n + 1);
            }
        }
        return freq;
    }

    private static String[] tokenize(String sentence) {
        if (sentence == null) {
            return new String[0];
        }
        String[] raw = sentence.split("[^\\p{L}\\p{Nd}]+");
        List<String> out = new ArrayList<>();
        for (String w : raw) {
            String t = w.toLowerCase();
            if (t.length() < 2 || isStop(t)) {
                continue;
            }
            out.add(t);
        }
        return out.toArray(new String[0]);
    }

    private static boolean isStop(String w) {
        for (String s : STOP_FA) {
            if (s.equals(w)) {
                return true;
            }
        }
        for (String s : STOP_EN) {
            if (s.equals(w)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        // Split on sentence enders (Latin + Persian) and hard newlines
        String[] parts = text.split("(?<=[.!?؟؛\n])\\s+");
        for (String p : parts) {
            String t = p.trim();
            if (t.length() >= 8) {
                out.add(t);
            }
        }
        return out;
    }
}
