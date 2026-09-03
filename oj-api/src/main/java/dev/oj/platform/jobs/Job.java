package dev.oj.platform.jobs;

import java.time.Instant;
import java.util.Map;

/**
 * Một job nền — Quy tắc 5 của {@code frplan.md}: <i>mọi thao tác có thể vượt 5 giây là job nền
 * có tiến độ và chạy tiếp được sau khi restart</i>.
 *
 * <h2>★ {@code cursorState} là thứ làm cho "chạy tiếp được" thành sự thật</h2>
 * Tiến độ nằm trong <b>database</b>, không trong bộ nhớ tiến trình. Một job nạp 1000 testcase
 * chết ở testcase thứ 700 sẽ chạy tiếp từ 700, không phải từ đầu — và với một file ZIP 200MB
 * thì đó là khác biệt giữa "chờ thêm một phút" và "làm lại từ đầu lúc 2 giờ sáng".
 *
 * <p>Hình dạng của {@code cursorState} do <b>handler</b> tự định nghĩa; khung này chỉ lưu và
 * trả lại. Ví dụ {@code {"testcaseCuoi": 700}}.
 *
 * @param totalItems  {@code null} khi handler chưa biết tổng — ví dụ trước khi đọc xong mục lục
 *                    của file ZIP. UI hiện "đang chuẩn bị" thay vì một thanh tiến độ nói dối
 * @param leaseOwner  instance nào đang giữ job. Hai instance API không được cùng chạy một job,
 *                    và {@code ux_jobs_one_active_per_entity} một mình không đủ — nó chỉ chặn
 *                    hai job, không chặn hai instance chạy cùng một job
 * @param errorMessage <b>câu cho người vận hành đọc</b>. Không đưa stack trace vào đây: bảng
 *                    này ADMIN xem được (FR-ADM-02) và nó chịu đúng bất biến #9 như log
 */
public record Job(
        long id,
        JobType type,
        JobStatus status,
        Map<String, Object> params,
        Map<String, Object> cursorState,
        Integer totalItems,
        int doneItems,
        String leaseOwner,
        Instant leaseUntil,
        Long createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage) {

    public Job {
        if (type == null || status == null) {
            throw new NullPointerException("type và status bắt buộc");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
        cursorState = cursorState == null ? Map.of() : Map.copyOf(cursorState);
    }

    /**
     * Phần trăm hoàn thành, hoặc {@code null} khi chưa biết tổng.
     *
     * <p>Trả {@code null} chứ không trả 0: một thanh tiến độ đứng ở 0% trông giống hệt một job
     * treo, và người dùng sẽ bấm chạy lại.
     */
    public Integer phanTram() {
        if (totalItems == null || totalItems <= 0) {
            return null;
        }
        return (int) Math.min(100L, Math.round(doneItems * 100.0 / totalItems));
    }
}
