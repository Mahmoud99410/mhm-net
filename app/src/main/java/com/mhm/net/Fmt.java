package com.mhm.net;

import java.util.Locale;

/** Number formatting (always Latin digits). */
public final class Fmt {
    private Fmt() {}

    private static final double MB = 1024.0 * 1024.0;

    public static String bytes(long b) {
        if (b < 0) b = 0;
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / MB);
        return String.format(Locale.US, "%.2f GB", b / (MB * 1024));
    }

    /** bytes per second -> "x.xx MB/s" */
    public static String speed(double bytesPerSec) {
        if (bytesPerSec < 0) bytesPerSec = 0;
        return String.format(Locale.US, "%.2f MB/s", bytesPerSec / MB);
    }

    public static String ago(long time) {
        long min = (System.currentTimeMillis() - time) / 60000;
        if (min < 1) return "الآن";
        if (min < 60) return "قبل " + min + " دقيقة";
        long h = min / 60;
        if (h < 24) return "قبل " + h + " ساعة";
        return "قبل " + (h / 24) + " يوم";
    }

    public static String minutes(long ms) {
        long min = Math.max(0, (ms + 59999) / 60000);
        if (min < 60) return min + " دقيقة";
        return String.format(Locale.US, "%d ساعة و %d دقيقة", min / 60, min % 60);
    }

    public static String hm(int minutesOfDay) {
        return String.format(Locale.US, "%02d:%02d", minutesOfDay / 60, minutesOfDay % 60);
    }
}
