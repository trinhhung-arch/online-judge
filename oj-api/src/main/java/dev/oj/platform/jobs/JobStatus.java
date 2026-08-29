package dev.oj.platform.jobs;

/**
 * Vòng đời một job. Khớp {@code CHECK (status IN (...))} của bảng {@code jobs} (V6).
 *
 * <pre>
 *   PENDING ──claim──▶ RUNNING ──xong──▶ DONE
 *      ▲                  │
 *      │                  ├──lỗi────────▶ FAILED
 *      └──lease hết hạn───┤
 *                         └──người huỷ──▶ CANCELLED
 *
 *   PAUSED ──claim──▶ RUNNING        (tiến trình chết giữa chừng, job chờ được nhặt lại)
 * </pre>
 *
 * <h2>{@link #PAUSED} là trạng thái của một job bị bỏ rơi, không phải của một job bị tạm dừng</h2>
 * Quy tắc 5 của {@code frplan.md} đòi job <b>chạy tiếp được sau khi restart</b>. Khi một
 * instance API chết giữa lúc chạy job, không ai đặt được trạng thái cho nó — nó nằm lại ở
 * {@code RUNNING} với một lease đã hết hạn. {@link JobRunner} nhặt những job như vậy về
 * {@code PAUSED} rồi claim lại từ {@code cursor_state} đã lưu.
 *
 * <p>Đây đúng là cơ chế của reaper ở {@code judge_queue}, áp cho một bảng khác. Cùng một lý
 * do: không có nó thì một lần restart là một job treo vĩnh viễn, và
 * {@code ux_jobs_one_active_per_type} sẽ chặn mọi job cùng loại về sau.
 */
public enum JobStatus {

    PENDING,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED;

    /** Đang chiếm chỗ trong {@code ux_jobs_one_active_per_type}. */
    public boolean dangSong() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }

    /** Đã kết thúc — {@code ck_jobs_finished} đòi {@code finished_at} khác NULL. */
    public boolean daKetThuc() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    /** Có thể được {@link JobRunner} nhặt lên chạy. */
    public boolean cothechay() {
        return this == PENDING || this == PAUSED;
    }

    public static JobStatus fromCode(String code) {
        for (JobStatus s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Trạng thái job không hợp lệ: " + code);
    }
}
