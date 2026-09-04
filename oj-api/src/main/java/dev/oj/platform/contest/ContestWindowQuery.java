package dev.oj.platform.contest;

import java.util.List;
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
     * Có <b>bất kỳ</b> kỳ thi nào đang diễn ra không — FR-ADM-01, Bước 6.3.
     *
     * <h2>Vì sao câu này rộng hơn {@link #deDangTrongContestDangChay} chứ không dùng lại nó</h2>
     * Rejudge một đề <i>không</i> thuộc kỳ thi nào vẫn phá kỳ thi đang chạy: nó đẩy hàng nghìn
     * bài vào cùng {@code judge_queue}, và dù {@code priority = 10} cho chúng xếp sau, sáu
     * judge slot vẫn là sáu — bài của thí sinh chờ lâu hơn ({@code frplan.md} mâu thuẫn 3.2).
     *
     * <p>Nói cách khác: chốt của FR-PROB-11 hỏi <i>"đề này có đang thi không"</i>, còn chốt
     * của FR-ADM-01 hỏi <i>"máy chấm có đang bận việc không được phép trễ không"</i>. Hai câu
     * hỏi khác nhau, và gộp chúng lại là mở một đường vòng qua một trong hai.
     */
    boolean coKyThiDangChay();

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

    /**
     * FR-CON-03 + FR-CON-07 — <b>dạng danh sách</b> của {@link #deBiKhoaBoiLichThi}.
     *
     * <h2>Vì sao cần một phương thức riêng thay vì gọi cái trên cho từng dòng</h2>
     * Trang danh sách đề trả 20–50 dòng. Hỏi từng dòng là N+1 truy vấn trên một trang ai cũng
     * mở. Nhưng lý do quan trọng hơn là <b>phân trang</b>: lọc sau khi đã lấy đủ 20 dòng làm
     * trang co lại còn 17, và con trỏ trang thì vẫn nhảy như thể đã trả 20 — người dùng mất
     * ba đề mà không có dấu hiệu nào. Điều kiện phải nằm TRONG câu query.
     *
     * <h2>Vì sao trả về danh sách id chứ không nhận vào rồi lọc</h2>
     * Luật thi thuộc về {@code contests}: đăng ký, khung giờ, {@code registration_required}.
     * Chép ba dữ kiện ấy vào câu SQL của {@code problems} là chép luật vào module không sở
     * hữu nó, và bản chép sẽ lệch vào ngày luật đổi — đúng điều javadoc của lớp này cảnh báo.
     * Trả về id thì luật ở nguyên một chỗ, còn {@code problems} chỉ biết "loại các id này".
     *
     * <p>Tập trả về nhỏ theo bản chất: nó chỉ gồm đề của các kỳ thi <b>chưa kết thúc</b>.
     * Kỳ thi đã xong không nằm trong đó — đó chính là FR-CON-07 "mở đề ra ngoài", và nó xảy
     * ra tự động khi đồng hồ đi qua {@code ends_at}, không cần ai bấm nút.
     *
     * @param userId {@code null} nếu khách chưa đăng nhập
     * @return id của mọi đề đang bị khoá với người xem này; rỗng là trường hợp thường gặp nhất
     */
    List<Long> deBiKhoaChoNguoiXem(Long userId);

    /**
     * Đề này có nằm trong <b>bất kỳ</b> kỳ thi nào không — kể cả kỳ thi đã kết thúc.
     *
     * <h2>Vì sao rộng hơn cả {@link #deDangTrongContestDangChay} lẫn {@link #deBiKhoaBoiLichThi}</h2>
     * Hai câu kia hỏi <i>"bây giờ có được xem/sửa không"</i>, và câu trả lời đổi theo đồng hồ.
     * Câu này hỏi <i>"có được XOÁ HẲN không"</i>, và câu trả lời không được đổi theo đồng hồ:
     * một kỳ thi đã kết thúc từ năm ngoái vẫn có bảng xếp hạng, và bảng ấy vẫn có cột mang
     * nhãn của đề này. Xoá đề đi là làm thủng một bảng xếp hạng không ai sửa lại được.
     *
     * <p>Đây là dữ kiện, không phải chính sách: nó chỉ nói đề có dòng trong
     * {@code contest_problems} hay không, còn quyết định từ chối nằm ở {@code problems}.
     */
    boolean deNamTrongKyThiNaoDo(long problemId);
}
