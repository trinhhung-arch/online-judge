package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Mọi response dưới {@code /api/v1/} mang {@code Cache-Control: no-store} — qua Tomcat thật.
 *
 * <p>Chạy trên cổng TCP thật vì cùng lý do với {@link GioiHanApiHttpIT}: phạm vi của bộ lọc do
 * container chọn trên đường dẫn ĐÃ chuẩn hoá, và {@code MockHttpServletRequest} không chuẩn hoá
 * gì cả. Một biến thể đường dẫn tới được controller mà thiếu header là response cá nhân có thể
 * nằm lại trong cache của máy dùng chung.
 *
 * <p>Mỗi ca kiểm MÃ trước rồi mới kiểm header: một biến thể mà Tomcat trả 400 vì lý do khác thì
 * header có hay không cũng không chứng minh gì.
 */
class KhongLuuDemApiHttpIT extends HttpIT {

    private record TraLoi(int ma, String cacheControl) {
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/status", "/api/v1;x/status", "/%61pi/v1/status"})
    @DisplayName("★ mọi biến thể đường dẫn tới được controller đều mang no-store")
    void bien_the_duong_dan_deu_no_store(String duongDan) {
        TraLoi tl = goiTho(duongDan, null);

        assertThat(tl.ma()).as("%s phải tới được controller", duongDan).isEqualTo(200);
        assertThat(tl.cacheControl()).as(duongDan).isEqualTo("no-store");
    }

    @Test
    @DisplayName("response cá nhân của người đã đăng nhập mang no-store")
    void du_lieu_ca_nhan_no_store() {
        TraLoi tl = goiTho("/api/v1/me", bearerDev());

        assertThat(tl.ma()).isEqualTo(200);
        assertThat(tl.cacheControl()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("★ response LỖI cũng mang no-store — 401 từ use-case, 404 không có controller nào")
    void loi_cung_no_store() {
        TraLoi khongDangNhap = goiTho("/api/v1/me", null);
        TraLoi khongCo = goiTho("/api/v1/khong-ton-tai", bearerDev());

        assertThat(khongDangNhap.ma()).isEqualTo(401);
        assertThat(khongDangNhap.cacheControl()).isEqualTo("no-store");
        assertThat(khongCo.ma()).isEqualTo(404);
        assertThat(khongCo.cacheControl()).isEqualTo("no-store");
    }

    @Test
    @DisplayName("file tĩnh KHÔNG bị áp — giống nhau cho mọi người, cache chúng là thứ ta muốn")
    void file_tinh_khong_bi_ap() {
        TraLoi tl = goiTho("/index.html", null);

        assertThat(tl.ma()).isEqualTo(200);
        assertThat(tl.cacheControl()).isNotEqualTo("no-store");
    }

    /** Đường dẫn NGUYÊN VĂN — xem {@code GioiHanApiHttpIT.goiTho}. */
    private TraLoi goiTho(String duongDan, String bearer) {
        var yeuCau = http.get().uri(URI.create("http://localhost:" + port + duongDan));
        if (bearer != null) {
            yeuCau = yeuCau.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return yeuCau.exchange((req, res) -> new TraLoi(res.getStatusCode().value(),
                res.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)), false);
    }
}
