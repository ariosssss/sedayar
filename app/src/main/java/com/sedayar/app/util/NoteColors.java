package com.sedayar.app.util;

/**
 * Shared palette for note cards (classic paper tones).
 */
public final class NoteColors {

    public static final int[] COLORS = {
            0xFFFFFFFF, // white
            0xFFFFF3C4, // warm yellow
            0xFFDCEFDC, // soft green
            0xFFFBE0E4, // soft pink
            0xFFDBEAFE, // soft blue
            0xFFEDE4F7  // soft purple
    };

    private NoteColors() {
    }

    public static int color(int index) {
        return COLORS[Math.max(0, Math.min(COLORS.length - 1, index))];
    }

    public static int count() {
        return COLORS.length;
    }
}
