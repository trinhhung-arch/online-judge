package dev.oj.it;

import dev.oj.contests.application.port.ContestAuthoringRepository;
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

class ContestProblemsIT extends PostgresIT {

    @Autowired ContestRepository contests;

    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired AuthorProblemUseCase authorProblem;
    @Autowired dev.oj.contests.application.usecase.AuthorContestUseCase author;

    private static final Instant MOC = Instant.now();

    private long kyThiChuaMo() {
        return contests.tao(new ContestRepository.ContestMoi(
                "gan-de-" + System.nanoTime(), "Thi thử", "ICPC",
                MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)), null,
                20, true, true, SETTER_ID));
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

    @Test
    @DisplayName("★ id đề không có thật là LỖI NGƯỜI DÙNG, không phải lỗi hệ thống")
    void id_de_khong_co_that_khong_thanh_loi_he_thong() {
        long ky = kyThiChuaMo();

        assertThatThrownBy(() -> soanKyThi.themDe(ky, 999_999L, "A", 100, ADMIN_ID, true))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "contest.de_khong_ton_tai")
                .hasMessageContaining("999999");
    }

    @Test
    @DisplayName("nhãn trùng vẫn báo đúng câu của nó, không bị nhánh mới nuốt")
    void nhan_trung_van_bao_dung_cau() {
        long ky = kyThiChuaMo();
        long deKhac = deMoi("gan-de-" + System.nanoTime());

        soanKyThi.themDe(ky, PROBLEM_ID, "A", 100, ADMIN_ID, true);

        assertThatThrownBy(() -> soanKyThi.themDe(ky, deKhac, "A", 100, ADMIN_ID, true))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "contest.nhan_de_trung");
    }

    @Test
    @DisplayName("★ danh sách đề sắp theo nhãn A, B, Z rồi AA — không theo thứ tự chèn")
    void thu_tu_de_la_thu_tu_nhan() {
        long ky = kyThiChuaMo();

        soanKyThi.themDe(ky, deMoi("thu-tu-aa-" + System.nanoTime()), "AA", 100, ADMIN_ID, true);
        soanKyThi.themDe(ky, deMoi("thu-tu-z-" + System.nanoTime()), "Z", 100, ADMIN_ID, true);
        soanKyThi.themDe(ky, PROBLEM_ID, "B", 100, ADMIN_ID, true);
        soanKyThi.themDe(ky, deMoi("thu-tu-a-" + System.nanoTime()), "A", 100, ADMIN_ID, true);

        assertThat(contests.deCua(ky))
                .extracting(ContestRepository.DeCuaContest::label)
                .describedAs("'AA' phải đứng SAU 'Z', không chen vào giữa 'A' và 'B'")
                .containsExactly("A", "B", "Z", "AA");
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Soạn đề riêng cho kỳ thi")
    class SoanDeRieng {

        private AuthorProblemUseCase.Command deMoi(String ma) {
            return new AuthorProblemUseCase.Command(
                    ma, "Đề " + ma, "Nội dung đề " + ma, 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, (BigDecimal) null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false);
        }

        @Test
        @DisplayName("★ một lần gọi tạo đề VÀ gắn vào kỳ thi, có đánh dấu nguồn gốc")
        void tao_de_va_gan_trong_mot_lan() {
            long ky = kyThiChuaMo();
            String ma = "rieng-" + System.nanoTime();

            long problemId;
            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                assertThat(phien).isNotNull();
                problemId = author.soanDeRieng(ky, deMoi(ma), "A", 100);
            }

            assertThat(contests.deCua(ky)).singleElement().satisfies(d -> {
                assertThat(d.problemId()).isEqualTo(problemId);
                assertThat(d.code()).isEqualTo(ma);
                assertThat(d.soanRieng())
                        .describedAs("đề sinh ra cho kỳ thi phải được đánh dấu là RIÊNG")
                        .isTrue();
            });
        }

        @Test
        @DisplayName("đề mượn từ kho KHÔNG bị đánh dấu là soạn riêng")
        void de_muon_khong_bi_danh_dau() {
            long ky = kyThiChuaMo();
            soanKyThi.themDe(ky, PROBLEM_ID, "A", 100, ADMIN_ID, true);

            assertThat(contests.deCua(ky)).singleElement()
                    .satisfies(d -> assertThat(d.soanRieng()).isFalse());
        }

        @Test
        @DisplayName("gắn đè bằng themDe không xoá được dấu 'soạn riêng'")
        void nguon_goc_dinh() {
            long ky = kyThiChuaMo();
            long problemId;
            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                assertThat(phien).isNotNull();
                problemId = author.soanDeRieng(ky, deMoi("dinh-" + System.nanoTime()),
                        "A", 100);
            }

            soanKyThi.themDe(ky, problemId, "B", 250, ADMIN_ID, true);

            assertThat(contests.deCua(ky)).singleElement().satisfies(d -> {
                assertThat(d.label()).isEqualTo("B");
                assertThat(d.soanRieng()).isTrue();
            });
        }

        @Test
        @DisplayName("★ gắn hỏng thì KHÔNG để lại đề mồ côi")
        void gan_hong_thi_khong_de_lai_de_mo_coi() {
            long ky = kyThiChuaMo();
            soanKyThi.themDe(ky, PROBLEM_ID, "A", 100, ADMIN_ID, true);
            String ma = "mo-coi-" + System.nanoTime();

            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                assertThat(phien).isNotNull();
                assertThatThrownBy(() -> author.soanDeRieng(ky, deMoi(ma), "A", 100))
                        .isInstanceOf(DomainException.class)
                        .hasFieldOrPropertyWithValue("code", "contest.nhan_de_trung");
            }

            assertThat(jdbc.sql("SELECT count(*) FROM problems WHERE code = :ma")
                    .param("ma", ma).query(Integer.class).single())
                    .describedAs("đề phải bị cuốn theo transaction, không nằm lại")
                    .isZero();
        }

        @Test
        @DisplayName("kỳ thi đã bắt đầu thì không soạn thêm đề được")
        void ky_thi_da_bat_dau_thi_thoi() {
            long ky = contests.tao(new ContestRepository.ContestMoi(
                    "dang-chay-" + System.nanoTime(), "Thi thử", "ICPC",
                    MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)), null,
                    20, true, true, SETTER_ID));

            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                assertThat(phien).isNotNull();
                assertThatThrownBy(() -> author.soanDeRieng(ky,
                        deMoi("muon-" + System.nanoTime()), "A", 100))
                        .isInstanceOf(DomainException.class)
                        .hasFieldOrPropertyWithValue("code", "contest.da_bat_dau");
            }
        }
    }

    @Test
    @DisplayName("★ AT-12 · kỳ thi đã bắt đầu: thêm đề, đổi nhãn và gỡ đề → 400 contest.da_bat_dau, danh sách đề không đổi")
    void at12_da_bat_dau_thi_khong_them_khong_go() {
        long ky = contests.tao(new ContestRepository.ContestMoi(
                "da-bat-dau-" + System.nanoTime(), "Thi thử", "ICPC",
                MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)), null,
                20, true, true, SETTER_ID));
        soanKyThi.themDe(ky, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        long deKhac = deMoi("sau-gio-" + System.nanoTime());

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            assertThatThrownBy(() -> author.themDe(ky, deKhac, "B", 100))
                    .hasFieldOrPropertyWithValue("code", "contest.da_bat_dau");
            assertThatThrownBy(() -> author.themDe(ky, PROBLEM_ID, "Z", 500))
                    .hasFieldOrPropertyWithValue("code", "contest.da_bat_dau");
            assertThatThrownBy(() -> author.goDeKhoiKyThi(ky, PROBLEM_ID))
                    .hasFieldOrPropertyWithValue("code", "contest.da_bat_dau");
        }

        assertThat(contests.deCua(ky)).singleElement().satisfies(d -> {
            assertThat(d.problemId()).isEqualTo(PROBLEM_ID);
            assertThat(d.label()).isEqualTo("A");
            assertThat(d.points()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("thêm lại cùng một đề là CẬP NHẬT nhãn và điểm, không phải lỗi")
    void them_lai_cung_de_la_cap_nhat() {
        long ky = kyThiChuaMo();

        soanKyThi.themDe(ky, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        soanKyThi.themDe(ky, PROBLEM_ID, "B", 250, ADMIN_ID, true);

        assertThat(contests.deCua(ky))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.label()).isEqualTo("B");
                    assertThat(d.points()).isEqualTo(250);
                });
    }
}
