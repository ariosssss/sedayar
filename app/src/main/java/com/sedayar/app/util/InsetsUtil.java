package com.sedayar.app.util;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * One-stop fix for Android 15 (targetSdk 35) forced edge-to-edge: without it
 * every screen draws underneath the status bar (the clock) and the gesture
 * navigation bar. Apply on an activity root, optionally padding a bottom view
 * (bottom nav / player bar) instead of the root so its background extends.
 */
public final class InsetsUtil {

    private InsetsUtil() {
    }

    /** Pads the root by the status bar; the bottom bar view by the nav bar. */
    public static void apply(View root, View bottomBar) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, bars.top, 0, 0);
            if (bottomBar instanceof ViewGroup && bottomBar != v) {
                bottomBar.setPadding(0, 0, 0, bars.bottom);
            } else if (bottomBar == null) {
                v.setPadding(0, bars.top, 0, bars.bottom);
            }
            return insets;
        });
        // request a first pass (some windows only dispatch after attach)
        ViewCompat.requestApplyInsets(root);
    }
}
