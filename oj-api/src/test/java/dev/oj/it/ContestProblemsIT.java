package dev.oj.it;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Gắn đề vào kỳ thi — vi phạm ràng buộc phải thành CÂU NÓI, không thành HTTP 500.
 *
 * <h2>Vì sao file này tồn tại</h2>
 * {@code contest_problems} có ba ràng buộc, và trước bản vá này chỉ MỘT trong ba được dịch:
 *
 * <ul>
 *   <li>{@code UNIQUE (contest_id, label)} — đã dịch từ đầu.</li>
 *   <li>{@code PRIMARY KEY (contest_id, problem_id)} — không bao giờ nổ, vì {@code THEM_DE}
 *       có {@code ON CONFLICT DO UPDATE}. Thêm lại cùng một đề là <i>sửa</i> nó.</li>
 *   <li>{@code FOREIGN KEY (problem_id)} — <b>không ai dịch</b>. Một id gõ nhầm rơi ra ngoài
 *       như {@link DataIntegrityViolationException} và người dùng nhận "Có lỗi phía hệ
 *       thống", cho một lỗi hoàn toàn phía người gõ.</li>
 * </ul>
 *
 * <p>Ca thật đã gặp: người ra đề tạo một đề mang <b>mã</b> {@code "15"}, rồi gõ {@code 15}
 * vào ô nhận <b>id</b>. Đề đó có id 3. Không dòng log nào nói ra điều ấy, và trang chỉ hiện
 * một khung đỏ nói rằng lỗi nằm ở phía máy chủ — nên người dùng đi tìm sai chỗ.
 *
 * <p>Bài học rộng hơn: khoá ngoại là một chốt <i>hoàn hảo</i> nhưng chỉ <i>một nửa</i>. Nó
 * bắt được mọi trường hợp, và nó không nói được trường hợp nào. Nửa còn lại là bản dịch.
 */
class ContestProblemsIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired AuthorProblemUseCase authorProblem;

    private static final Instant MOC = Instant.now();

    /** Kỳ thi chưa mở — {@code AuthorContestUseCase} chỉ cho gắn đề trước giờ bắt đầu. */
    private long kyThiChuaMo() {
        return contests.tao(new ContestRepository.ContestMoi(
                "gan-de-" + System.nanoTime(), "Thi thử", "ICPC",
                MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)), null,
                20, true, true, ADMIN_ID));
    }

    private long deMoi(String ma) {
        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            return authorProblem.tao(new AuthorProblemUseCase.Command(
                    ma, "Đề " + ma, "Nội dung đề " + ma, 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, (BigDecimal) null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false));
        }
    }

    // =========================================================================

    /**
     * ★ Ca hồi quy của lỗi thật.
     *
     * <p>Khẳng định hai thứ, và cả hai đều cần: <b>kiểu</b> ngoại lệ phải là
     * {@link DomainException} (mã lỗi có, HTTP 4xx), và <b>câu chữ</b> phải nhắc lại con số
     * người dùng vừa gõ. Một câu "Không có đề nào" không kèm số thì với người vừa gõ nhầm
     * nó vẫn là một lời phủ nhận không kiểm chứng được.
     */
    @Test
    @DisplayName("★ id đề không có thật là LỖI NGƯỜI DÙNG, không phải lỗi hệ thống")
    void id_de_khong_co_that_khong_thanh_loi_he_thong() {
        long ky = kyThiChuaMo();

        assertThatThrownBy(() -> contests.themDe(ky, 999_999L, "A", 1, 100))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "contest.de_khong_ton_tai")
                .hasMessageContaining("999999");
    }

    /**
     * Bản dịch mới KHÔNG được nuốt bản dịch cũ: {@code DuplicateKeyException} là con của
     * {@code DataIntegrityViolationException}, nên đặt nhầm thứ tự hai nhánh {@code catch}
     * là mọi nhãn trùng đột nhiên báo "đề không tồn tại".
     */
    @Test
    @DisplayName("nhãn trùng vẫn báo đúng câu của nó, không bị nhánh mới nuốt")
    void nhan_trung_van_bao_dung_cau() {
        long ky = kyThiChuaMo();
        long deKhac = deMoi("gan-de-" + System.nanoTime());

        contests.themDe(ky, PROBLEM_ID, "A", 1, 100);

        assertThatThrownBy(() -> contests.themDe(ky, deKhac, "A", 2, 100))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "contest.nhan_de_trung");
    }

    /**
     * Ghi lại hành vi {@code ON CONFLICT DO UPDATE} thành một khẳng định, vì nó là lý do
     * nhánh {@code catch} mới KHÔNG cần lo cho khoá chính. Nếu ai đó bỏ mệnh đề ấy đi, ca
     * này đỏ ngay — thay vì để một "đề này đã có rồi" giả xuất hiện ở nhánh mới.
     */
    @Test
    @DisplayName("thêm lại cùng một đề là CẬP NHẬT nhãn và điểm, không phải lỗi")
    void them_lai_cung_de_la_cap_nhat() {
        long ky = kyThiChuaMo();

        contests.themDe(ky, PROBLEM_ID, "A", 1, 100);
        contests.themDe(ky, PROBLEM_ID, "B", 2, 250);

        assertThat(contests.deCua(ky))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.label()).isEqualTo("B");
                    assertThat(d.points()).isEqualTo(250);
                });
    }
}
