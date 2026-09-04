package dev.oj.problems.application.port;

import dev.oj.platform.web.CursorPage;
import dev.oj.problems.domain.Problem;

import java.util.List;
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
 * <p><b>M4 đã tới, và lời dặn trên được thực hiện triệt để hơn một bước:</b> đường SETTER/ADMIN
 * không thêm hàm vào đây mà nằm ở một port <i>riêng</i>,
 * {@link ProblemAuthoringRepository}. Lý do là chính câu javadoc ở trên — tính chất
 * "mọi phương thức đều có chữ Published trong tên" chỉ có giá trị khi nó <b>đúng với mọi
 * phương thức</b>. Thêm một {@code findForAuthor} vào đây là tự tay xoá cái tính chất mà
 * đoạn văn này tồn tại để bảo vệ.
 */
public interface ProblemRepository {

    /** Dùng bởi {@code GET /api/v1/problems/{code}}. So sánh không phân biệt hoa thường. */
    Optional<Problem> findPublishedByCode(String code);

    /** Dùng khi nhận bài nộp: {@code SubmitSolutionUseCase} cần giới hạn và testdata version. */
    Optional<Problem> findPublishedById(long id);

    /** FR-PROB-09 — danh sách đề đã xuất bản, phân trang cursor-based. */
    CursorPage<ProblemListItem> danhSachDaXuatBan(ListFilter loc, Long cursor, int size);



    /**
     * @param daGiaiBoi lọc "đã giải" của FR-PROB-09. {@code null} = không lọc.
     *                  {@code true}/{@code false} cùng {@code requesterId} = chỉ đề đã/chưa giải
     * @param idBiKhoa  FR-CON-03 — id các đề bị lịch thi khoá với người xem này, do
     *                  {@code ContestWindowQuery} tính. <b>Không bao giờ rỗng</b>: người gọi
     *                  luôn thêm một id canh {@code 0}, vì {@code NOT IN ()} là lỗi cú pháp
     *                  SQL và {@code problems.id} bắt đầu từ 1 nên {@code 0} không loại ai
     */
    record ListFilter(String tagSlug, Long requesterId, Boolean daGiaiBoi, List<Long> idBiKhoa) {
    }

    /**
     * Một dòng trong danh sách đề. <b>Không có {@code statementMd}</b> — nó là cột TEXT lớn
     * nhất bảng, và kéo nó về cho 50 dòng là kéo về vài trăm KB không ai đọc
     * ({@code postgres-design.md} mục 15).
     */
    record ProblemListItem(
            long id, String code, String title,
            int timeLimitMs, int memoryLimitKb, boolean daGiai) {
    }
}
