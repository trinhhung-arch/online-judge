package dev.oj.it;

import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.infrastructure.JdbcStandingsReader;
import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.identity.application.usecase.AnonymizeAccountUseCase;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase.Outcome;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BangXepHangBoSungIT extends PostgresIT {

    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsReader standings;
    @Autowired JdbcStandingsReader bangTuPostgres;
    @Autowired StandingsUpdater updater;
    @Autowired GetStandingsUseCase getStandings;
    @Autowired AnonymizeAccountUseCase anonymize;
    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeProgressUseCase recordProgress;
    @Autowired RecordJudgeResultUseCase recordResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;
    @Autowired AppProperties props;

    private final Instant bayGio = Instant.now();

    @Test
    @DisplayName("★ M2 · thí sinh bị ẩn danh hoá sau kỳ thi: dòng, điểm và hạng giữ nguyên, tên thành [đã xoá #id] — kể cả sau khi dựng lại (frplan 3.5)")
    void m2_an_danh_hoa_khong_lam_mat_dong_tren_bang() {
        long id = kyThi(bayGio.minus(Duration.ofHours(3)), bayGio.minus(Duration.ofHours(1)),
                PROBLEM_ID);
        long biXoa = nguoi("se-bi-xoa");
        long nguoiKhac = nguoi("nguoi-o-lai");
        nopAc(id, biXoa, PROBLEM_ID);
        nopAc(id, nguoiKhac, PROBLEM_ID);
        updater.capNhat(doc(id));
        StandingsReader.Dong truoc = standings.cuaNguoi(id, biXoa, false).orElseThrow();
        int hangTruoc = standings.hang(id, biXoa, false).orElseThrow();

        try (var admin = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
            anonymize.thucHien(biXoa);
            assertThat(admin).isNotNull();
        }

        assertThat(standings.cuaNguoi(id, biXoa, false)).hasValueSatisfying(sau -> {
            assertThat(sau.displayName()).isEqualTo("[đã xoá #" + biXoa + "]");
            assertThat(sau.tongDiem()).isEqualTo(truoc.tongDiem());
            assertThat(sau.penaltyGiay()).isEqualTo(truoc.penaltyGiay());
            assertThat(sau.soBaiDat()).isEqualTo(1);
        });
        assertThat(standings.hang(id, biXoa, false)).hasValue(hangTruoc);

        updater.xoaTrang(id);
        updater.capNhat(doc(id));

        assertThat(standings.top(id, 50, false)).extracting(StandingsReader.Dong::userId)
                .as("xoá cứng một người từng có hạng là làm sai vĩnh viễn bảng của kỳ thi ấy")
                .containsExactlyInAnyOrder(biXoa, nguoiKhac);
        assertThat(standings.cuaNguoi(id, biXoa, false).orElseThrow().tongDiem())
                .isEqualTo(truoc.tongDiem());
    }

    @Test
    @DisplayName("★ M2 · người đã ẩn danh hoá: cả sáu câu đọc bảng (thật và đóng băng) không trả handle; người còn lại vẫn có handle")
    void m2_bang_khong_tra_handle_cua_nguoi_da_an_danh() {
        long id = kyThi(bayGio.minus(Duration.ofHours(3)), bayGio.minus(Duration.ofHours(1)),
                PROBLEM_ID);
        long biXoa = nguoi("handle-phai-bien-mat");
        long nguoiKhac = nguoi("nguoi-o-lai");
        nopAc(id, biXoa, PROBLEM_ID);
        nopAc(id, nguoiKhac, PROBLEM_ID);
        updater.capNhat(doc(id));
        try (var admin = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
            anonymize.thucHien(biXoa);
            assertThat(admin).isNotNull();
        }

        for (boolean dongBang : new boolean[]{false, true}) {
            List<StandingsReader.Dong> moiDong = new java.util.ArrayList<>(
                    bangTuPostgres.top(id, 50, dongBang));
            moiDong.add(bangTuPostgres.cuaNguoi(id, biXoa, dongBang).orElseThrow());
            bangTuPostgres.quanhNguoi(id, nguoiKhac, 2, dongBang)
                    .forEach(d -> moiDong.add(d.dong()));

            assertThat(moiDong).as("đóng băng = %s", dongBang).hasSize(5).allSatisfy(d -> {
                if (d.userId() == biXoa) {
                    assertThat(d.handle()).as("handle cũ đủ để nhận ra người đã xin xoá").isNull();
                    assertThat(d.displayName()).isEqualTo("[đã xoá #" + biXoa + "]");
                } else {
                    assertThat(d.handle()).isEqualTo("nguoi-o-lai");
                }
            });
        }
    }

    @Test
    @DisplayName("★ M4 · hai kỳ thi chạy song song: bài của kỳ thi này không vào bảng kỳ thi kia, và không làm bảng kia tính lại")
    void m4_hai_ky_thi_song_song_khong_lan_bang() {
        long deB = deKhac("DE-SONG-SONG");
        long a = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)),
                PROBLEM_ID);
        long b = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), deB);
        long chiThiA = nguoi("chi-thi-a");
        long chiThiB = nguoi("chi-thi-b");
        long thiCaHai = nguoi("thi-ca-hai");
        nopAc(a, chiThiA, PROBLEM_ID);
        nopAc(b, chiThiB, deB);
        nopAc(a, thiCaHai, PROBLEM_ID);
        nopAc(b, thiCaHai, deB);

        assertThat(updater.capNhat(doc(a))).isEqualTo(2);
        assertThat(nguoiTrenBang(a)).containsExactlyInAnyOrder(chiThiA, thiCaHai);
        assertThat(nguoiTrenBang(b)).as("B chưa tới nhịp của mình thì chưa có gì").isEmpty();

        assertThat(updater.capNhat(doc(b))).isEqualTo(2);
        assertThat(nguoiTrenBang(b)).containsExactlyInAnyOrder(chiThiB, thiCaHai);

        nopAc(b, chiThiA, deB);
        assertThat(updater.capNhat(doc(a))).as("bài mới của B không phải việc của A").isZero();
        assertThat(updater.capNhat(doc(b))).isEqualTo(1);

        assertThat(standings.top(a, 50, false)).allSatisfy(d ->
                assertThat(d.soBaiDat()).as("người %s trên bảng A", d.handle()).isEqualTo(1));
        assertThat(nguoiTrenBang(a)).containsExactlyInAnyOrder(chiThiA, thiCaHai);
    }

    @Test
    @DisplayName("M6 · kỳ thi tới giờ mà không có đề nào: bảng trống, đọc bảng không lỗi, hết ân hạn vẫn chốt")
    void m6_ky_thi_khong_co_de() {
        long id = contests.tao(new ContestRepository.ContestMoi("khong-de-" + System.nanoTime(),
                "Kỳ thi quên gắn đề", "ICPC", bayGio.minus(Duration.ofHours(1)),
                bayGio.plus(Duration.ofHours(2)), null, 20, true, true, ADMIN_ID));
        contests.dangKy(id, USER_ID, bayGio.minus(Duration.ofHours(2)));

        assertThat(contests.deCua(id)).isEmpty();
        assertThat(updater.capNhat(doc(id))).isZero();
        assertThat(getStandings.thucHien(id).top()).isEmpty();
        assertThat(getStandings.thucHien(id).dongCuaToi()).isEmpty();

        jdbc.sql("""
                UPDATE contests SET starts_at = now() - interval '3 hours',
                                    ends_at   = now() - interval '10 minutes'
                 WHERE id = :id
                """).param("id", id).update();
        updater.nhip();

        assertThat(contests.canCapNhat(Instant.now())).extracting(Contest::id)
                .as("kỳ thi rỗng không được nằm mãi trong danh sách cần cập nhật")
                .doesNotContain(id);
    }

    @Test
    @DisplayName("★ M7 · bài chấm lâu gấp ba lease trong kỳ thi mà worker còn sống: bảng chờ nó, bài nhận verdict thật ở attempt 1 (không IE), rồi cả lô vào bảng (ADR 026)")
    void m7_bai_cham_lau_trong_ky_thi() {
        long id = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)),
                PROBLEM_ID);
        contests.dangKy(id, USER_ID, bayGio.minus(Duration.ofHours(2)));
        long lau = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        int attempt = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow().attempt();
        long keSau = nguoi("nop-sau-bai-lau");
        nopAc(id, keSau, PROBLEM_ID);

        for (int lo = 1; lo <= 3; lo++) {
            troiGanHetMotLease(lau);
            recordProgress.record(new JudgeProgressDto(lau, attempt, lo * 20, lo * 20, 100,
                    List.of(new JudgeProgressDto.TestOutcome(lo * 20, Verdict.AC, 12, 2048))));

            assertThat(reapStaleJobs.reap()).as("lô %d: worker còn sống thì không thu hồi", lo)
                    .isZero();
            assertThat(updater.capNhat(doc(id))).as("lô %d: bài phía sau đứng chờ", lo).isZero();
        }
        assertThat(standings.top(id, 50, false)).isEmpty();

        assertThat(recordResult.record(new JudgeResultDto(lau, attempt, Verdict.AC, 100, 100,
                null, 3, 23, 8192, null, null, "mac-m1max-host", new BigDecimal("1.000"),
                Instant.now(), List.of()))).isEqualTo(Outcome.RECORDED);

        assertThat(updater.capNhat(doc(id))).isEqualTo(2);
        assertThat(nguoiTrenBang(id)).containsExactlyInAnyOrder(USER_ID, keSau);
        assertThat(jdbc.sql("SELECT verdict || ' ' || attempt FROM submissions WHERE id = :s")
                .param("s", lau).query(String.class).single()).isEqualTo("AC 1");
    }

    private long kyThi(Instant batDau, Instant ketThuc, long problemId) {
        long id = contests.tao(new ContestRepository.ContestMoi("bo-sung-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, null, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, problemId, "A", 100, ADMIN_ID, true);
        return id;
    }

    private void troiGanHetMotLease(long submissionId) {
        jdbc.sql("""
                UPDATE judge_queue
                   SET lease_until = lease_until - make_interval(secs => :lease - 5)
                 WHERE submission_id = :s
                """).param("lease", props.judge().leaseSeconds()).param("s", submissionId)
                .update();
    }

    private Contest doc(long id) {
        return contests.timTheoId(id).orElseThrow();
    }

    private List<Long> nguoiTrenBang(long contestId) {
        return standings.top(contestId, 50, false).stream()
                .map(StandingsReader.Dong::userId).toList();
    }

    private long nguoi(String handle) {
        return jdbc.sql("""
                INSERT INTO users (handle, email, display_name) VALUES (:h, :email, :h)
                RETURNING id
                """).param("h", handle).param("email", handle + "@oj.test")
                .query(Long.class).single();
    }

    private long deKhac(String code) {
        return jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                VALUES (:code, 'Đề của kỳ thi B', 'x', :hash, 1000, 262144, 65536, 'token',
                        'ALL_OR_NOTHING', 'TEST_INDEX', 'PUBLISHED', :owner, now(), 1)
                RETURNING id
                """).param("code", code).param("hash", "c".repeat(64)).param("owner", SETTER_ID)
                .query(Long.class).single();
    }

    private void nopAc(long contestId, long userId, long problemId) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """).param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, now())
                """).param("u", userId).param("p", problemId).param("c", contestId)
                .param("sha", NGUON).update();
    }
}
