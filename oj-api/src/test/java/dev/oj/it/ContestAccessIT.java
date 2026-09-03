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

    /**
     * ★ Trang DANH SÁCH kỳ thi không được lộ đề — Bước G4, FR-CON-01.
     *
     * <h2>Vì sao ca này nằm ở file "ai được thấy gì", không ở một file phân trang</h2>
     * {@code GetContestUseCase} đã giấu danh sách đề tới giờ bắt đầu, và
     * {@link TruyCapDe} bên dưới canh điều đó. Nhưng endpoint danh sách là một
     * đường thứ hai tới cùng dữ liệu, và nó là đường mà một người chưa đăng nhập dùng để
     * <i>tìm</i> kỳ thi — tức là đường dễ bị quên nhất khi rà soát.
     *
     * <p>Cách hỏng cụ thể mà ca này chặn: ai đó tái dùng {@code ContestResponses.ChiTiet}
     * cho trang danh sách "cho đỡ phải viết DTO mới". Nó biên dịch được, trang chạy đẹp, và
     * mã đề của mọi kỳ thi chưa mở nằm sẵn trong JSON.
     */
    @Nested
    @DisplayName("★ FR-CON-01 · danh sách kỳ thi")
    class DanhSach {

        @Autowired dev.oj.contests.application.usecase.ListContestsUseCase listContests;

        @Test
        @DisplayName("★ DTO tóm tắt KHÔNG có chỗ nào để nhét danh sách đề")
        void tom_tat_khong_mang_duoc_de() {
            var thanhPhan = dev.oj.contests.api.ContestResponses.TomTat.class
                    .getRecordComponents();

            for (var tp : thanhPhan) {
                assertThat(java.util.Collection.class.isAssignableFrom(tp.getType()))
                        .as("trường '%s' là một tập hợp — trang danh sách chỉ được mang giá trị "
                                + "đơn. Một List ở đây là chỗ để danh sách đề lọt vào, và nó sẽ "
                                + "lọt qua đúng endpoint mà khách chưa đăng nhập dùng để tìm "
                                + "kỳ thi", tp.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("kỳ thi chưa mở vẫn hiện trong lịch — giấu đề, không giấu kỳ thi")
        void chua_mo_van_hien_trong_lich() {
            long id = dungContest(MOC.plus(Duration.ofHours(2)),
                    MOC.plus(Duration.ofHours(5)), null);

            var trang = listContests.thucHien(null, 50);

            assertThat(trang.items())
                    .as("giấu đề KHÔNG có nghĩa là giấu kỳ thi — người ta phải xem được lịch "
                            + "để quyết định có đăng ký không")
                    .anyMatch(t -> t.id() == id
                            && t.trangThai() == dev.oj.contests.application.usecase
                                    .ListContestsUseCase.TrangThai.SAP_DIEN_RA);
        }

        @Test
        @DisplayName("phân trang cursor: trang sau không lặp dòng của trang trước")
        void phan_trang_khong_lap() {
            for (int i = 0; i < 4; i++) {
                dungContest(MOC.plus(Duration.ofHours(2)), MOC.plus(Duration.ofHours(5)), null);
            }

            var trang1 = listContests.thucHien(null, 2);
            assertThat(trang1.items()).hasSize(2);
            assertThat(trang1.nextCursor()).isNotNull();

            var trang2 = listContests.thucHien(trang1.nextCursor(), 2);

            assertThat(trang2.items()).extracting(t -> t.id())
                    .doesNotContainAnyElementsOf(
                            trang1.items().stream().map(t -> t.id()).toList());
        }

        @Test
        @DisplayName("xin 1000 thì nhận trần, không nhận lỗi")
        void xin_qua_nhieu_thi_bi_cat() {
            dungContest(MOC.plus(Duration.ofHours(2)), MOC.plus(Duration.ofHours(5)), null);

            assertThat(listContests.thucHien(null, 1000).items().size())
                    .isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("cursor rác trả trang đầu, không ném lỗi")
        void cursor_rac_khong_no() {
            dungContest(MOC.plus(Duration.ofHours(2)), MOC.plus(Duration.ofHours(5)), null);

            assertThat(listContests.thucHien("khong-phai-so", 5).items()).isNotEmpty();
        }
    }

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
