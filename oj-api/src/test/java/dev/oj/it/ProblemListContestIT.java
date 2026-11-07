package dev.oj.it;

import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.application.usecase.ListProblemsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemListContestIT extends PostgresIT {

    @Autowired ContestRepository contests;

    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired ListProblemsUseCase listProblems;
    @Autowired AuthorProblemUseCase authorProblem;

    private static final Instant MOC = Instant.now();

    private long dungContestChua(long problemId, Instant batDau, Instant ketThuc) {
        long id = contests.tao(new ContestRepository.ContestMoi(
                "ds-de-" + System.nanoTime(), "Thi thử", "ICPC",
                batDau, ketThuc, null, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, problemId, "A", 100, ADMIN_ID, true);
        return id;
    }

    private List<String> maDeThayBoi(long userId, String handle, Role vaiTro) {
        try (var phien = GiaLapDanhTinh.dongVai(userId, handle, vaiTro)) {
            assertThat(phien).isNotNull();
            return listProblems.thucHien(null, null, null, 50).items()
                    .stream().map(d -> d.code()).toList();
        }
    }

    private List<String> maDeThayBoiThiSinh() {
        return maDeThayBoi(USER_ID, "dev", Role.USER);
    }

    @Test
    @DisplayName("★ đề của kỳ thi CHƯA MỞ không nằm trong danh sách đề")
    void de_cua_ky_thi_chua_mo_vang_mat() {
        assertThat(maDeThayBoiThiSinh()).contains("A-PLUS-B");

        dungContestChua(PROBLEM_ID, MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));

        assertThat(maDeThayBoiThiSinh()).doesNotContain("A-PLUS-B");
    }

    @Test
    @DisplayName("★ FR-CON-07 — đề tự quay lại danh sách sau khi kỳ thi kết thúc")
    void de_quay_lai_sau_khi_ky_thi_ket_thuc() {
        dungContestChua(PROBLEM_ID, MOC.minus(Duration.ofHours(4)), MOC.minus(Duration.ofHours(1)));

        assertThat(maDeThayBoiThiSinh()).contains("A-PLUS-B");
    }

    @Test
    @DisplayName("thí sinh ĐÃ đăng ký thấy đề trong giờ thi")
    void nguoi_da_dang_ky_thay_de_trong_gio_thi() {
        long ky = dungContestChua(PROBLEM_ID,
                MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)));

        assertThat(maDeThayBoiThiSinh()).doesNotContain("A-PLUS-B");

        contests.dangKy(ky, USER_ID, MOC);

        assertThat(maDeThayBoiThiSinh()).contains("A-PLUS-B");
    }

    @Test
    @DisplayName("người ra đề và ADMIN vẫn thấy đề bị khoá")
    void nguoi_ra_de_va_admin_van_thay() {
        dungContestChua(PROBLEM_ID, MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));

        assertThat(maDeThayBoi(SETTER_ID, "setter", Role.SETTER)).contains("A-PLUS-B");
        assertThat(maDeThayBoi(ADMIN_ID, "admin", Role.ADMIN)).contains("A-PLUS-B");
    }

    @Test
    @DisplayName("★ phân trang không co lại khi có đề bị khoá")
    void phan_trang_khong_co_lai() {
        long b = deMoi("ds-b-" + System.nanoTime());
        long c = deMoi("ds-c-" + System.nanoTime());
        assertThat(c).isGreaterThan(b);

        dungContestChua(c, MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));

        try (var phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER)) {
            assertThat(phien).isNotNull();
            var trang = listProblems.thucHien(null, null, null, 2);
            assertThat(trang.items())
                    .describedAs("xin 2 đề thì phải nhận đủ 2, không phải 1 vì một đề bị khoá")
                    .hasSize(2);
            assertThat(trang.items()).noneSatisfy(
                    d -> assertThat(d.id()).isEqualTo(c));
        }
    }

    private long deMoi(String ma) {
        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            long id = authorProblem.tao(new AuthorProblemUseCase.Command(
                    ma, "Đề " + ma, "Nội dung đề " + ma, 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, (BigDecimal) null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false));
            jdbc.sql("UPDATE problems SET current_testdata_version = 1 WHERE id = :id")
                    .param("id", id).update();
            authorProblem.xuatBan(id);
            return id;
        }
    }
}
