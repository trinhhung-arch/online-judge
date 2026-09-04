package dev.oj.judging.application.usecase;

import dev.oj.platform.contest.ContestWindowQuery;

import java.util.List;
import java.util.OptionalLong;

/**
 * {@link ContestWindowQuery} giả cho unit test — mặc định <b>không có kỳ thi nào</b>.
 *
 * <h2>Vì sao mặc định là "tự do"</h2>
 * Phần lớn đề của hệ thống không thuộc kỳ thi nào, và phần lớn test cũng vậy. Một bản giả mặc
 * định trả "đang thi" sẽ làm hàng chục ca đỏ vì một lý do không liên quan tới thứ chúng kiểm.
 *
 * <p>Test nào cần kiểm hành vi trong kỳ thi thì đặt {@link #contestDangChay} — và việc phải
 * đặt tường minh là điều tốt: nó làm ca kiểm ấy nói rõ nó đang giả định gì.
 */
final class LichThiGia implements ContestWindowQuery {

    /** {@code null} = đề không nằm trong kỳ thi nào đang chạy. */
    Long contestDangChay;

    /** Đặt {@code true} để giả lập FR-CON-03 từ chối truy cập. */
    boolean biKhoa;

    /** FR-ADM-01: có kỳ thi nào đang chạy không — chốt của {@code StartRejudgeUseCase}. */
    boolean coKyThi;

    int soLanHoi;

    @Override
    public OptionalLong contestDangChayChuaDe(long problemId) {
        soLanHoi++;
        return contestDangChay == null ? OptionalLong.empty() : OptionalLong.of(contestDangChay);
    }

    @Override
    public boolean coKyThiDangChay() {
        return coKyThi;
    }

    @Override
    public boolean deBiKhoaBoiLichThi(long problemId, Long userId, boolean laNguoiRaDe) {
        return biKhoa && !laNguoiRaDe;
    }

    /** Danh sách đề bị khoá; mặc định rỗng, đúng nghĩa "không có kỳ thi nào". */
    List<Long> idBiKhoa = List.of();

    /** Đề có thuộc kỳ thi nào không — chốt của {@code AuthorProblemUseCase.xoa}. */
    boolean thuocKyThiNaoDo;

    @Override
    public boolean deNamTrongKyThiNaoDo(long problemId) {
        return thuocKyThiNaoDo;
    }

    @Override
    public List<Long> deBiKhoaChoNguoiXem(Long userId) {
        return idBiKhoa;
    }
}
