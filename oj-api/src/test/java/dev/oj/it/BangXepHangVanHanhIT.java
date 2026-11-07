package dev.oj.it;

import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.application.usecase.RevealStandingsUseCase;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.infrastructure.JdbcStandingsReader;
import dev.oj.contests.infrastructure.RedisStandingsCache;
import dev.oj.contract.ClaimRequestDto;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class BangXepHangVanHanhIT extends PostgresIT {

    private static final Logger log = LoggerFactory.getLogger(BangXepHangVanHanhIT.class);
    private static final String NGUON = "a".repeat(64);
    private static final Duration MUC_TIEU_P8 = Duration.ofSeconds(2);
    private static final Duration BAO_DONG_P8 = Duration.ofSeconds(10);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsReader standings;
    @Autowired StandingsUpdater updater;
    @Autowired StandingsEventBus bus;
    @Autowired GetStandingsUseCase getStandings;
    @Autowired RevealStandingsUseCase reveal;
    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;
    @Autowired AppProperties props;
    @Autowired ObjectMapper json;

    private final Instant bayGio = Instant.now();

    private long kyThi(Instant batDau, Instant ketThuc, Instant dongBang) {
        long id = contests.tao(new ContestRepository.ContestMoi("van-hanh-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, dongBang, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private Contest doc(long id) {
        return contests.timTheoId(id).orElseThrow();
    }

    private long nguoi(String handle) {
        return jdbc.sql("""
                INSERT INTO users (handle, email, display_name) VALUES (:h, :email, :h)
                RETURNING id
                """).param("h", handle).param("email", handle + "@oj.test")
                .query(Long.class).single();
    }

    private void nopAc(long contestId, long userId) {
        taoNguon();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, now())
                """).param("u", userId).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).update();
    }

    private static void cho(BooleanSupplier xong, Duration han, String moTa) {
        long het = System.nanoTime() + han.toNanos();
        while (!xong.getAsBoolean()) {
            if (System.nanoTime() > het) {
                throw new AssertionError("Quá " + han + " vẫn chưa thấy " + moTa);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    @DisplayName("★ BX-06 · một bài kẹt JUDGING chặn mọi bài sau nó; bảng chỉ chạy tiếp khi reaper bỏ cuộc (≈ 3 lease × 120 s — cái giá ADR 019 đã nhận)")
    void bx06_bai_ket_chan_bang_toi_khi_reaper_xong() {
        long id = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), null);
        contests.dangKy(id, USER_ID, bayGio.minus(Duration.ofHours(2)));
        long ket = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        assertThat(claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64")))
                .hasValueSatisfying(job -> assertThat(job.submissionId()).isEqualTo(ket));
        long keSau = nguoi("ke-sau");
        nopAc(id, keSau);

        assertThat(updater.capNhat(doc(id))).as("bài AC phía sau phải đứng chờ").isZero();
        assertThat(standings.top(id, 50, false)).isEmpty();

        jdbc.sql("""
                UPDATE judge_queue SET lease_until = now() - interval '1 second', reap_count = :max
                 WHERE submission_id = :s
                """).param("max", props.judge().maxReaps()).param("s", ket).update();
        reapStaleJobs.reap();
        assertThat(jdbc.sql("SELECT verdict FROM submissions WHERE id = :s").param("s", ket)
                .query(String.class).single()).isEqualTo("IE");

        updater.capNhat(doc(id));
        assertThat(standings.top(id, 50, false)).anySatisfy(d -> {
            assertThat(d.userId()).isEqualTo(keSau);
            assertThat(d.soBaiDat()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("★ BX-07 · hết ân hạn mà vẫn còn bài chờ chấm: bảng chờ, bài chấm xong muộn vẫn vào bảng, rồi mới chốt")
    void bx07_cap_nhat_toi_khi_het_bai_cho() {
        long id = kyThi(bayGio.minus(Duration.ofHours(3)), bayGio.minus(Duration.ofMinutes(10)),
                null);
        String sha = taoNguon();
        long cho = jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version,
                                         created_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, now() - interval '15 minutes')
                RETURNING id
                """).param("u", USER_ID).param("p", PROBLEM_ID).param("c", id)
                .param("sha", sha).query(Long.class).single();
        jdbc.sql("INSERT INTO judge_queue (submission_id) VALUES (:s)").param("s", cho).update();
        long keSau = nguoi("cham-muon");
        nopAc(id, keSau);

        updater.nhip();
        assertThat(contests.canCapNhat(Instant.now())).extracting(Contest::id)
                .as("còn bài trong judge_queue thì chưa được chốt").contains(id);
        assertThat(standings.top(id, 50, false)).isEmpty();

        jdbc.sql("""
                WITH roi_hang_doi AS (DELETE FROM judge_queue WHERE submission_id = :s)
                UPDATE submissions SET status = 'DONE', verdict = 'WA', score = 0, max_score = 100,
                                       attempt = 1, judged_at = now()
                 WHERE id = :s
                """).param("s", cho).update();
        updater.nhip();
        updater.nhip();

        assertThat(standings.top(id, 50, false)).extracting(StandingsReader.Dong::userId)
                .containsExactlyInAnyOrder(USER_ID, keSau);
        assertThat(contests.canCapNhat(Instant.now())).extracting(Contest::id)
                .as("hết bài chờ và đã vào bảng hết thì chốt").doesNotContain(id);
    }

    private String taoNguon() {
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) "
                + "VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", NGUON).update();
        return NGUON;
    }

    @Test
    @DisplayName("★ TT-05 · khi bảng đổi và khi công bố: cache bảng thật lẫn bản chụp đã bị xoá TRƯỚC khi sự kiện tới người nghe")
    void tt05_xoa_cache_truoc_khi_bao() throws Exception {
        long id = kyThi(bayGio.minus(Duration.ofHours(3)), bayGio.minus(Duration.ofHours(1)),
                bayGio.minus(Duration.ofHours(2)));
        jdbc.sql("UPDATE contests SET standings_settled_at = now() WHERE id = :id")
                .param("id", id).update();
        jdbc.sql("""
                INSERT INTO contest_standings_frozen (contest_id, user_id, total_score,
                                                      penalty_seconds, solved_count)
                VALUES (:c, :u, 0, 0, 0)
                """).param("c", id).param("u", USER_ID).update();
        jdbc.sql("INSERT INTO contest_standings (contest_id, user_id) VALUES (:c, :u)")
                .param("c", id).param("u", USER_ID).update();
        String khoaThat = "oj:standings:" + id + ":live";
        String khoaChup = "oj:standings:" + id + ":frozen";
        List<String> lucBao = new CopyOnWriteArrayList<>();

        try (var nghe = bus.subscribe(id,
                () -> lucBao.add(redis.hasKey(khoaThat) + "/" + redis.hasKey(khoaChup)))) {
            napCache(id, khoaThat, khoaChup);
            nopAc(id, USER_ID);
            assertThat(updater.capNhat(doc(id))).isEqualTo(1);
            cho(() -> lucBao.size() == 1, Duration.ofSeconds(10), "sự kiện khi bảng đổi");

            napCache(id, khoaThat, khoaChup);
            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                reveal.thucHien(id);
                assertThat(phien).isNotNull();
            }
            cho(() -> lucBao.size() == 2, Duration.ofSeconds(10), "sự kiện khi công bố");
            assertThat(nghe).isNotNull();
        }

        assertThat(lucBao).as("hai khoá cache (thật/chụp) lúc sự kiện tới")
                .containsExactly("false/false", "false/false");
    }

    private void napCache(long id, String khoaThat, String khoaChup) {
        getStandings.thucHien(id, new CurrentUser(USER_ID, "dev", Role.USER));
        standings.top(id, 50, false);
        assertThat(redis.hasKey(khoaThat)).isTrue();
        assertThat(redis.hasKey(khoaChup)).isTrue();
    }

    @Test
    @DisplayName("★ TT-06 · bảng đổi đúng lúc một lượt đọc đang lấy bảng cũ từ Postgres → bảng cũ KHÔNG được ghi vào cache")
    void tt06_khong_ghi_bang_cu_vao_cache() {
        long id = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), null);
        String khoa = "oj:standings:" + id + ":live";
        var dongCu = new StandingsReader.Dong(USER_ID, "dev", "Dev", 1, 60, 1, bayGio, 0);
        var docTrungLucBangDoi = new JdbcStandingsReader(jdbc) {
            @Override
            public List<StandingsReader.Dong> top(long contestId, int n, boolean dongBang) {
                bus.bangDaDoi(contestId);
                return List.of(dongCu);
            }
        };
        var docBinhThuong = new JdbcStandingsReader(jdbc) {
            @Override
            public List<StandingsReader.Dong> top(long contestId, int n, boolean dongBang) {
                return List.of(dongCu);
            }
        };

        assertThat(new RedisStandingsCache(docTrungLucBangDoi, redis, json).top(id, 50, false))
                .containsExactly(dongCu);
        assertThat(redis.hasKey(khoa)).as("bảng đọc trước lô mới nằm lại trong cache").isFalse();

        new RedisStandingsCache(docBinhThuong, redis, json).top(id, 50, false);
        assertThat(redis.hasKey(khoa)).as("không có gì chen vào thì vẫn ghi cache như cũ").isTrue();
    }

    @Test
    @DisplayName("★ NFR-03 · P8: từ lúc ghi verdict tới lúc bảng phản ánh — trung vị 5 lần đo < 2 s, không lần nào > 10 s")
    void nfr03_do_do_tre_p8() throws InterruptedException {
        long id = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), null);
        Duration nhip = props.contest().standingsInterval();
        List<Duration> doTre = new ArrayList<>();

        for (int lan = 0; lan < 5; lan++) {
            Duration lechPhaSoVoiNhip = nhip.multipliedBy(lan).dividedBy(5);
            Thread.sleep(lechPhaSoVoiNhip);
            long thiSinh = nguoi("p8-" + lan);
            long batDau = System.nanoTime();
            nopAc(id, thiSinh);
            cho(() -> standings.cuaNguoi(id, thiSinh, false).isPresent(), BAO_DONG_P8,
                    "bảng phản ánh bài của " + thiSinh);
            doTre.add(Duration.ofNanos(System.nanoTime() - batDau));
        }

        doTre.sort(null);
        log.info("P8 đo được (ms, đã xếp): {}", doTre.stream().map(Duration::toMillis).toList());
        assertThat(doTre.get(2)).as("trung vị P8").isLessThan(MUC_TIEU_P8);
    }
}
