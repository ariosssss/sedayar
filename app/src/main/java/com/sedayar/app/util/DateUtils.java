package com.sedayar.app.util;

import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Date formatting helper: shows Jalali (Persian) dates on Persian devices
 * using the built-in ICU calendar (API 24+), Gregorian otherwise.
 */
public final class DateUtils {

    private DateUtils() {
    }

    public static String format(long timestamp) {
        Locale locale = Locale.getDefault();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "fa".equals(locale.getLanguage())) {
            try {
                android.icu.text.SimpleDateFormat icu = new android.icu.text.SimpleDateFormat(
                        "d MMMM yyyy",
                        new android.icu.util.ULocale("fa_IR@calendar=persian"));
                return icu.format(new Date(timestamp));
            } catch (Throwable ignored) {
                // fall through to the Gregorian formatter
            }
        }
        SimpleDateFormat sdf = new SimpleDateFormat("d MMM yyyy", locale);
        return sdf.format(new Date(timestamp));
    }
}
