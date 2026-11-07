package dev.oj.it;

import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KyThiLoiHttpIT extends HttpIT {

    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsUpdater updater;

    private final Instant bayGio = Instant.now();

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
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """)
                .param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, now())
                """).param("u", userId).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).update();
    }

    private static void tamDungRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void tiepTucRedis() {
        DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    @Test
    @DisplayName("★ TT-04 · Redis chết: nhịp cập nhật không ném lỗi, GET /standings đọc thẳng Postgres và vẫn đúng")
    void tt04_redis_chet_bang_van_dung() {
        long id = contests.tao(new ContestRepository.ContestMoi("redis-chet-" + System.nanoTime(),
                "Thi thử", "ICPC", bayGio.minus(Duration.ofHours(3)),
                bayGio.minus(Duration.ofHours(1)), null, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        jdbc.sql("UPDATE contests SET standings_settled_at = now() WHERE id = :id")
                .param("id", id).update();
        nopAc(id, USER_ID);
        assertThat(updater.capNhat(doc(id))).isEqualTo(1);
        long keSau = nguoi("khi-redis-chet");

        int capNhat;
        ResponseEntity<Map<String, Object>> res;
        tamDungRedis();
        try {
            nopAc(id, keSau);
            capNhat = updater.capNhat(doc(id));
            res = goi(http.get().uri("/api/v1/contests/{id}/standings", id)
                    .header("Authorization", bearerDev()));
        } finally {
            tiepTucRedis();
        }

        assertThat(capNhat).as("Redis chết không được làm hỏng nhịp cập nhật").isEqualTo(1);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) res.getBody().get("top")).as("cả bài ghi lúc Redis chết").hasSize(2);
        assertThat(res.getBody().get("cuaToi")).isNotNull();
    }

    @Test
    @DisplayName("★ AT-03 · tạo kỳ thi trùng slug → 400 contest.slug_da_ton_tai, không phải 500 — kể cả khi chỉ khác hoa thường")
    void at03_trung_slug() {
        Instant batDau = bayGio.plus(Duration.ofDays(1));
        Instant ketThuc = batDau.plus(Duration.ofHours(3));
        Map<String, Object> than = Map.of("slug", "trung-ma", "title", "Thi thử",
                "format", "ICPC", "startsAt", batDau.toString(), "endsAt", ketThuc.toString());
        String setter = bearer(SETTER_ID, "setter", Role.SETTER);

        assertThat(taoQuaApi(than, setter).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var lanHai = taoQuaApi(than, setter);

        assertThat(lanHai.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(lanHai.getBody()).containsEntry("code", "contest.slug_da_ton_tai");
        assertThatThrownBy(() -> contests.tao(new ContestRepository.ContestMoi("Trung-Ma",
                "Thi thử", "ICPC", batDau, ketThuc, null, 20, true, true, SETTER_ID)))
                .as("khác hoa thường, đi thẳng xuống kho — chỉ chỉ mục lower(slug) chặn được")
                .hasFieldOrPropertyWithValue("code", "contest.slug_da_ton_tai");
    }

    @Test
    @DisplayName("★ AT-07 · phút phạt 40000 qua API → 400 contest.penalty_qua_lon, không chạm tới cột SMALLINT")
    void at07_phut_phat_tran_smallint() {
        Instant batDau = bayGio.plus(Duration.ofDays(1));
        Map<String, Object> than = Map.of("slug", "phat-qua-lon", "title", "Thi thử",
                "format", "ICPC", "startsAt", batDau.toString(),
                "endsAt", batDau.plus(Duration.ofHours(3)).toString(), "penaltyMinutes", 40_000);

        var res = taoQuaApi(than, bearer(SETTER_ID, "setter", Role.SETTER));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "contest.penalty_qua_lon");
        assertThat(contests.timTheoSlug("phat-qua-lon")).isEmpty();
    }

    private ResponseEntity<Map<String, Object>> taoQuaApi(Map<String, Object> than,
                                                          String token) {
        return goi(http.post().uri("/api/v1/contests").header("Authorization", token)
                .body(than));
    }
}
