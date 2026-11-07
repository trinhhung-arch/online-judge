package dev.oj.it;

import dev.oj.judging.application.port.SubmissionRateLimiter;
import dev.oj.judging.application.published.QueueStatusQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SuyGiamIT extends HttpIT {

    @Autowired
    private QueueStatusQuery trangThai;

    @Autowired
    private SubmissionRateLimiter rateLimiter;

    @Autowired
    private HealthIndicator judgeCapacityHealthIndicator;

    @Autowired
    private dev.oj.judging.infrastructure.QueueMetricsSampler mau;

    @Test
    @DisplayName("★ RabbitMQ chết: vẫn nhận bài, và bài nằm trong judge_queue chờ reaper")
    void rabbitmq_chet() {
        quenLuotNopVuaRoi(USER_ID);

        var res = goi(http.post().uri("/api/v1/submissions")
                .header(HttpHeaders.AUTHORIZATION, bearerDev())
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                        "source", "int main(){}")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long id = ((Number) res.getBody().get("submissionId")).longValue();
        assertThat(jdbc.sql("SELECT count(*) FROM judge_queue WHERE submission_id = :id")
                .param("id", id).query(Integer.class).single())
                .as("judge_queue là sự thật; RabbitMQ chỉ là đường dẫn")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ Redis chết: rate limit lùi về Postgres, KHÔNG chặn người dùng")
    void redis_chet_rate_limit_van_chay() {
        quenLuotNopVuaRoi(USER_ID);
        redis.opsForHash().put(ResetGiuaCacTest.KHOA_RATE_LIMIT + USER_ID, "x", "y");

        assertThatNoException()
                .as("Redis hỏng không được biến thành một lỗi cho người nộp bài")
                .isThrownBy(() -> rateLimiter.kiemTraVaGhiNhan(USER_ID));

        redis.delete(ResetGiuaCacTest.KHOA_RATE_LIMIT + USER_ID);
    }

    @Test
    @DisplayName("★ Redis chết: bảng xếp hạng đọc thẳng Postgres, trả ĐÚNG chứ không lỗi")
    void redis_chet_bang_xep_hang_van_dung() {
        long contestId = taoContestDaXong();
        jdbc.sql("""
                INSERT INTO contest_standings (contest_id, user_id, total_score, penalty_seconds,
                                               solved_count, last_applied_submission_id)
                VALUES (:c, :u, 100, 600, 1, 1)
                """).param("c", contestId).param("u", USER_ID).update();
        redis.opsForValue().set("oj:standings:" + contestId + ":live", "hong");

        var res = goi(http.get().uri("/api/v1/contests/{id}/standings", contestId)
                .header(HttpHeaders.AUTHORIZATION, bearerDev()));

        assertThat(res.getStatusCode())
                .as("chậm hơn thì được, lỗi thì không — oj-api/CLAUDE.md mục 6")
                .isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().toString()).contains("100");
    }

    @Test
    @DisplayName("★ không worker nào: vẫn nhận bài 202, health OUT_OF_SERVICE chứ không DOWN")
    void toan_bo_worker_chet() {
        jdbc.sql("UPDATE judge_hosts SET last_seen_at = NULL").update();
        mau.layMau();
        quenLuotNopVuaRoi(USER_ID);

        var res = goi(http.post().uri("/api/v1/submissions")
                .header(HttpHeaders.AUTHORIZATION, bearerDev())
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                        "source", "int main(){}")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(trangThai.doc().mayChamSong()).isZero();

        mau.layMau();
        var health = judgeCapacityHealthIndicator.health();
        assertThat(health.getStatus())
                .as("worker chết KHÔNG phải lý do để nói API hỏng")
                .isNotEqualTo(Status.DOWN);
        assertThat(health.getStatus().getCode()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    @DisplayName("trang trạng thái công khai vẫn trả lời khi mọi thứ đang suy giảm")
    void trang_trang_thai_van_song() {
        jdbc.sql("UPDATE judge_hosts SET last_seen_at = NULL").update();

        var res = goi(http.get().uri("/api/v1/status"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("dangNhanBai", true);
    }

    private long taoContestDaXong() {
        return jdbc.sql("""
                INSERT INTO contests (slug, title, format, starts_at, ends_at, created_by,
                                      registration_required)
                VALUES ('da-xong', 'Đã xong', 'ICPC', now() - interval '3 hours',
                        now() - interval '1 hour', :u, FALSE)
                RETURNING id
                """).param("u", ADMIN_ID).query(Long.class).single();
    }
}

