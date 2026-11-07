package dev.oj.it;

import dev.oj.contests.application.StandingsDriftCheckJob;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class BangXepHangHienThiIT extends PostgresIT {

    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired GetStandingsUseCase getStandings;
    @Autowired StandingsUpdater updater;
    @Autowired StandingsDriftCheckJob drift;

    private final Instant bayGio = Instant.now();

    private long kyThiDaChot(String theThuc, int diemDe) {
        long id = contests.tao(new ContestRepository.ContestMoi("hien-thi-" + System.nanoTime(),
                "Thi thử", theThuc, bayGio.minus(Duration.ofHours(3)),
                bayGio.minus(Duration.ofHours(1)), null, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", diemDe, ADMIN_ID, true);
        jdbc.sql("UPDATE contests SET standings_settled_at = now() WHERE id = :id")
                .param("id", id).update();
        return id;
    }

    private long nguoi(String handle) {
        return jdbc.sql("""
                INSERT INTO users (handle, email, display_name) VALUES (:h, :email, :h)
                RETURNING id
                """).param("h", handle).param("email", handle + "@oj.test")
                .query(Long.class).single();
    }

    @Test
    @DisplayName("★ BX-10 · người hạng 6/8 thấy 2 dòng trên và 2 dòng dưới mình, đúng hạng; hạng của mình lấy từ chính câu ấy")
    void bx10_vung_quanh_minh() {
        long id = kyThiDaChot("ICPC", 100);
        List<Long> xep = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            xep.add(nguoi("quanh-" + i));
        }
        xep.add(5, USER_ID);
        for (int i = 0; i < xep.size(); i++) {
            jdbc.sql("""
                    INSERT INTO contest_standings (contest_id, user_id, total_score, solved_count)
                    VALUES (:c, :u, :diem, :diem)
                    """).param("c", id).param("u", xep.get(i)).param("diem", 8 - i).update();
        }

        var bang = getStandings.thucHien(id, new CurrentUser(USER_ID, "dev", Role.USER));

        assertThat(bang.hangCuaToi()).isEqualTo(6);
        assertThat(bang.cuaToi().userId()).isEqualTo(USER_ID);
        assertThat(bang.quanhToi())
                .extracting(d -> d.dong().userId(), StandingsReader.DongCoHang::hang)
                .containsExactly(tuple(xep.get(3), 4), tuple(xep.get(4), 5), tuple(USER_ID, 6),
                        tuple(xep.get(6), 7), tuple(xep.get(7), 8));
        assertThat(getStandings.thucHien(id, new CurrentUser(SETTER_ID, "setter", Role.SETTER)))
                .as("người không có mặt trên bảng thì không có vùng quanh mình")
                .satisfies(b -> {
                    assertThat(b.quanhToi()).isEmpty();
                    assertThat(b.cuaToi()).isNull();
                });
    }

    @Test
    @DisplayName("★ TD-06 · IOI: bài 50/100 ở đề gắn 200 điểm lên bảng thành 100; đối soát tính lại cũng ra 100")
    void td06_ioi_quy_doi_theo_points() {
        long id = kyThiDaChot("IOI", 200);
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) "
                + "VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, created_at, judged_at)
                SELECT :u, :p, c.id, 1, :sha, 12, 1, 'DONE', 1, 'WA', 50, 100,
                       c.starts_at + interval '30 minutes', now()
                  FROM contests c WHERE c.id = :c
                """).param("u", USER_ID).param("p", PROBLEM_ID).param("c", id)
                .param("sha", NGUON).update();

        updater.capNhat(contests.timTheoId(id).orElseThrow());

        assertThat(getStandings.thucHien(id, null).top()).singleElement()
                .satisfies(d -> assertThat(d.tongDiem()).isEqualTo(100));
        drift.chay(new JobContextGia(Map.of("contestId", id), ADMIN_ID));
        assertThat(jdbc.sql("""
                SELECT rows_mismatched FROM standings_drift_checks
                 WHERE contest_id = :c ORDER BY id DESC LIMIT 1
                """).param("c", id).query(Integer.class).single()).isZero();
    }
}
