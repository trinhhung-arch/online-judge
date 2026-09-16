package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Trần API chung phải đếm ĐÚNG những đường Spring định tuyến tới controller — qua Tomcat thật.
 *
 * <h2>Vì sao test này phải chạy trên một cổng TCP thật</h2>
 * Lỗ hổng nó giữ nằm ở <b>khoảng lệch giữa hai cách đọc một đường dẫn</b>. Tomcat và Spring
 * chuẩn hoá URI (bỏ {@code ;tham-so}, giải mã {@code %61}) trước khi chọn controller, còn bản
 * cũ của {@code GioiHanApiFilter} so tiền tố trên {@code getRequestURI()} — chuỗi THÔ. Nên
 * {@code /api/v1;x/status} tới đúng controller mà không bị đếm lượt nào: trần 100 lượt/phút
 * biến mất với ai biết thêm hai ký tự. {@code MockHttpServletRequest} không chuẩn hoá gì cả,
 * nên một unit test không bao giờ thấy được khoảng lệch ấy.
 *
 * <p>Test đọc thẳng bộ đếm trong Redis thay vì bắn 601 lượt để chờ một mã 429: nó kiểm đúng
 * câu hỏi <i>"lượt này có được đếm không"</i>, và nhanh hơn ba bậc.
 */
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

    /**
     * Gửi đường dẫn ĐÚNG NGUYÊN VĂN. Truyền chuỗi cho {@code uri(String)} thì {@code RestClient}
     * mã hoá lại {@code %61} thành {@code %2561}, và test sẽ kiểm một đường dẫn khác hẳn.
     */
    private HttpStatusCode goiTho(String duongDan) {
        return http.get().uri(URI.create("http://localhost:" + port + duongDan))
                .exchange((req, res) -> res.getStatusCode(), false);
    }

    /**
     * Cộng mọi ô thời gian, không đọc riêng ô hiện tại: một test chạy vắt qua ranh giới phút
     * sẽ ghi sang khoá mới, và đọc một khoá thì thấy bộ đếm "giảm".
     */
    private long tongLuot(String mau) {
        long tong = 0;
        for (String khoa : redis.keys(mau)) {
            String giaTri = redis.opsForValue().get(khoa);
            tong += giaTri == null ? 0 : Long.parseLong(giaTri);
        }
        return tong;
    }
}
