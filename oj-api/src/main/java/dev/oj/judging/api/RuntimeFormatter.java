package dev.oj.judging.api;

import java.util.Locale;

public final class RuntimeFormatter {

    public static final int ROUNDING_MS = 10;

    public static final String MEASUREMENT_NOTE = "đo trên máy chấm chuẩn, sai số ±5%";

    private RuntimeFormatter() {
    }

    public static Integer roundMs(Integer timeMs) {
        return timeMs == null ? null : (int) (Math.round(timeMs / (double) ROUNDING_MS) * ROUNDING_MS);
    }

    public static String seconds(Integer timeMs) {
        return timeMs == null ? "—" : String.format(Locale.ROOT, "%.2fs", timeMs / 1000.0);
    }

    public static String memory(Integer memoryKb) {
        return memoryKb == null ? "—" : String.format(Locale.ROOT, "%.1f MB", memoryKb / 1024.0);
    }
}
