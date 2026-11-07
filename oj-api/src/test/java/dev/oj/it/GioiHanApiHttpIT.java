package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GioiHanApiHttpIT extends HttpIT {

    private static final String XO_AN_DANH = "oj:ratelimit:api:ip:*";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/status", "/api/v1;x/status", "/api;x/v1/status",
            "/%61pi/v1/status"})
    @DisplayName("★ mọi biến thể đường dẫn tới được controller đều bị đếm đúng một lượt")
    void bien_the_duong_dan_van_bi_dem(String duongDan) {
        long truoc = tongLuot(XO_AN_DANH);

        assertThat(goiTho(duongDan))
                .as("%s phải tới được controller — không thì ca này không chứng minh gì", duongDan)
                .isEqualTo(HttpStatus.OK);
        assertThat(tongLuot(XO_AN_DANH) - truoc)
                .as("%s tới được controller mà không bị đếm là lách được trần API", duongDan)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ người đã đăng nhập vẫn đếm theo userId — bộ lọc phải đứng SAU JwtAuthFilter")
    void dang_nhap_thi_van_dem_theo_user() {
        long truoc = tongLuot("oj:ratelimit:api:u:" + USER_ID + ":*");

        HttpStatusCode ma = http.get().uri("/api/v1/me")
                .header("Authorization", bearerDev())
                .exchange((req, res) -> res.getStatusCode(), false);

        assertThat(ma).isEqualTo(HttpStatus.OK);
        assertThat(tongLuot("oj:ratelimit:api:u:" + USER_ID + ":*") - truoc).isEqualTo(1);
    }

    @Test
    @DisplayName("★ /internal/** và file tĩnh KHÔNG bị đếm — bóp cửa worker là bóp đường ghi verdict")
    void ngoai_api_v1_thi_khong_dem() {
        long truoc = tongLuot("oj:ratelimit:api:*");

        http.get().uri("/internal/judge/testdata/" + "0".repeat(64))
                .header("X-Internal-Secret", "x".repeat(32))
                .exchange((req, res) -> res.getStatusCode(), false);
        goiTho("/index.html");

        assertThat(tongLuot("oj:ratelimit:api:*")).isEqualTo(truoc);
    }

    private HttpStatusCode goiTho(String duongDan) {
        return http.get().uri(URI.create("http://localhost:" + port + duongDan))
                .exchange((req, res) -> res.getStatusCode(), false);
    }

    private long tongLuot(String mau) {
        long tong = 0;
        for (String khoa : redis.keys(mau)) {
            String giaTri = redis.opsForValue().get(khoa);
            tong += giaTri == null ? 0 : Long.parseLong(giaTri);
        }
        return tong;
    }
}
