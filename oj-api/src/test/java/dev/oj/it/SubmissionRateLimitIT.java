package dev.oj.it;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.infrastructure.RedisSubmissionRateLimiter;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ FR-SUB-08 — 1 bài / 10 giây / người dùng, với <b>con số thật của production</b>. Bước 4.7.
 *
 * <p>{@code SubmitLatencyIT} hạ cửa sổ xuống 1ms để đo được p95; lớp này là chỗ con số 10 giây
 * thật sự được kiểm.
 *
 * <h2>Và đây cũng là chỗ duy nhất đường DỰ PHÒNG được chạy</h2>
 * Đường Redis thì mọi test đều đi qua. Đường Postgres chỉ chạy khi Redis chết — một trạng thái
 * không xảy ra trong test, nên nếu không cố ý dựng ra thì nó là <b>mã chưa từng chạy một lần
 * nào</b> cho tới đêm Redis thật sự chết.
 *
 * <p>{@link #dungRedisChet()} dựng ra đúng trạng thái đó: một {@code StringRedisTemplate} ném
 * lỗi kết nối ở mọi thao tác, cắm vào <b>repository thật</b> trên <b>Postgres thật</b>. Nhờ
 * thế truy vấn 7 của {@code duong_nong.sql} cũng được kiểm là chạy đúng.
 */
class SubmissionRateLimitIT extends HttpIT {

    @Autowired SubmissionRepository submissions;
    @Autowired AppProperties properties;
    @Autowired Clock clock;

    private static final Map<String, Object> BAI = Map.of(
            "problemId", 1L, "languageCode", "cpp20", "source", "// EXPECT: AC\nint main(){}");

    @Test
    @DisplayName("★ nộp hai lần liên tiếp → 429 kèm Retry-After và mã ổn định")
    void nop_hai_lan_lien_tiep() {
        String token = "Bearer " + tokenCua("dev");

        var lanDau = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", token).body(BAI));
        assertThat(lanDau.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        var lanHai = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", token).body(BAI));

        assertThat(lanHai.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lanHai.getBody()).containsEntry("code", "submission.rate_limited");
        // FR-SUB-08 là quy tắc được CÔNG BỐ: UI phải hiện được đếm ngược, nên header này
        // không phải tuỳ chọn (oj-api/CLAUDE.md mục 8).
        assertThat(lanHai.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat((String) lanHai.getBody().get("message")).contains("giây");

        // Và chỉ MỘT bài được ghi — 429 không phải một lời từ chối mang tính trang trí.
        assertThat(jdbc.sql("SELECT count(*) FROM submissions").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ giới hạn theo TỪNG NGƯỜI — người khác không bị vạ lây")
    void gioi_han_theo_tung_nguoi() {
        var cuaDev = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", "Bearer " + tokenCua("dev")).body(BAI));
        assertThat(cuaDev.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        var cuaAdmin = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", "Bearer " + tokenCua("admin")).body(BAI));

        assertThat(cuaAdmin.getStatusCode())
                .describedAs("khoá theo người, không theo hệ thống — nếu không thì một người "
                        + "nộp bài liên tục là cả kỳ thi đứng")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("★ Redis chết → đường dự phòng Postgres (truy vấn 7) vẫn giữ đúng quy tắc")
    void redis_chet_van_giu_gioi_han() {
        var limiter = new RedisSubmissionRateLimiter(
                dungRedisChet(), submissions, properties, clock);

        // Chưa nộp gì: cho qua, không mở toang cũng không khoá cứng.
        limiter.kiemTraVaGhiNhan(USER_ID);

        // Nộp một bài thật qua HTTP, rồi hỏi lại — lần này Postgres có dữ liệu để trả lời.
        assertThat(goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", "Bearer " + tokenCua("dev")).body(BAI))
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThatThrownBy(() -> limiter.kiemTraVaGhiNhan(USER_ID))
                .isInstanceOf(JudgingException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED);

        // Người chưa nộp gì vẫn nộp được — Redis chết không được biến thành khoá toàn hệ thống.
        limiter.kiemTraVaGhiNhan(ADMIN_ID);
    }

    /**
     * Một {@code StringRedisTemplate} ném {@link RedisConnectionFailureException} ở mọi thao
     * tác — đúng thứ Lettuce ném khi Redis không còn ở đó.
     */
    private static StringRedisTemplate dungRedisChet() {
        return new StringRedisTemplate() {
            @Override
            public ValueOperations<String, String> opsForValue() {
                throw new RedisConnectionFailureException("giả lập: Redis không phản hồi");
            }
        };
    }
}
