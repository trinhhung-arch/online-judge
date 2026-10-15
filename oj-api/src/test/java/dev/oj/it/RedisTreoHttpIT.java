package dev.oj.it;

import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.DockerClientFactory;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Redis <b>treo</b> — khác với Redis <b>hỏng nhanh</b> mà {@code SuyGiamIT} đã đo.
 *
 * <p>{@code SuyGiamIT} làm Redis "chết" bằng một khoá sai kiểu: lệnh trả lỗi WRONGTYPE ngay
 * tức thì, và đường lùi về Postgres chạy đúng. Nhưng một Redis treo — tiến trình đứng, máy
 * quá tải, mạng nuốt gói tin — không trả lỗi nào cả: lệnh nằm chờ tới hết timeout của client.
 * Thời gian chờ ấy mới là thứ người nộp bài thấy.
 *
 * <p>Test làm treo chính container Redis dùng chung bằng {@code docker pause} (đông cứng tiến
 * trình, kết nối TCP vẫn mở) rồi nhả ra trong {@code finally}. Không dựng Redis riêng: thêm một
 * context Spring là thêm 26 connection vào một Postgres đã dùng 79/100 — xem {@link DiemHenSql}.
 */
class RedisTreoHttpIT extends HttpIT {

    private static final Logger log = LoggerFactory.getLogger(RedisTreoHttpIT.class);

    /** Ngưỡng cảnh báo của P2 ({@code nfrplan.md} Phần 1: alert khi p95 &gt; 800ms). */
    private static final Duration NGUONG_CANH_BAO_P2 = Duration.ofMillis(800);

    @Test
    @DisplayName("★ Redis treo: nộp bài vẫn 202 và KHÔNG treo theo Redis")
    void redis_treo_nop_bai_khong_treo_theo() {
        String token = bearerDev();          // ký thẳng: đăng nhập cũng đi qua Redis
        // Làm nóng đường nộp bài khi Redis còn sống: số đo dưới chỉ được mang phần trễ do Redis
        // treo, không mang chi phí JIT của request đầu tiên — thứ làm test chập chờn trên CI.
        // Bằng tài khoản KHÁC: lúc Redis treo, rate limit lùi về Postgres và thấy đúng bài làm
        // nóng vừa nộp chưa đủ 10 giây — 429, tức là đường lùi đang chạy đúng (đã thấy thật).
        quenLuotNopVuaRoi(SETTER_ID);
        assertThat(nopBai(bearer(SETTER_ID, "setter", Role.SETTER)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        quenLuotNopVuaRoi(USER_ID);          // trước khi treo — sau đó thì không xoá được nữa

        tamDungRedis();
        long batDau = System.nanoTime();
        ResponseEntity<Map<String, Object>> res;
        try {
            res = nopBai(token);
        } finally {
            tiepTucRedis();
        }
        Duration mat = Duration.ofNanos(System.nanoTime() - batDau);
        log.info("POST /api/v1/submissions khi Redis treo: {}ms, HTTP {}",
                mat.toMillis(), res.getStatusCode().value());

        assertThat(res.getStatusCode())
                .as("Redis là cache và bộ đếm — nó treo không được thành lỗi của người nộp bài")
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(mat)
                .as("nộp bài treo theo Redis: %sms", mat.toMillis())
                .isLessThan(NGUONG_CANH_BAO_P2);
    }

    private ResponseEntity<Map<String, Object>> nopBai(String token) {
        return goi(http.post().uri("/api/v1/submissions")
                .header(HttpHeaders.AUTHORIZATION, token)
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                        "source", "// EXPECT: AC\nint main(){}")));
    }

    private static void tamDungRedis() {
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void tiepTucRedis() {
        DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
