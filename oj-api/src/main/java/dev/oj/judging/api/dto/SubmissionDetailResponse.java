package dev.oj.judging.api.dto;

import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;

import java.time.Instant;

/**
 * {@code GET /api/v1/submissions/{id}} — FR-SUB-03, FR-SUB-04.
 *
 * <h2>★ {@code failedTestOrdinal} CỐ Ý VẮNG MẶT ở M1</h2>
 * {@link Submission} có con số đó, và trả nó ra là một dòng code. Nhưng bộ lọc theo
 * {@code problems.feedback_level} — thứ quyết định người dùng <i>được phép</i> thấy nó hay
 * không — là {@code FeedbackPolicy} ở M3.
 *
 * <p>Nếu M1 trả con số ra rồi M3 mới thêm bộ lọc, hệ thống sẽ có bốn tuần chạy với
 * {@code feedback_level} bị bỏ qua hoàn toàn. Với đề đặt mức {@code NONE} — thể thức ICPC —
 * đó đúng là đường rò rỉ mà FR-PROB-07 sinh ra để đóng, và {@code frplan.md} Phần 6 xếp
 * FR-PROB-07 vào danh sách <b>tuyệt đối không cắt</b>. Thứ tự đúng là <b>bộ lọc trước, dữ
 * liệu sau</b>.
 *
 * <p>Cùng lý do, không có ở đây: nội dung testcase (không tồn tại ở bất kỳ đâu trong
 * {@code oj-api}), {@code sourceSha256}, và log compiler (FR-SUB-06, cũng M3).
 *
 * @param timeMs   đã <b>làm tròn 10ms</b> — xem {@link #roundTo10ms(Integer)}
 * @param attempt  lần chấm thứ mấy. Có mặt để UI phân biệt "WA" với "WA · đang chấm lại":
 *                 một bài {@code JUDGING} mà vẫn có verdict cũ là bài đang rejudge
 */
public record SubmissionDetailResponse(
        long submissionId,
        long problemId,
        int languageId,
        String status,
        String verdict,
        Integer score,
        Integer maxScore,
        Integer timeMs,
        Integer memoryKb,
        int attempt,
        Instant createdAt,
        Instant judgedAt) {

    public static SubmissionDetailResponse from(Submission s) {
        JudgeOutcome outcome = s.outcome();
        return new SubmissionDetailResponse(
                s.id(),
                s.problemId(),
                s.languageId(),
                s.status().name(),
                outcome == null ? null : outcome.verdict().name(),
                outcome == null ? null : outcome.score(),
                outcome == null ? null : outcome.maxScore(),
                outcome == null ? null : roundTo10ms(outcome.timeMs()),
                outcome == null ? null : outcome.memoryKb(),
                s.attempt(),
                s.createdAt(),
                s.judgedAt());
    }

    /**
     * FR-SUB-11 · P7 — làm tròn đến <b>10ms</b>.
     *
     * <p>Độ lệch đo lường là ±5%, nên chữ số hàng mili giây là <b>nhiễu, không phải thông
     * tin</b>. Hiển thị nó tạo ra một trò chơi giả: người dùng nộp lại mười lần để "tối ưu"
     * từ 23ms xuống 21ms, trong khi thực tế họ chỉ đang lấy mẫu ngẫu nhiên — và tiêu mười
     * lượt chấm của cả hệ thống cho 0 giá trị.
     *
     * <p>Domain giữ số đo thật ({@code judge_runs.time_ms}) để còn đối chiếu khi hệ số máy
     * trôi; chỉ tầng này làm tròn. Ở Bước 3.12, {@code RuntimeFormatter} nhận việc này cùng
     * với chú thích <i>"đo trên máy chấm chuẩn, sai số ±5%"</i>.
     */
    static Integer roundTo10ms(Integer timeMs) {
        return timeMs == null ? null : (int) (Math.round(timeMs / 10.0) * 10);
    }
}
