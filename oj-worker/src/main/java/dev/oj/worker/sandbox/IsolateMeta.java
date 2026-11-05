package dev.oj.worker.sandbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record IsolateMeta(
        String status,
        String message,
        long cpuTimeMs,
        long wallTimeMs,
        long cgMemoryKb,
        long maxRssKb,
        boolean cgOomKilled,
        boolean killed,
        Integer exitCode,
        Integer exitSignal,
        Map<String, String> raw) {

    public enum Outcome {
        OK,
        TIME_LIMIT,
        MEMORY_LIMIT,
        RUNTIME_ERROR,
        INTERNAL_ERROR
    }

    public static IsolateMeta parse(List<String> lines) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                raw.put(line.substring(0, colon), line.substring(colon + 1));
            }
        }
        return new IsolateMeta(
                raw.get("status"),
                raw.get("message"),
                millis(raw.get("time")),
                millis(raw.get("time-wall")),
                number(raw.get("cg-mem")),
                number(raw.get("max-rss")),
                "1".equals(raw.get("cg-oom-killed")),
                "1".equals(raw.get("killed")),
                integer(raw.get("exitcode")),
                integer(raw.get("exitsig")),
                Map.copyOf(raw));
    }

    public Outcome outcome() {
        if (status == null || status.isBlank()) {
            return Outcome.OK;
        }
        return switch (status) {
            case "TO" -> Outcome.TIME_LIMIT;
            case "SG" -> cgOomKilled ? Outcome.MEMORY_LIMIT : Outcome.RUNTIME_ERROR;
            case "RE" -> Outcome.RUNTIME_ERROR;
            default -> Outcome.INTERNAL_ERROR;
        };
    }

    public long memoryKb() {
        return cgMemoryKb > 0 ? cgMemoryKb : maxRssKb;
    }

    public boolean hitWallClock() {
        return message != null && message.contains("wall clock");
    }

    public String diagnostic() {
        if (outcome() == Outcome.INTERNAL_ERROR) {
            StringBuilder sb = new StringBuilder("isolate meta nguyên văn:");
            raw.forEach((k, v) -> sb.append('\n').append(k).append(':').append(v));
            return sb.toString();
        }
        return (status == null ? "OK" : status)
                + (exitCode != null ? " exit=" + exitCode : "")
                + (exitSignal != null ? " signal=" + exitSignal : "")
                + (cgOomKilled ? " cg-oom" : "")
                + " cpu=" + cpuTimeMs + "ms wall=" + wallTimeMs + "ms mem=" + memoryKb() + "KB";
    }

    private static long millis(String secondsText) {
        return Optional.ofNullable(secondsText)
                .map(s -> Math.round(Double.parseDouble(s.trim()) * 1000.0))
                .orElse(0L);
    }

    private static long number(String text) {
        return text == null ? 0L : Long.parseLong(text.trim());
    }

    private static Integer integer(String text) {
        return text == null ? null : Integer.valueOf(text.trim());
    }
}
