package dev.oj.contests.application.port;

import dev.oj.contests.domain.Contest;

import java.util.Optional;

/**
 * ★ Soạn kỳ thi — gắn và gỡ đề. Mọi thao tác ở đây mang <b>người gọi</b> vào tận câu SQL.
 *
 * <h2>Vì sao tách khỏi {@link ContestRepository}</h2>
 * Bản trước để {@code themDe}/{@code goDe} ở {@code ContestRepository} và không nhận người gọi
 * nào, nên một SETTER bất kỳ sửa được kỳ thi của SETTER khác (rà bảo mật 2026-09-23):
 * <ul>
 *   <li>gỡ, đổi nhãn, đổi điểm đề trong kỳ thi sắp diễn ra của người khác;</li>
 *   <li>gắn một đề công khai của người khác vào kỳ thi của mình với {@code ends_at} năm 2099 —
 *       đề biến khỏi kho với mọi người (FR-CON-03), chủ đề không sửa được, và
 *       {@code deNamTrongKyThiNaoDo} làm nó không bao giờ xoá được nữa;</li>
 *   <li>gắn lần lượt từng id vào kỳ thi của mình rồi đọc trang kỳ thi để lấy mã của mọi đề,
 *       kể cả đề DRAFT và đề của kỳ thi chưa mở.</li>
 * </ul>
 * Tách hẳn ra — cùng mẫu {@code ProblemRepository} / {@code ProblemAuthoringRepository} — để
 * không còn một phương thức ghi {@code contest_problems} nào mà thiếu tham số người gọi.
 *
 * <h2>Điều kiện chủ sở hữu nằm TRONG câu SQL</h2>
 * {@code oj-api/CLAUDE.md} mục 2. {@code nguoiGoi}/{@code laAdmin} phải lấy từ
 * {@code CurrentUserProvider}, nơi ADMIN chưa bật 2FA đã bị hạ (ADR 017).
 */
public interface ContestAuthoringRepository {

    /**
     * Kỳ thi mà người gọi được soạn: chủ kỳ thi ({@code created_by}), hoặc ADMIN.
     *
     * @return rỗng với mọi người khác — <b>kể cả khi kỳ thi có thật</b>. Người gọi trả 404,
     *         không phải 403: 403 xác nhận "kỳ thi này tồn tại và là của người khác"
     */
    Optional<Contest> timDeSoan(long contestId, long nguoiGoi, boolean laAdmin);

    /**
     * Gắn một đề <b>mượn từ kho đề chung</b> vào kỳ thi. Cần sở hữu CẢ HAI — kỳ thi và đề —
     * hoặc là ADMIN. Gắn lại một đề đã có là đổi nhãn/điểm của nó, và cũng qua cùng chốt ấy.
     *
     * <p>Không có tham số thứ tự: {@code label} vừa là tên đề trong kỳ thi vừa <b>là</b> thứ
     * tự của nó (V12, ADR 015).
     *
     * @throws dev.oj.contests.domain.ContestsException {@code contest.de_khong_ton_tai} nếu
     *         đề không có thật <b>hoặc</b> không phải của người gọi — <b>cùng một câu</b>, để
     *         lỗi này không thành một cách dò xem id nào là đề có thật;
     *         {@code contest.nhan_de_trung} nếu nhãn đã dùng trong kỳ thi
     */
    void themDe(long contestId, long problemId, String label, int points,
                long nguoiGoi, boolean laAdmin);

    /**
     * Gắn một đề <b>vừa được soạn riêng cho kỳ thi này</b> (V10). Cùng chốt với
     * {@link #themDe} — đề vừa soạn thuộc về chính người gọi nên luôn qua.
     *
     * <p>Hai phương thức thay vì một tham số {@code boolean} vì đây là hai hành động khác
     * nhau: một bên lấy thứ đã có sẵn và có thể đang được người khác luyện tập, một bên tạo
     * ra thứ chưa ai thấy.
     */
    void themDeSoanRieng(long contestId, long problemId, String label, int points,
                         long nguoiGoi, boolean laAdmin);

    /**
     * Gỡ một đề khỏi kỳ thi. Không đụng tới bản thân đề. Cần sở hữu kỳ thi, hoặc là ADMIN.
     *
     * @return {@code false} nếu đề không nằm trong kỳ thi này, hoặc người gọi không được soạn nó
     */
    boolean goDe(long contestId, long problemId, long nguoiGoi, boolean laAdmin);
}
