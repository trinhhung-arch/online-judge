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

/**
 * ★ Bước 6.9 — <b>chế độ suy giảm</b>. Bảng năm kịch bản của {@code nfrplan.md} 7.2.
 *
 * <pre>
 *   Redis chết        leaderboard đọc thẳng Postgres — chậm hơn nhưng ĐÚNG      ở đây
 *   RabbitMQ chết     vẫn nhận bài vào DB, reaper xử lý khi hồi phục            ở đây
 *   Toàn bộ worker    vẫn nhận bài, hiện "đang chờ chấm", KHÔNG báo lỗi          ở đây
 *   MinIO chết        đề đã cache vẫn xem được, không upload đề mới được        CodingRulesTest
 *   LLM chết          AI review "tạm không khả dụng", verdict không đổi         tuần 14–15
 * </pre>
 *
 * <h2>★ Cách làm một phụ thuộc "chết" mà không giết container dùng chung</h2>
 * Cả bộ IT chia nhau <b>một</b> Postgres và <b>một</b> Redis; dừng container ở giữa là làm
 * hỏng mọi lớp test chạy sau, và theo một thứ tự khác nhau giữa máy dev và CI.
 *
 * <p>Nên ở đây Redis được làm hỏng <b>theo thao tác</b>: ghi đè khoá bằng một <i>kiểu dữ liệu
 * sai</i>. {@code ZREVRANGE} trên một khoá kiểu chuỗi ném {@code WRONGTYPE}, đúng như một
 * Redis đang lỗi — nhưng chỉ với khoá ấy, và chỉ trong test này. Kỹ thuật này còn <b>chặt hơn</b>
 * việc tắt container: nó chứng minh nhánh {@code catch} thật sự chạy, chứ không chỉ chứng minh
 * rằng ứng dụng không nổ khi Redis vắng mặt.
 *
 * <p>RabbitMQ thì không cần dựng kịch bản gì: <b>cả bộ IT đã chạy mà không có broker nào</b>
 * ({@code PostgresIT} đặt {@code oj.judge.rabbit.enabled=false}). Ca dưới đây chỉ nói rõ ra
 * điều đó, để nó là một khẳng định chứ không phải một sự tình cờ.
 */
class SuyGiamIT extends HttpIT {

    @Autowired
    private QueueStatusQuery trangThai;

    @Autowired
    private SubmissionRateLimiter rateLimiter;

    @Autowired
    private HealthIndicator judgeCapacityHealthIndicator;

    /**
     * ★ Health check đọc <b>mẫu gần nhất</b>, không chạy truy vấn — xem javadoc của
     * {@code QueueMetricsSampler} về việc vì sao.
     *
     * <p>Hệ quả cho test: sau khi sửa dữ liệu bằng SQL, phải lấy mẫu lại tường minh. Không
     * làm thế thì ca dưới đây đỏ hoặc xanh <b>tuỳ vào nhịp 10 giây</b> rơi vào đâu — và một
     * test đỏ theo đồng hồ là một test sẽ bị xoá thay vì được sửa.
     *
     * <p>Đây cũng là điều người trực cần biết: {@code /actuator/health} trễ tối đa 10 giây.
     */
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

    /**
     * ★ Rate limit là chốt duy nhất của M6 nằm trên đường nộp bài mà <b>dựa vào Redis</b>.
     * Redis chết mà nó ném thì cả hệ thống ngừng nhận bài — vi phạm R1 vì một cache.
     *
     * <p>Đường lùi là {@code SubmissionRepository.lastSubmittedAt}, một truy vấn index-only.
     */
    @Test
    @DisplayName("★ Redis chết: rate limit lùi về Postgres, KHÔNG chặn người dùng")
    void redis_chet_rate_limit_van_chay() {
        quenLuotNopVuaRoi(USER_ID);
        // Khoá rate limit thành kiểu HASH -> mọi lệnh SET/INCR trên nó ném WRONGTYPE.
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
        // ZSET của contest này thành một chuỗi -> ZREVRANGE ném WRONGTYPE.
        redis.opsForValue().set("oj:standings:" + contestId + ":live", "hong");

        var res = goi(http.get().uri("/api/v1/contests/{id}/standings", contestId)
                .header(HttpHeaders.AUTHORIZATION, bearerDev()));

        assertThat(res.getStatusCode())
                .as("chậm hơn thì được, lỗi thì không — oj-api/CLAUDE.md mục 6")
                .isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().toString()).contains("100");
    }

    /**
     * ★ Dòng "Toàn bộ worker chết" của bảng: <i>"vẫn nhận bài, hiện đang chờ chấm, KHÔNG báo
     * lỗi cho user"</i>.
     *
     * <p>Vì thế health check trả {@code OUT_OF_SERVICE}, <b>không phải {@code DOWN}</b>: API
     * vẫn phục vụ được mọi thứ khác và vẫn nhận bài nộp. Trả {@code DOWN} là nói với bộ giám
     * sát rằng cần restart API — mà restart API không mang một worker nào trở lại.
     */
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
