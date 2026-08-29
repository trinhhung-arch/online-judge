package dev.oj.worker.sandbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Đọc file {@code meta} của {@code isolate}. Bước 2.5 của {@code build-order.md}.
 *
 * <h2>Luật của class này: mã lạ → {@link Outcome#INTERNAL_ERROR}, không map bừa sang RE</h2>
 * Cám dỗ là viết {@code default -> RE} cho gọn. Nhưng {@code RE} nói với thí sinh "chương
 * trình của bạn sai", còn sự thật có thể là "máy chấm hỏng". API cho chấm lại {@code IE} tối
 * đa 2 lần (FR-SUB-12); {@code RE} thì không, nó là verdict cuối. Đoán sai một verdict giữa
 * contest thì <b>không ai phát hiện ra</b> — và đó mới là điều tệ
 * ({@code oj-worker/CLAUDE.md} mục 6).
 *
 * <h2>Bốn giá trị {@code status} của isolate 2.6, đo được</h2>
 * <pre>
 *   (không có)                          chạy xong, exitcode 0
 *   RE   exitcode:N                     thoát với mã khác 0
 *   SG   exitsig:N                      bị tín hiệu giết
 *   TO   killed:1                       vượt giới hạn thời gian
 *   XX                                  lỗi nội bộ của chính isolate
 * </pre>
 *
 * <h2>Chỗ dễ sai nhất: {@code SG} không đồng nghĩa với RE</h2>
 * {@code SG} kèm {@code cg-oom-killed:1} là <b>MLE</b>, không phải RE. Đo được: chương trình
 * chạm 10GB bị cgroup giết bằng tín hiệu 9 — báo cho thí sinh là "Runtime Error" thì họ đi tìm
 * lỗi con trỏ trong một chương trình chỉ thiếu bộ nhớ (U2, {@code nfrplan.md} 6.2).
 *
 * <h2>Và một chỗ ngược đời: {@code cg-mem} tích luỹ theo tuổi của box</h2>
 * Đo được: biên dịch xong rồi chạy trong <i>cùng một box</i> thì lượt chạy báo
 * {@code cg-mem:40032} cho một chương trình dùng thật 1,6MB — đó là đỉnh bộ nhớ của
 * {@code g++} còn sót lại. Đặt ngưỡng MLE trên con số ấy là mọi bài đều MLE. Vì thế
 * {@link IsolateBox} {@code --cleanup} rồi {@code --init} lại giữa các lượt: mất ~5ms, và
 * đó là giá của một con số bộ nhớ có nghĩa.
 */
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

    /** Kết luận của sandbox — <b>chưa phải verdict</b>: {@link #OK} còn phải so output đã. */
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

    /**
     * Kết luận. Nhánh {@code default} <b>không</b> được đổi thành RE — xem javadoc của class.
     */
    public Outcome outcome() {
        if (status == null || status.isBlank()) {
            return Outcome.OK;
        }
        return switch (status) {
            case "TO" -> Outcome.TIME_LIMIT;
            case "SG" -> cgOomKilled ? Outcome.MEMORY_LIMIT : Outcome.RUNTIME_ERROR;
            case "RE" -> Outcome.RUNTIME_ERROR;
            default -> Outcome.INTERNAL_ERROR;   // gồm "XX" và mọi mã isolate sau này thêm
        };
    }

    /**
     * Bộ nhớ đã dùng. Ưu tiên {@code cg-mem} (đỉnh của cả cgroup, tính cả tiến trình con) và
     * chỉ lùi về {@code max-rss} (một tiến trình) khi vì lý do nào đó không có — với
     * {@code max-rss} thì fork ra 50 con mỗi con 100MB được báo là 100MB.
     */
    public long memoryKb() {
        return cgMemoryKb > 0 ? cgMemoryKb : maxRssKb;
    }

    /** Vượt giờ vì ngủ hoặc chờ I/O, chứ không phải vì tính toán — chỉ để chẩn đoán. */
    public boolean hitWallClock() {
        return message != null && message.contains("wall clock");
    }

    /**
     * Chuỗi chẩn đoán ghi vào {@code judge_runs.isolate_status}.
     *
     * <p>{@code oj-worker/CLAUDE.md} mục 6 yêu cầu <b>log nguyên văn file meta</b> khi gặp mã
     * lạ — đây là chỗ nó được giữ lại. Chuỗi này chỉ dành cho ADMIN, không bao giờ đi ra
     * response của thí sinh: nó chứa đường dẫn bên trong box, và mục 7 cấm để lộ chúng.
     */
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
