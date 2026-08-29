package dev.oj.problems.api;

import dev.oj.problems.domain.Problem;

/**
 * Hình dạng của một đề khi ra khỏi HTTP.
 *
 * <p>{@code CLAUDE.md} mục 7: <i>"Entity không rời {@code domain}. Không bao giờ trả entity
 * trực tiếp ra HTTP."</i> Lý do rất cụ thể chứ không phải nghi thức: {@link Problem} có
 * {@code ownerId}, và ngày mai nó sẽ có thêm gì đó nữa. Trả thẳng entity nghĩa là mỗi lần
 * thêm một cột vào domain, bạn vô tình xuất bản nó ra internet mà không ai review bước đó.
 * Với DTO thì thêm một trường là một dòng có chủ ý, nằm trong diff.
 *
 * <h2>Ba thứ cố ý vắng mặt</h2>
 * <ul>
 *   <li>{@code ownerId} — ai soạn đề không phải việc của người giải. Ở M5 nó còn là thông tin
 *       nhạy cảm: biết ai ra đề contest là một lợi thế.</li>
 *   <li>{@code currentTestdataVersion} — chi tiết nội bộ. Server tự đóng dấu vào bài nộp;
 *       client không cần biết và không được phép chọn.</li>
 *   <li>Mọi thứ liên quan testcase ẩn — không có ở đây, và cũng không có ở {@link Problem}.</li>
 * </ul>
 *
 * <h2>Vì sao {@code feedbackLevel} thì lại CÓ mặt</h2>
 * Cùng lý do với rate limit ở FR-SUB-08: đây là <b>quy tắc nghiệp vụ được công bố</b>, không
 * phải cơ chế ẩn. Người dùng nên biết trước rằng đề này sẽ chỉ báo "sai ở test 7" chứ không
 * cho xem gì thêm — biết trước thì họ hiểu, còn im lặng thì họ tưởng hệ thống lỗi. Công bố
 * mức phản hồi không làm yếu nó đi chút nào: nó không lộ <i>nội dung</i> nào cả.
 *
 * @param statement Markdown thô ở M1. M4 đổi thành HTML đã render và cache theo
 *                  {@code statement_hash} (FR-PROB-02) — render LaTeX mỗi request là lãng phí
 *                  thuần, và cũng là chỗ dễ dính XSS nhất nếu render ở client
 */
public record ProblemResponse(
        long problemId,
        String code,
        String title,
        String statement,
        String statementHtml,
        int timeLimitMs,
        int memoryLimitKb,
        String checkerType,
        String scoringMode,
        String feedbackLevel,
        boolean acceptsSubmissions) {

    /**
     * ★ Bước 4.9 — {@code statementHtml} là bản đã render server-side (FR-PROB-02), và
     * {@code statement} là Markdown gốc.
     *
     * <p>Giữ cả hai vì chúng phục vụ hai người khác nhau: trang đề dùng HTML (đã escape, an
     * toàn để nhúng), còn trang <b>soạn đề</b> cần lại đúng thứ tác giả đã gõ. Trả một cái rồi
     * dựng lại cái kia ở client là dựng lại sai.
     *
     * <p>{@code problemId} có mặt vì {@code POST /api/v1/submissions} nhận id chứ không nhận
     * mã đề, nên không có nó thì trang đề không nộp bài được. Đây không phải thông tin mới:
     * {@code SubmissionDetailResponse} đã trả cùng con số đó từ M1. Đường dẫn công khai vẫn
     * dùng {@code code} — id chỉ nằm trong thân phản hồi, nơi nó không mời gọi việc dò tuần tự.
     *
     * <p>HTML ở đây <b>đã escape mọi HTML thô</b> —
     * {@code CommonMarkStatementRenderer.escapeHtml(true)}. Các đoạn {@code $...$} đi qua
     * nguyên vẹn để KaTeX vẽ ở trình duyệt.
     */
    public static ProblemResponse from(Problem problem, String statementHtml) {
        return new ProblemResponse(
                problem.id(),
                problem.code(),
                problem.title(),
                problem.statementMd(),
                statementHtml,
                problem.timeLimitMs(),
                problem.memoryLimitKb(),
                problem.checkerType().code(),
                problem.scoringMode().name(),
                problem.feedbackLevel().name(),
                problem.acceptsSubmissions());
    }

    // checkerEpsilon cố ý không có mặt: nó chỉ có nghĩa với checker 'float', và một trường
    // luôn null cho mọi đề khác là nhiễu. Nếu M4 thấy cần hiện sai số cho người dùng, ghép
    // nó vào phần mô tả checker chứ đừng thêm một trường rỗng.

    // -------------------------------------------------------------------------
    // M4 thêm: tags, độ khó, đã giải hay chưa, và các testcase SAMPLE
    // (FR-PROB-04 — đọc từ sample_testcase_contents, bảng mà theo ràng buộc khoá ngoại
    // tổng hợp ở V2 KHÔNG THỂ chứa test ẩn; xem truy vấn 10 của duong_nong.sql).
    // Không thêm sớm: mỗi trường ở đây là một lần nữa phải hỏi "cái này lộ gì".
    // -------------------------------------------------------------------------
}
