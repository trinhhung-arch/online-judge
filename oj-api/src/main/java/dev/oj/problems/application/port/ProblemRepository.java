package dev.oj.problems.application.port;

import dev.oj.problems.domain.Problem;

import java.util.Optional;

/**
 * Port đọc đề cho <b>request của người dùng</b>. Hiện thực chạy trên pool {@code app} (20).
 *
 * <h2>Vì sao mọi phương thức đều có chữ "Published" trong tên</h2>
 * Bộ lọc trạng thái nằm <b>trong câu query</b>, không phải trong một câu {@code if} sau khi đã
 * load. Một hàm tên {@code findByCode} là lời mời gọi cho việc quên lọc, và lần quên đó sẽ
 * làm lộ đề {@code DRAFT} — rất thường là đề của contest tuần sau (FR-PROB-08).
 *
 * <p>Đây cũng chính là mẫu chống IDOR ở {@code oj-api/CLAUDE.md} mục 2: <i>"điều kiện lọc phải
 * nằm trong câu query của repository, không phải một câu if ở service sau khi đã load. Query
 * sai chỗ là lỗ hổng ngay cả khi câu if viết đúng."</i>
 *
 * <p>Ở M4, đường SETTER/ADMIN cần đọc cả đề chưa xuất bản. Lúc đó thêm
 * {@code findForAuthor(code, requesterId, role)} — <b>tên hàm nói rõ nó lấy nhiều hơn</b> —
 * chứ đừng nới lỏng các hàm dưới đây.
 */
public interface ProblemRepository {

    /** Dùng bởi {@code GET /api/v1/problems/{code}}. So sánh không phân biệt hoa thường. */
    Optional<Problem> findPublishedByCode(String code);

    /** Dùng khi nhận bài nộp: {@code SubmitSolutionUseCase} cần giới hạn và testdata version. */
    Optional<Problem> findPublishedById(long id);
}
