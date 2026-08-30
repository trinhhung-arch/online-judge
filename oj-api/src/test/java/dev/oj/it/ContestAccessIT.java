package dev.oj.it;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Ai được vào đâu, và khi nào — M5, FR-CON-02, FR-CON-03, và FR-PROB-11.
 *
 * <p>Bảng xếp hạng nằm ở {@link ContestStandingsIT} — đường cắt theo <b>câu hỏi</b>, không
 * theo số dòng: đây hỏi <i>"ai được thấy gì"</i>, bên kia hỏi <i>"điểm tính thế nào"</i>.
 *
 * <h2>Vì sao gần hết các ca ở đây là ca TỪ CHỐI</h2>
 * Một kỳ thi bán sự công bằng, và sự công bằng bị phá bởi những thứ trông rất giống lòng tốt:
 * cho đăng ký muộn, cho xem đề sớm, cho sửa đề giữa chừng, cho thấy bảng đã đóng băng. Bốn
 * thứ ấy đều tiện cho ai đó, và cả bốn đều là lỗ hổng.
 *
 * <p>Ca <i>cho phép</i> thì ít và dễ; ca <i>từ chối</i> mới là thứ hệ thống này phải giữ.
 */
class ContestAccessIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired GetProblemUseCase getProblem;
    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired dev.oj.problems.application.usecase.AuthorProblemUseCase authorProblem;

    private static final Instant MOC = Instant.now();

    /** Kỳ thi đang chạy, chứa đề A-PLUS-B. */
    private long dangChay() {
        return dungContest(MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)), null);
    }

    private long dungContest(Instant batDau, Instant ketThuc, Instant dongBang) {
        long id = contests.tao(new ContestRepository.ContestMoi(
                "thi-thu-" + System.nanoTime(), "Thi thử", "ICPC",
                batDau, ketThuc, dongBang, 20, true, true, ADMIN_ID));
        contests.themDe(id, PROBLEM_ID, "A", 1, 100);
        return id;
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-CON-02 · đăng ký")
    class DangKy {

        @Test
        @DisplayName("đăng ký trước giờ bắt đầu thì được")
        void truoc_gio_thi_duoc() {
            long id = dungContest(MOC.plus(Duration.ofHours(1)),
                    MOC.plus(Duration.ofHours(4)), null);

            contests.dangKy(id, USER_ID, MOC);

            assertThat(contests.daDangKy(id, USER_ID)).isTrue();
        }

        @Test
        @DisplayName("đăng ký hai lần → 409, không tạo dòng thứ hai")
        void dang_ky_hai_lan() {
            long id = dangChay();
            contests.dangKy(id, USER_ID, MOC);

            assertThatThrownBy(() -> contests.dangKy(id, USER_ID, MOC))
                    .hasFieldOrPropertyWithValue("code", "contest.da_dang_ky");
            assertThat(jdbc.sql("SELECT count(*) FROM contest_registrations WHERE contest_id = :id")
                    .param("id", id).query(Integer.class).single()).isEqualTo(1);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-CON-03 · đề chỉ mở trong khung giờ")
    class TruyCapDe {

        @Test
        @DisplayName("★ kỳ thi CHƯA MỞ → đề biến mất với mọi người, kể cả người đã đăng ký")
        void chua_mo_thi_de_bien_mat() {
            long id = dungContest(MOC.plus(Duration.ofHours(1)),
                    MOC.plus(Duration.ofHours(4)), null);
            contests.dangKy(id, USER_ID, MOC);

            // 404 chứ không 403: 403 xác nhận đề tồn tại và đang thuộc một kỳ thi, mà đó
            // chính là thứ không được lộ trước giờ thi.
            assertThatThrownBy(() -> getProblem.byCode("A-PLUS-B"))
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.NOT_FOUND);
        }

        @Test
        @DisplayName("★ đang chạy mà CHƯA đăng ký → vẫn không xem được")
        void chua_dang_ky_thi_khong_xem_duoc() {
            dangChay();

            assertThatThrownBy(() -> getProblem.byCode("A-PLUS-B"))
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.NOT_FOUND);
        }

        @Test
        @DisplayName("đang chạy và đã đăng ký → xem được")
        void da_dang_ky_thi_xem_duoc() {
            long id = dangChay();
            contests.dangKy(id, USER_ID, MOC);

            assertThat(getProblem.byCode("A-PLUS-B").code()).isEqualTo("A-PLUS-B");
        }

        @Test
        @DisplayName("★ ADMIN luôn xem được — ma trận hiển thị, dòng 'đề trong contest chưa mở'")
        void admin_luon_xem_duoc() {
            dungContest(MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)), null);

            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                assertThat(getProblem.byCode("A-PLUS-B")).isNotNull();
                assertThat(phien).isNotNull();
            }
        }

        @Test
        @DisplayName("kỳ thi ĐÃ KẾT THÚC → đề mở lại tự động, không ai phải bấm gì (FR-CON-07)")
        void ket_thuc_thi_de_mo_lai() {
            dungContest(MOC.minus(Duration.ofHours(4)), MOC.minus(Duration.ofHours(1)), null);

            assertThat(getProblem.byCode("A-PLUS-B")).isNotNull();
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-PROB-11 · cấm sửa đề đang trong kỳ thi")
    class CamSuaDe {

        @Test
        @DisplayName("★ SETTER không sửa được đề đang thi — 409, và đề không đổi")
        void khong_sua_duoc_de_dang_thi() {
            dangChay();
            String tieuDeCu = jdbc.sql("SELECT title FROM problems WHERE id = :id")
                    .param("id", PROBLEM_ID).query(String.class).single();

            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                assertThatThrownBy(() -> authorProblem.sua(PROBLEM_ID,
                        new dev.oj.problems.application.usecase.AuthorProblemUseCase.Command(
                                "A-PLUS-B", "Tiêu đề mới", "Nội dung mới", 1000, 262_144,
                                dev.oj.contract.CheckerType.TOKEN, null,
                                dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                                dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false)))
                        .hasFieldOrPropertyWithValue("code", "problem.dang_trong_ky_thi");
                assertThat(phien).isNotNull();
            }

            // Sửa đề giữa kỳ thi tạo ra hai nhóm thí sinh — nhóm đọc bản cũ và nhóm đọc bản
            // mới — và không có cách nào đền bù cho nhóm thứ nhất.
            assertThat(jdbc.sql("SELECT title FROM problems WHERE id = :id")
                    .param("id", PROBLEM_ID).query(String.class).single()).isEqualTo(tieuDeCu);
        }

        @Test
        @DisplayName("kỳ thi CHƯA MỞ thì vẫn sửa được — chỉ cấm khi ĐANG chạy")
        void chua_mo_thi_van_sua_duoc() {
            dungContest(MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)), null);

            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                authorProblem.sua(PROBLEM_ID,
                        new dev.oj.problems.application.usecase.AuthorProblemUseCase.Command(
                                "A-PLUS-B", "Sửa trước giờ thi", "Nội dung", 1000, 262_144,
                                dev.oj.contract.CheckerType.TOKEN, null,
                                dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                                dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false));
                assertThat(phien).isNotNull();
            }

            assertThat(jdbc.sql("SELECT title FROM problems WHERE id = :id")
                    .param("id", PROBLEM_ID).query(String.class).single())
                    .isEqualTo("Sửa trước giờ thi");
        }
    }

}
