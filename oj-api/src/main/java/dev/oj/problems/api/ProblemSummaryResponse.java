package dev.oj.problems.api;

import dev.oj.problems.application.port.ProblemRepository;

/**
 * Một dòng trong danh sách đề — FR-PROB-09.
 *
 * <h2>Không có {@code statement} và không có {@code statementHtml}</h2>
 * Một trang 50 đề mà mỗi đề cõng theo vài chục KB nội dung là vài megabyte cho một danh sách
 * mà người dùng chỉ đọc tiêu đề. Câu query cũng không {@code SELECT} cột ấy
 * ({@code postgres-design.md} mục 15) — hai đầu khớp nhau, và đó là chủ ý chứ không phải trùng hợp.
 *
 * @param daGiai FR-PROB-09. Với khách chưa đăng nhập thì luôn {@code false} — không phải một
 *               lời khẳng định về họ, mà là giá trị duy nhất có nghĩa khi chưa biết họ là ai
 */
public record ProblemSummaryResponse(
        String code,
        String title,
        int timeLimitMs,
        int memoryLimitKb,
        boolean daGiai) {

    public static ProblemSummaryResponse from(ProblemRepository.ProblemListItem item) {
        return new ProblemSummaryResponse(item.code(), item.title(),
                item.timeLimitMs(), item.memoryLimitKb(), item.daGiai());
    }
}
