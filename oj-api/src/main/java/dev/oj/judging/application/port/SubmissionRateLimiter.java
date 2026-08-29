package dev.oj.judging.application.port;

/**
 * FR-SUB-08 — 1 bài / 10 giây / người dùng. Bước 4.7.
 *
 * <h2>Vì sao là một port chứ không phải một lời gọi Redis thẳng trong use-case</h2>
 * Vì {@code SubmitSolutionUseCase} là đoạn code quan trọng nhất hệ thống và nó phải test được
 * bằng JUnit trần, không Redis. Nhưng lý do nặng hơn: giới hạn này là <b>quy tắc nghiệp vụ</b>
 * ({@code oj-api/CLAUDE.md} mục 8), còn Redis là một chi tiết triển khai — và chi tiết ấy đã
 * được thiết kế sẵn một đường dự phòng Postgres ({@code docs/sql/duong_nong.sql} truy vấn 7).
 * Một interface là chỗ để nói rằng hai đường đó cùng phục vụ một quy tắc.
 *
 * <h2>Ngân sách</h2>
 * Đây là một chặng <b>mới</b> trên đường nộp bài, và câu hỏi 6 của {@code CLAUDE.md} mục 4 bắt
 * phải trả lời nó lấy thời gian từ đâu. Trả lời: một lệnh {@code SET NX PX} tới Redis cùng máy
 * tốn dưới 1ms, và nó nằm trong ngân sách 300ms của P2 mà không phải cắt của ai. Đường dự
 * phòng Postgres là một index-only scan trên {@code ix_submissions_user_recent} — cũng dưới
 * 1ms, và chỉ chạy khi Redis chết.
 */
public interface SubmissionRateLimiter {

    /**
     * Ghi nhận một lượt nộp và từ chối nếu quá nhanh.
     *
     * <p><b>Vừa kiểm vừa chiếm chỗ trong một thao tác</b>, không tách làm hai. Tách ra thì hai
     * request song song của cùng một người cùng đọc thấy "chưa nộp gì" rồi cùng đi tiếp — và
     * giới hạn không còn là giới hạn. {@code SET NX} của Redis nguyên tử theo đúng nghĩa đó.
     *
     * @throws dev.oj.judging.domain.JudgingException {@code RATE_LIMITED} kèm
     *         {@code retryAfter}, để {@code GlobalExceptionHandler} trả 429 với header
     *         {@code Retry-After}
     */
    void kiemTraVaGhiNhan(long userId);
}
