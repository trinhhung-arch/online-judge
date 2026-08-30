package dev.oj.platform.contest;

import java.util.OptionalLong;

/**
 * ★ Lịch thi, hỏi từ bên ngoài module {@code contests} — Bước 5.3.
 *
 * <h2>Vì sao interface này ở {@code platform} chứ không ở {@code contests}</h2>
 * Bốn nơi cần biết một đề có đang nằm trong kỳ thi hay không:
 *
 * <ul>
 *   <li>{@code problems} — cấm sửa đề đang thi (FR-PROB-11) và khoá đề ngoài giờ (FR-CON-03)</li>
 *   <li>{@code judging} — gán {@code submissions.contest_id}, và cấm nộp ngoài giờ</li>
 *   <li>{@code ai} — tắt AI review trong contest (FR-AI-02, tuần 14–15)</li>
 *   <li>{@code judging} lần nữa — cấm rejudge khi contest đang chạy (FR-ADM-01, M6)</li>
 * </ul>
 *
 * <p>Nhưng chiều phụ thuộc là {@code problems → judging → contests}: cả bốn đều nằm
 * <b>trước</b> {@code contests}, nên không nơi nào import được nó (luật ArchUnit 3). Đây đúng
 * là tình huống mà {@code ArchitectureTest} đã ghi chú sẵn từ M0 và chỉ ra cách đi:
 * <i>đặt interface ở {@code platform}, để {@code contests.infrastructure} hiện thực</i>.
 * Đồ thị module vẫn không có chu trình.
 *
 * <h2>Interface này nói SỰ THẬT, không nói CHÍNH SÁCH — trừ một chỗ</h2>
 * Ba phương thức đầu trả về dữ kiện. {@link #deBiKhoaBoiLichThi} thì trả về một quyết định, và
 * đó là cố ý: câu hỏi <i>"người này có được xem đề này không"</i> phụ thuộc vào đăng ký, khung
 * giờ, và cờ {@code reveal_after_end} — <b>toàn bộ đều là dữ liệu của {@code contests}</b>.
 * Bắt {@code problems} tự ghép ba dữ kiện lại là chép luật thi vào một module không sở hữu nó,
 * và bản chép sẽ lệch vào ngày luật đổi.
 *
 * <h2>Không có contest nào thì mọi câu trả lời phải là "tự do"</h2>
 * Phần lớn đề của hệ thống không thuộc kỳ thi nào. Hiện thực phải rẻ và phải trả lời "không
 * bị khoá" cho chúng — {@code ix_contest_problems_problem} tồn tại cho đúng việc đó.
 */
public interface ContestWindowQuery {

    /**
     * Kỳ thi <b>đang diễn ra</b> có chứa đề này.
     *
     * <p>Dùng ở đường nộp bài để gán {@code submissions.contest_id}. <b>Suy ra từ máy chủ, không
     * nhận từ client</b>: nếu client khai contest thì nó khai được contest khác, hoặc khai
     * không có contest nào để bài của mình không vào bảng xếp hạng.
     */
    OptionalLong contestDangChayChuaDe(long problemId);

    /**
     * Đề này có đang nằm trong một kỳ thi đang diễn ra không — <b>câu của Bước 5.3</b>.
     *
     * <p>Ba nơi dùng: cấm sửa đề (FR-PROB-11), tắt AI review (FR-AI-02), cấm rejudge
     * (FR-ADM-01). Cả ba đều hỏi cùng một câu và phải nhận cùng một câu trả lời — nếu không
     * thì sẽ có một đường đi vòng qua đúng một trong ba.
     */
    default boolean deDangTrongContestDangChay(long problemId) {
        return contestDangChayChuaDe(problemId).isPresent();
    }

    /**
     * FR-CON-03 — đề của kỳ thi chỉ truy cập được trong khung giờ, và chỉ bởi người đã đăng ký.
     *
     * @param userId       {@code null} nếu khách chưa đăng nhập
     * @param laNguoiRaDe  người gọi là SETTER sở hữu đề, hoặc ADMIN. Họ luôn xem được —
     *                     ma trận hiển thị của {@code oj-api/CLAUDE.md} mục 2, dòng
     *                     "Đề trong contest chưa mở"
     * @return {@code true} nghĩa là <b>từ chối</b>, và người gọi phải trả 404 chứ không 403:
     *         403 xác nhận đề tồn tại và đang thuộc một kỳ thi, mà đó chính là thứ không được
     *         lộ trước giờ thi
     */
    boolean deBiKhoaBoiLichThi(long problemId, Long userId, boolean laNguoiRaDe);
}
