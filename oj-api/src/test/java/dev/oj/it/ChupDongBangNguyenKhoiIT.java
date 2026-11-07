package dev.oj.it;

import dev.oj.contests.application.FreezeStandingsScheduler;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChupDongBangNguyenKhoiIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired FreezeStandingsScheduler dongBang;

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
        long id = kyThiDaQuaGioDongBang();

        dongBang.nhip();

        assertThat(soDong("contest_standings_frozen", id))
                .as("câu thứ nhất phải rollback theo câu thứ hai — nửa bản chụp là bản chụp "
                        + "không bao giờ được làm lại")
                .isZero();

        goTrigger();
        dongBang.nhip();

        assertThat(soDong("contest_standings_frozen", id)).isEqualTo(1);
        assertThat(soDong("contest_problem_standings_frozen", id))
                .as("nhịp sau phải chụp đủ cả ô từng đề")
                .isEqualTo(1);
    }

    private long kyThiDaQuaGioDongBang() {
        Instant bayGio = Instant.now();
        long id = contests.tao(new ContestRepository.ContestMoi(
                "chup-" + System.nanoTime(), "Chụp đóng băng", "ICPC",
                bayGio.minus(Duration.ofHours(2)), bayGio.plus(Duration.ofHours(1)),
                bayGio.minus(Duration.ofMinutes(30)), 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        jdbc.sql("""
                INSERT INTO contest_standings (contest_id, user_id, total_score,
                    penalty_seconds, solved_count, last_applied_submission_id)
                VALUES (:c, :u, 100, 60, 1, 0)
                """).param("c", id).param("u", USER_ID).update();
        jdbc.sql("""
                INSERT INTO contest_problem_standings (contest_id, user_id, problem_id,
                    best_score, attempts_before_score)
                VALUES (:c, :u, :p, 100, 0)
                """).param("c", id).param("u", USER_ID).param("p", PROBLEM_ID).update();
        return id;
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
