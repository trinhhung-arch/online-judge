package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XacMinhEmailHttpIT extends HttpIT {

    private static final String GUI = "/api/v1/me/xac-minh-email";
    private static final String XAC_NHAN = "/api/v1/me/xac-minh-email/xac-nhan";

    @Test
    @DisplayName("★ chưa đăng nhập → 401 ở CẢ HAI endpoint, không phải 204 im lặng")
    void chua_dang_nhap_thi_401() {
        var gui = goi(http.post().uri(GUI));
        var xacNhan = goi(http.post().uri(XAC_NHAN).body(Map.of("ma", "123456")));

        assertThat(gui.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(gui.getBody()).containsEntry("code", "auth.chua_dang_nhap");
        assertThat(xacNhan.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(xacNhan.getBody()).containsEntry("code", "auth.chua_dang_nhap");
    }

    @Test
    @DisplayName("★ /api/v1/me mang emailVerified, và tài khoản seed thì CHƯA xác minh")
    void ho_so_mang_nhan_xac_minh() {
        Map<String, Object> hoSo = http.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + tokenCua("dev"))
                .retrieve().body(THAN_JSON);

        assertThat(hoSo)
                .containsEntry("emailVerified", false)
                .doesNotContainKey("emailVerifiedAt");
    }

    @Test
    @DisplayName("★ máy chủ chưa bật → 409 kèm lời giải thích, KHÔNG im lặng trả 204")
    void tinh_nang_tat_thi_noi_thang() {
        var res = goi(http.post().uri(GUI)
                .header("Authorization", "Bearer " + tokenCua("dev")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsEntry("code", "identity.xac_minh_email_tat");
    }

    @Test
    @DisplayName("gõ mã khi chưa có mã nào → 400 với mã lỗi ổn định, không phải 500")
    void chua_co_ma_thi_400() {
        var res = goi(http.post().uri(XAC_NHAN)
                .header("Authorization", "Bearer " + tokenCua("dev"))
                .body(Map.of("ma", "123456")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "identity.ma_xac_minh_sai");
        assertThat((String) res.getBody().get("message")).contains("gửi lại");
    }

    @Test
    @DisplayName("★ không response nào ở đây để lộ mã hay bản băm của nó — bất biến #9")
    void khong_lo_ma() {
        var res = goi(http.post().uri(XAC_NHAN)
                .header("Authorization", "Bearer " + tokenCua("dev"))
                .body(Map.of("ma", "123456")));

        assertThat(res.getBody().toString())
                .as("thân lỗi không được nhắc lại mã người dùng vừa gõ, và không được mang "
                        + "theo bản băm nào — cả hai đều là đường rò rỉ qua log của client")
                .doesNotContain("123456")
                .doesNotContain("sha256");
    }
}
