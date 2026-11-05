package dev.oj.it;

import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.usecase.AuthorContestUseCase;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.domain.ProblemNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XoaDeIT extends PostgresIT {

    @Autowired AuthorProblemUseCase authorProblem;
    @Autowired AuthorContestUseCase authorContest;
    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired dev.oj.problems.application.port.ProblemAuthoringRepository problemsRepo;

    private static final Instant MOC = Instant.now();

    private long deNhap() {
        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            return authorProblem.tao(new AuthorProblemUseCase.Command(
                    "xoa-" + System.nanoTime(), "Đề để xoá", "Nội dung", 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, (BigDecimal) null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false));
        }
    }

    private void xoaBoi(long problemId, long userId, String handle, Role vaiTro) {
        try (var phien = GiaLapDanhTinh.dongVai(userId, handle, vaiTro)) {
            assertThat(phien).isNotNull();
            authorProblem.xoa(problemId);
        }
    }

    private int demDe(long problemId) {
        return jdbc.sql("SELECT count(*) FROM problems WHERE id = :id")
                .param("id", problemId).query(Integer.class).single();
    }

    private void themBaiNop(long problemId) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (repeat('a', 64), 'int main(){}', 12)
                ON CONFLICT (sha256) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id,
                                         source_sha256, source_bytes, status)
                VALUES (:u, :p, 1, repeat('a', 64), 12, 'QUEUED')
                """).param("u", USER_ID).param("p", problemId).update();
    }

    private long kyThi(Instant batDau, Instant ketThuc) {
        return contests.tao(new ContestRepository.ContestMoi(
                "xoa-de-" + System.nanoTime(), "Thi thử", "ICPC",
                batDau, ketThuc, null, 20, true, true, SETTER_ID));
    }

    @Test
    @DisplayName("★ bản nháp chưa ai đụng tới thì xoá được")
    void ban_nhap_xoa_duoc() {
        long id = deNhap();

        xoaBoi(id, SETTER_ID, "setter", Role.SETTER);

        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("★ đề đã có bài nộp thì KHÔNG xoá được — bài nộp là lịch sử của người khác")
    void de_da_co_bai_nop_thi_khong_xoa_duoc() {
        long id = deNhap();
        themBaiNop(id);

        assertThatThrownBy(() -> xoaBoi(id, SETTER_ID, "setter", Role.SETTER))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "problem.da_co_bai_nop");

        assertThat(demDe(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ đề thuộc kỳ thi ĐÃ KẾT THÚC vẫn không xoá được")
    void de_thuoc_ky_thi_da_ket_thuc_van_khong_xoa_duoc() {
        long id = deNhap();
        long ky = kyThi(MOC.minus(Duration.ofHours(4)), MOC.minus(Duration.ofHours(1)));
        soanKyThi.themDe(ky, id, "A", 100, ADMIN_ID, true);

        assertThatThrownBy(() -> xoaBoi(id, SETTER_ID, "setter", Role.SETTER))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "problem.dang_thuoc_ky_thi");

        assertThat(demDe(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ SETTER khác không xoá được đề của người ra đề này")
    void setter_khac_khong_xoa_duoc() {
        long id = deNhap();

        assertThatThrownBy(() -> xoaBoi(id, USER_ID, "dev", Role.SETTER))
                .isInstanceOf(ProblemNotFoundException.class);

        assertThat(demDe(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ câu DELETE tự nó cũng từ chối người không sở hữu")
    void cau_delete_tu_no_cung_chan() {
        long id = deNhap();

        assertThat(problemsRepo.xoa(id, USER_ID, false))
                .describedAs("người không sở hữu, không phải admin → 0 dòng bị xoá")
                .isFalse();
        assertThat(demDe(id)).isEqualTo(1);

        assertThat(problemsRepo.xoa(id, SETTER_ID, false)).isTrue();
        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("ADMIN xoá được đề của người khác")
    void admin_xoa_duoc() {
        long id = deNhap();

        xoaBoi(id, ADMIN_ID, "admin", Role.ADMIN);

        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("★ gỡ khỏi kỳ thi rồi thì xoá được — hết ngõ cụt")
    void go_khoi_ky_thi_roi_thi_xoa_duoc() {
        long id = deNhap();
        long ky = kyThi(MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));
        soanKyThi.themDe(ky, id, "A", 100, ADMIN_ID, true);

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            authorContest.goDeKhoiKyThi(ky, id);
        }

        assertThat(contests.deCua(ky)).isEmpty();
        xoaBoi(id, SETTER_ID, "setter", Role.SETTER);
        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("kỳ thi đã bắt đầu thì không gỡ đề ra được")
    void ky_thi_da_bat_dau_thi_khong_go_duoc() {
        long id = deNhap();
        long ky = kyThi(MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)));
        soanKyThi.themDe(ky, id, "A", 100, ADMIN_ID, true);

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            assertThatThrownBy(() -> authorContest.goDeKhoiKyThi(ky, id))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "contest.da_bat_dau");
        }

        assertThat(contests.deCua(ky)).hasSize(1);
    }
}
