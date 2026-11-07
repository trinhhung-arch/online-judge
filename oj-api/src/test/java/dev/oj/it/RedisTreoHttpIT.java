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

class RedisTreoHttpIT extends HttpIT {

    private static final Logger log = LoggerFactory.getLogger(RedisTreoHttpIT.class);

    private static final Duration NGUONG_CANH_BAO_P2 = Duration.ofMillis(800);

    @Test
    @DisplayName("★ Redis treo: nộp bài vẫn 202 và KHÔNG treo theo Redis")
    void redis_treo_nop_bai_khong_treo_theo() {
        String token = bearerDev();
        quenLuotNopVuaRoi(SETTER_ID);
        assertThat(nopBai(bearer(SETTER_ID, "setter", Role.SETTER)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        quenLuotNopVuaRoi(USER_ID);

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
