package dev.oj.it;

import dev.oj.contests.application.FreezeStandingsScheduler;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ChupDongBangNguyenKhoiIT extends PostgresIT {

    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired FreezeStandingsScheduler dongBang;
    @Autowired StandingsUpdater updater;
    @Autowired StandingsReader standings;

    private final Instant bayGio = Instant.now();

    @AfterEach
    void goTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS it_hong_chup_theo_de ON contest_problem_standings_frozen")
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS it_hong_chup_theo_de()").update();
    }

    @Test
    @DisplayName("★ câu chụp thứ hai hỏng thì rollback cả bản, và nhịp sau chụp lại TRỌN VẸN")
    void hong_giua_chung_thi_rollback_va_chup_lai() {
        jdbc.sql("""
                CREATE FUNCTION it_hong_chup_theo_de() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'IT: hỏng giữa hai câu chụp';
                END $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER it_hong_chup_theo_de BEFORE INSERT
                    ON contest_problem_standings_frozen
                    FOR EACH ROW EXECUTE FUNCTION it_hong_chup_theo_de()
                """).update();
        long id = kyThiChuaDongBang();
        nop(id, USER_ID, bayGio.minus(Duration.ofMinutes(40)));
        dongBangLuc(id, bayGio.minus(Duration.ofMinutes(30)));

        dongBang.nhip();

        assertThat(soDong("contest_standings_frozen", id))
                .as("câu thứ nhất phải rollback theo câu thứ hai — nửa bản chụp là bản chụp "
                        + "không bao giờ được làm lại")
                .isZero();
        assertThat(jdbc.sql("SELECT frozen_captured_at IS NULL FROM contests WHERE id = :c")
                .param("c", id).query(Boolean.class).single())
                .as("dấu đã chụp cũng rollback theo").isTrue();

        goTrigger();
        dongBang.nhip();

        assertThat(soDong("contest_standings_frozen", id)).isEqualTo(1);
        assertThat(soDong("contest_problem_standings_frozen", id))
                .as("nhịp sau phải chụp đủ cả ô từng đề")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ DB-05 · bài nộp sau freeze_at, kịp chấm và vào bảng thật trước nhịp chụp, KHÔNG có trong bản chụp")
    void db05_bai_sau_gio_dong_bang_khong_vao_ban_chup() {
        Instant f = bayGio.minus(Duration.ofSeconds(30));
        long id = kyThiChuaDongBang();
        long nopSau = jdbc.sql("""
                INSERT INTO users (handle, email, display_name)
                VALUES ('nop-sau-freeze', 'nop-sau-freeze@oj.test', 'Nộp sau freeze')
                RETURNING id
                """).query(Long.class).single();
        nop(id, USER_ID, f.minus(Duration.ofMinutes(1)));
        nop(id, nopSau, f.plusSeconds(1));
        updater.capNhat(contests.timTheoId(id).orElseThrow());
        assertThat(standings.cuaNguoi(id, nopSau, false)).as("bảng thật đã có bài sau freeze_at")
                .isPresent();

        dongBangLuc(id, f);
        dongBang.nhip();

        assertThat(standings.cuaNguoi(id, USER_ID, true))
                .hasValueSatisfying(d -> assertThat(d.soBaiDat()).isEqualTo(1));
        assertThat(standings.cuaNguoi(id, nopSau, true))
                .as("kết quả của bài nộp sau freeze_at không được lọt vào bản chụp — chỉ hiện là đang chờ")
                .hasValueSatisfying(d -> {
                    assertThat(d.soBaiDat()).isZero();
                    assertThat(d.tongDiem()).isZero();
                    assertThat(d.soBaiChoSauFreeze()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("★ DB-06 · lúc freeze_at bảng còn trống: chụp một bản trống, KHÔNG chụp lại khi bài sau giờ đóng băng vào bảng")
    void db06_bang_trong_luc_dong_bang_thi_van_trong() {
        Instant f = bayGio.minus(Duration.ofSeconds(30));
        long id = kyThiChuaDongBang();
        dongBangLuc(id, f);
        dongBang.nhip();
        OffsetDateTime lanChupDau = daChupLuc(id);

        nop(id, USER_ID, f.plusSeconds(5));
        updater.capNhat(contests.timTheoId(id).orElseThrow());
        dongBang.nhip();

        assertThat(lanChupDau).as("bảng trống vẫn là một bản chụp").isNotNull();
        assertThat(daChupLuc(id)).as("không chụp lại").isEqualTo(lanChupDau);
        assertThat(soDong("contest_standings_frozen", id)).isZero();
        assertThat(standings.top(id, 50, false)).as("bảng thật vẫn có bài").hasSize(1);
    }

    @Test
    @DisplayName("★ DB-07 · đang đóng băng: cột Chờ đếm lúc đọc số bài nộp sau freeze_at, và hiện cả người chỉ nộp sau giờ đóng băng")
    void db07_dem_bai_cho_luc_doc() {
        Instant f = bayGio.minus(Duration.ofMinutes(10));
        long id = kyThiChuaDongBang();
        long chiNopSau = jdbc.sql("""
                INSERT INTO users (handle, email, display_name)
                VALUES ('chi-nop-sau', 'chi-nop-sau@oj.test', 'Chỉ nộp sau') RETURNING id
                """).query(Long.class).single();
        nop(id, USER_ID, f.minus(Duration.ofMinutes(5)));
        dongBangLuc(id, f);
        dongBang.nhip();

        nop(id, USER_ID, f.plusSeconds(60));
        nop(id, USER_ID, f.plusSeconds(120));
        nop(id, chiNopSau, f.plusSeconds(30));
        updater.capNhat(contests.timTheoId(id).orElseThrow());

        assertThat(standings.top(id, 50, true))
                .extracting(StandingsReader.Dong::userId, StandingsReader.Dong::soBaiDat,
                        StandingsReader.Dong::soBaiChoSauFreeze)
                .containsExactly(tuple(USER_ID, 1, 2), tuple(chiNopSau, 0, 1));
    }

    private long kyThiChuaDongBang() {
        long id = contests.tao(new ContestRepository.ContestMoi(
                "chup-" + System.nanoTime(), "Chụp đóng băng", "ICPC",
                bayGio.minus(Duration.ofHours(2)), bayGio.plus(Duration.ofHours(1)),
                bayGio.plus(Duration.ofMinutes(30)), 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private void dongBangLuc(long contestId, Instant luc) {
        jdbc.sql("UPDATE contests SET freeze_at = :luc WHERE id = :c")
                .param("luc", OffsetDateTime.ofInstant(luc, ZoneOffset.UTC))
                .param("c", contestId).update();
    }

    private void nop(long contestId, long userId, Instant luc) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """)
                .param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, created_at, judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, :luc, now())
                """).param("u", userId).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).param("luc", OffsetDateTime.ofInstant(luc, ZoneOffset.UTC))
                .update();
    }

    private OffsetDateTime daChupLuc(long contestId) {
        return jdbc.sql("SELECT frozen_captured_at FROM contests WHERE id = :c")
                .param("c", contestId).query(OffsetDateTime.class).single();
    }

    private int soDong(String bang, long contestId) {
        String sql = switch (bang) {
            case "contest_standings_frozen" ->
                    "SELECT count(*) FROM contest_standings_frozen WHERE contest_id = :c";
            case "contest_problem_standings_frozen" ->
                    "SELECT count(*) FROM contest_problem_standings_frozen WHERE contest_id = :c";
            default -> throw new IllegalArgumentException(bang);
        };
        return jdbc.sql(sql).param("c", contestId).query(Integer.class).single();
    }
}
