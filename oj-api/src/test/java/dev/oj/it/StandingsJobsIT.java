package dev.oj.it;

import dev.oj.contests.application.RebuildStandingsJob;
import dev.oj.contests.application.StandingsDriftCheckJob;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.domain.Contest;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Hai job giữ bất biến của M5 — FR-CON-08 và FR-CON-09.
 *
 * <h2>Vì sao {@code oj-api/CLAUDE.md} mục 6 bắt job rebuild PHẢI có test</h2>
 * Bất biến của mốc này là <i>"Redis là cache, {@code contest_standings} là sự thật"</i>. Câu
 * ấy chỉ là một lời nói cho tới khi có ai chứng minh rằng bảng xếp hạng <b>dựng lại được</b>
 * từ Postgres. Không có ca kiểm ở đây thì ngày phải dựng lại thật là ngày đầu tiên biết là
 * không dựng lại được — và ngày đó, theo định nghĩa, là một ngày đã hỏng.
 *
 * <p>Cùng lý do với job đối soát: nó tồn tại để phát hiện lệch, nên nó phải được chứng minh là
 * <b>phát hiện được</b> lệch. Một job đối soát luôn báo "không lệch" thì không phân biệt được
 * với một job không chạy.
 */
class StandingsJobsIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired StandingsReader standings;
    @Autowired dev.oj.contests.application.port.StandingsRepository standingsRepo;
    @Autowired StandingsUpdater updater;
    @Autowired RebuildStandingsJob rebuild;
    @Autowired StandingsDriftCheckJob drift;
    @Autowired SubmitSolutionUseCase submitSolution;

    private long contestId;

    @BeforeEach
    void dungKyThiCoDuLieu() {
        contestId = contests.tao(new ContestRepository.ContestMoi(
                "thi-job-" + System.nanoTime(), "Thi thử", "ICPC",
                Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofHours(2)), null,
                20, true, true, ADMIN_ID));
        contests.themDe(contestId, PROBLEM_ID, "A", 1, 100);
        contests.dangKy(contestId, USER_ID, Instant.now());

        nopVaCham("WA", 0);
        nopVaCham("AC", 100);
        updater.capNhat(contest());
    }

    private Contest contest() {
        return contests.timTheoId(contestId).orElseThrow();
    }

    private void nopVaCham(String verdict, int score) {
        long id;
        try (var phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER)) {
            quenLuotNopVuaRoi(USER_ID);
            id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                    PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
            assertThat(phien).isNotNull();
        }
        jdbc.sql("""
                UPDATE submissions
                   SET status = 'DONE', verdict = :v, score = :s, max_score = 100, judged_at = now()
                 WHERE id = :id
                """).param("v", verdict).param("s", score).param("id", id).update();
    }

    private JobContextGia ctx() {
        return new JobContextGia(Map.of("contestId", contestId), ADMIN_ID);
    }

    private int soDongLechDaGhi() {
        return jdbc.sql("""
                SELECT COALESCE(max(rows_mismatched), -1) FROM standings_drift_checks
                 WHERE contest_id = :id
                """).param("id", contestId).query(Integer.class).single();
    }

    // =========================================================================

    @Test
    @DisplayName("★ FR-CON-09 — kỳ thi đang chạy lọt vào danh sách cần soát")
    void ky_thi_dang_chay_can_soat() {
        Instant bayGio = Instant.now();

        assertThat(standingsRepo.canSoatLech(bayGio,
                bayGio.minus(Duration.ofDays(7)), bayGio.minus(Duration.ofMinutes(15))))
                .contains(contestId);
    }

    @Test
    @DisplayName("★ vừa soát xong thì KHÔNG soát lại — chốt chống quét lặp")
    void vua_soat_thi_thoi() {
        Instant bayGio = Instant.now();
        // Mỗi lần soát là một lần tính lại toàn bộ kỳ thi từ submissions. Không có chốt này
        // thì cửa sổ bảy ngày với nhịp mười lăm phút là gần bảy trăm lần quét cho mỗi kỳ thi.
        drift.chay(ctx());

        assertThat(standingsRepo.canSoatLech(bayGio,
                bayGio.minus(Duration.ofDays(7)), bayGio.minus(Duration.ofMinutes(15))))
                .doesNotContain(contestId);
    }

    @Test
    @DisplayName("bản soát CŨ hơn một nhịp thì phải soát lại — lệch tới muộn sau rejudge")
    void ban_soat_cu_thi_soat_lai() {
        drift.chay(ctx());
        Instant bayGio = Instant.now();

        // `chuaSoatTu` ở tương lai: mọi bản soát đều "cũ hơn" nó.
        assertThat(standingsRepo.canSoatLech(bayGio,
                bayGio.minus(Duration.ofDays(7)), bayGio.plus(Duration.ofMinutes(1))))
                .contains(contestId);
    }

    @Test
    @DisplayName("kỳ thi ngoài cửa sổ thì coi như đã chốt, không soát nữa")
    void ngoai_cua_so_thi_thoi() {
        Instant bayGio = Instant.now();

        // `ketThucSau` ở tương lai: không kỳ thi nào kết thúc sau mốc ấy.
        assertThat(standingsRepo.canSoatLech(bayGio,
                bayGio.plus(Duration.ofDays(1)), bayGio.minus(Duration.ofMinutes(15))))
                .doesNotContain(contestId);
    }

    @Test
    @DisplayName("★ FR-CON-08 — xoá sạch rồi dựng lại cho ra ĐÚNG bảng cũ")
    void dung_lai_cho_ra_dung_bang_cu() {
        List<StandingsReader.Dong> truoc = standings.top(contestId, 50, false);
        assertThat(truoc).isNotEmpty();

        rebuild.chay(ctx());

        // Đây là toàn bộ bằng chứng cho câu "Postgres là sự thật". Nếu ca này đỏ, câu ấy sai.
        assertThat(standings.top(contestId, 50, false)).isEqualTo(truoc);
    }

    @Test
    @DisplayName("★ dựng lại SỬA được một bảng đã bị làm hỏng")
    void dung_lai_sua_duoc_bang_hong() {
        jdbc.sql("UPDATE contest_standings SET total_score = 999, penalty_seconds = 0 "
                + "WHERE contest_id = :id").param("id", contestId).update();

        rebuild.chay(ctx());

        assertThat(standings.top(contestId, 50, false)).singleElement()
                .satisfies(d -> assertThat(d.tongDiem()).isEqualTo(1));
    }

    @Test
    @DisplayName("dựng lại KHÔNG dùng logic riêng — chạy hai lần cho cùng kết quả")
    void dung_lai_hai_lan_giong_nhau() {
        rebuild.chay(ctx());
        var lanMot = standings.top(contestId, 50, false);
        rebuild.chay(ctx());

        assertThat(standings.top(contestId, 50, false)).isEqualTo(lanMot);
    }

    @Test
    @DisplayName("★ FR-CON-09 — bảng đúng thì đối soát báo 0 dòng lệch")
    void doi_soat_bang_dung() {
        drift.chay(ctx());

        assertThat(soDongLechDaGhi()).isZero();
    }

    @Test
    @DisplayName("★ FR-CON-09 — bảng bị làm hỏng thì đối soát PHÁT HIỆN ra")
    void doi_soat_phat_hien_lech() {
        jdbc.sql("UPDATE contest_standings SET total_score = 999 WHERE contest_id = :id")
                .param("id", contestId).update();

        drift.chay(ctx());

        // Một job đối soát luôn báo "không lệch" thì không phân biệt được với một job không chạy.
        assertThat(soDongLechDaGhi()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ đối soát chỉ ĐO, không tự sửa")
    void doi_soat_khong_tu_sua() {
        jdbc.sql("UPDATE contest_standings SET total_score = 999 WHERE contest_id = :id")
                .param("id", contestId).update();

        drift.chay(ctx());

        // Tự sửa nghe hấp dẫn nhưng sai: nếu chính nó tính sai thì nó vừa phá bảng đúng, và
        // thứ hạng đổi giữa kỳ thi mà không ai biết vì sao. Sửa là việc của RebuildStandingsJob.
        assertThat(jdbc.sql("SELECT total_score FROM contest_standings WHERE contest_id = :id")
                .param("id", contestId).query(Integer.class).single()).isEqualTo(999);
    }

    @Test
    @DisplayName("đối soát ghi lại mẫu dòng lệch để người vận hành đọc")
    void doi_soat_ghi_mau() {
        jdbc.sql("UPDATE contest_standings SET solved_count = 7 WHERE contest_id = :id")
                .param("id", contestId).update();

        drift.chay(ctx());

        assertThat(jdbc.sql("""
                SELECT detail::text FROM standings_drift_checks
                 WHERE contest_id = :id ORDER BY id DESC LIMIT 1
                """).param("id", contestId).query(String.class).single())
                .contains("soDongLech").contains(String.valueOf(USER_ID));
    }
}
