package dev.oj.it;

import dev.oj.contests.api.ContestResponses;
import dev.oj.contests.application.FreezeStandingsScheduler;
import dev.oj.contests.application.RebuildStandingsJob;
import dev.oj.contests.application.StandingsDriftCheckJob;
import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.application.usecase.RevealStandingsUseCase;
import dev.oj.contests.domain.Contest;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BangXepHangKyThiIT extends PostgresIT {

    private static final String NGUON = "a".repeat(64);
    private static final CurrentUser DEV = new CurrentUser(USER_ID, "dev", Role.USER);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsReader standings;
    @Autowired StandingsUpdater updater;
    @Autowired GetStandingsUseCase getStandings;
    @Autowired RevealStandingsUseCase reveal;
    @Autowired StandingsEventBus bus;
    @Autowired StandingsDriftCheckJob drift;
    @Autowired RebuildStandingsJob rebuild;
    @Autowired FreezeStandingsScheduler freeze;
    @Autowired AppProperties props;

    private final Instant bayGio = Instant.now();

    private long kyThi(Instant batDau, Instant ketThuc, Instant dongBang) {
        return kyThi(batDau, ketThuc, dongBang, true);
    }

    private long kyThi(Instant batDau, Instant ketThuc, Instant dongBang, boolean tuCongBo) {
        long id = contests.tao(new ContestRepository.ContestMoi("bxh-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, dongBang, 20, true, tuCongBo, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private long daXongLau() {
        long id = kyThi(bayGio.minus(Duration.ofHours(3)), bayGio.minus(Duration.ofHours(1)), null);
        jdbc.sql("UPDATE contests SET standings_settled_at = now() WHERE id = :id")
                .param("id", id).update();
        return id;
    }

    private Contest doc(long id) {
        return contests.timTheoId(id).orElseThrow();
    }

    private void nop(long contestId, String verdict, int soBai) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """)
                .param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, created_at, judged_at)
                SELECT :u, :p, c.id, 1, :sha, 12, 1, 'DONE', 1, :v, :diem, 100,
                       c.starts_at + interval '30 minutes', now()
                  FROM contests c CROSS JOIN generate_series(1, :n)
                 WHERE c.id = :c
                """)
                .param("u", USER_ID).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).param("v", verdict).param("diem", "AC".equals(verdict) ? 100 : 0)
                .param("n", soBai).update();
    }

    private long nguoi(String handle) {
        return jdbc.sql("""
                INSERT INTO users (handle, email, display_name) VALUES (:h, :email, :h)
                RETURNING id
                """).param("h", handle).param("email", handle + "@oj.test")
                .query(Long.class).single();
    }

    private void song(long contestId, long userId, int diem, int phat, Instant luc) {
        jdbc.sql("""
                INSERT INTO contest_standings (contest_id, user_id, total_score, penalty_seconds,
                                               solved_count, last_scoring_at)
                VALUES (:c, :u, :diem, :phat, :diem, :luc)
                """).param("c", contestId).param("u", userId).param("diem", diem)
                .param("phat", phat).param("luc", OffsetDateTime.ofInstant(luc, ZoneOffset.UTC))
                .update();
    }

    private void chup(long contestId, long userId, int diem, int phat, Instant luc) {
        jdbc.sql("""
                INSERT INTO contest_standings_frozen (contest_id, user_id, total_score,
                                                      penalty_seconds, solved_count, last_scoring_at)
                VALUES (:c, :u, :diem, :phat, :diem, :luc)
                """).param("c", contestId).param("u", userId).param("diem", diem)
                .param("phat", phat).param("luc", OffsetDateTime.ofInstant(luc, ZoneOffset.UTC))
                .update();
    }

    private int soDongLech(long contestId) {
        return jdbc.sql("""
                SELECT rows_mismatched FROM standings_drift_checks
                 WHERE contest_id = :c ORDER BY id DESC LIMIT 1
                """).param("c", contestId).query(Integer.class).single();
    }

    @Test
    @DisplayName("★ BX-05 · trần + 1 bài chấm xong cùng lúc: nhịp đầu tính đúng trần, nhịp sau tính 1, không sót không trùng")
    void bx05_qua_tran_mot_nhip() {
        int tran = props.contest().standingsBatchSize();
        long id = daXongLau();
        nop(id, "WA", tran);
        nop(id, "AC", 1);

        assertThat(updater.capNhat(doc(id))).isEqualTo(tran);
        assertThat(updater.capNhat(doc(id))).isEqualTo(1);
        assertThat(updater.capNhat(doc(id))).isZero();

        assertThat(standings.top(id, 50, false)).singleElement().satisfies(d -> {
            assertThat(d.soBaiDat()).isEqualTo(1);
            assertThat(d.penaltyGiay()).as("mỗi bài WA cộng phạt đúng một lần")
                    .isEqualTo(30 * 60 + tran * 20 * 60);
        });
    }

    @Test
    @DisplayName("★ BX-08 · điểm ↓ → phạt ↑ → lần ghi điểm cuối sớm hơn; bằng nhau mọi mặt thì cùng hạng")
    void bx08_thu_tu_va_dong_hang() {
        long id = daXongLau();
        Instant t = bayGio.minus(Duration.ofHours(2));
        long a = nguoi("bx-a");
        long b = nguoi("bx-b");
        long c = nguoi("bx-c");
        long d = nguoi("bx-d");
        long e = nguoi("bx-e");
        song(id, b, 2, 300, t.plusSeconds(1200));
        song(id, e, 2, 300, t.plusSeconds(1800));
        song(id, a, 3, 900, t.plusSeconds(600));
        song(id, d, 2, 300, t.plusSeconds(1200));
        song(id, c, 2, 100, t.plusSeconds(300));

        assertThat(standings.top(id, 50, false)).extracting(StandingsReader.Dong::userId)
                .containsExactlyInAnyOrder(a, b, c, d, e).startsWith(a, c).endsWith(e);
        assertThat(ContestResponses.BangXepHang.tu(getStandings.thucHien(id, null)).top())
                .extracting(ContestResponses.Dong::hang).containsExactly(1, 2, 3, 3, 5);
        assertThat(List.of(a, c, b, d, e))
                .extracting(u -> standings.hang(id, u, false).orElseThrow())
                .containsExactly(1, 2, 3, 3, 5);
    }

    @Test
    @DisplayName("★ DS-04 · chấm lại sau kỳ thi đổi verdict → bảng lệch → đối soát phát hiện → dựng lại sửa đúng")
    void ds04_cham_lai_doi_soat_dung_lai() {
        long id = daXongLau();
        nop(id, "AC", 1);
        updater.capNhat(doc(id));
        assertThat(standings.top(id, 50, false)).singleElement()
                .satisfies(d -> assertThat(d.soBaiDat()).isEqualTo(1));

        jdbc.sql("""
                UPDATE submissions SET verdict = 'WA', score = 0, attempt = 2
                 WHERE contest_id = :c
                """).param("c", id).update();
        assertThat(updater.capNhat(doc(id))).as("watermark đã qua bài này").isZero();

        var ctx = new JobContextGia(Map.of("contestId", id), ADMIN_ID);
        drift.chay(ctx);
        assertThat(soDongLech(id)).isEqualTo(1);

        rebuild.chay(ctx);
        assertThat(standings.top(id, 50, false)).singleElement()
                .satisfies(d -> assertThat(d.soBaiDat()).isZero());
        drift.chay(ctx);
        assertThat(soDongLech(id)).isZero();
    }

    @Test
    @DisplayName("★ DB-08 · đang đóng băng: 'dòng của tôi' và 'hạng của tôi' cũng là bản chụp")
    void db08_dong_cua_toi_cung_dong_bang() {
        long id = kyThi(bayGio.minus(Duration.ofHours(2)), bayGio.plus(Duration.ofHours(1)),
                bayGio.minus(Duration.ofMinutes(30)));
        Instant t = bayGio.minus(Duration.ofHours(1));
        chup(id, USER_ID, 1, 600, t);
        chup(id, SETTER_ID, 2, 900, t);
        song(id, SETTER_ID, 2, 900, t);
        song(id, USER_ID, 3, 1200, t.plusSeconds(60));

        var bang = getStandings.thucHien(id, DEV);

        assertThat(bang.dongBang()).isTrue();
        assertThat(bang.cuaToi().tongDiem()).as("không lộ điểm mới của chính mình").isEqualTo(1);
        assertThat(bang.hangCuaToi()).isEqualTo(2);
        assertThat(standings.hang(id, USER_ID, false)).as("bảng thật đã khác").hasValue(1);
    }

    @Test
    @DisplayName("★ DB-14 · kỳ thi không đặt freeze_at: tới phút cuối vẫn không đóng băng, không có bản chụp")
    void db14_khong_dat_gio_dong_bang() {
        long id = kyThi(bayGio.minus(Duration.ofHours(2)), bayGio.plus(Duration.ofMinutes(1)), null);
        song(id, USER_ID, 2, 600, bayGio.minus(Duration.ofHours(1)));

        freeze.nhip();

        assertThat(jdbc.sql("SELECT count(*) FROM contest_standings_frozen WHERE contest_id = :c")
                .param("c", id).query(Integer.class).single()).isZero();
        var bang = getStandings.thucHien(id, DEV);
        assertThat(bang.dongBang()).isFalse();
        assertThat(bang.cuaToi().tongDiem()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ DB-12 · sau khi công bố: người thường thấy bảng thật, cache bị xoá, có sự kiện, có audit")
    void db12_sau_khi_cong_bo() throws Exception {
        long id = kyThi(bayGio.minus(Duration.ofHours(3)), bayGio.minus(Duration.ofHours(1)),
                bayGio.minus(Duration.ofHours(2)), false);
        Instant t = bayGio.minus(Duration.ofMinutes(150));
        chup(id, USER_ID, 1, 600, t);
        song(id, USER_ID, 3, 1200, t);
        String khoaChup = "oj:standings:" + id + ":frozen";
        String khoaThat = "oj:standings:" + id + ":live";

        assertThat(getStandings.thucHien(id, DEV).top()).singleElement()
                .satisfies(d -> assertThat(d.tongDiem()).isEqualTo(1));
        standings.top(id, 50, false);
        assertThat(redis.hasKey(khoaChup)).isTrue();
        assertThat(redis.hasKey(khoaThat)).isTrue();

        var suKien = new CountDownLatch(1);
        try (var nghe = bus.subscribe(id, suKien::countDown);
             var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
            reveal.thucHien(id);
            assertThat(suKien.await(10, TimeUnit.SECONDS)).as("luồng SSE được báo").isTrue();
            assertThat(nghe).isNotNull();
            assertThat(phien).isNotNull();
        }

        assertThat(redis.hasKey(khoaChup)).isFalse();
        assertThat(redis.hasKey(khoaThat)).isFalse();
        var sauCongBo = getStandings.thucHien(id, DEV);
        assertThat(sauCongBo.dongBang()).isFalse();
        assertThat(sauCongBo.cuaToi().tongDiem()).isEqualTo(3);
        assertThat(sauCongBo.top()).singleElement()
                .satisfies(d -> assertThat(d.tongDiem()).isEqualTo(3));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit_log
                 WHERE action = 'CONTEST_STANDINGS_REVEALED' AND entity_id = :c
                """).param("c", id).query(Integer.class).single()).isEqualTo(1);
    }
}
