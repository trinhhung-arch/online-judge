package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Xác minh email qua <b>HTTP thật</b> — FR-AUTH-09 (V13).
 *
 * <h2>Bộ IT chạy với tính năng TẮT, và đó chính là thứ đáng kiểm nhất ở tầng này</h2>
 * Không có máy chủ SMTP nào trong CI, nên vòng "nhận thư rồi gõ mã" được đo bằng fake ở
 * {@code XacMinhEmailUseCaseTest}. Thứ chỉ hiện ra khi có một cổng TCP thật thì khác, và
 * đúng ba thứ:
 *
 * <ol>
 *   <li><b>Hai endpoint có tồn tại và đứng đúng chỗ.</b> Một {@code @RequestMapping} viết sai
 *       cho 404, và không test nào gọi thẳng use-case phát hiện được.</li>
 *   <li><b>{@code JwtAuthFilter} chặn trước.</b> Đây là bảng test bắt buộc của
 *       {@code CLAUDE.md} mục 6, dòng "endpoint mới": gọi bằng vai trò sai phải bị TỪ CHỐI,
 *       <b>không phải 200 rỗng</b>. Hai endpoint này mang {@code @RequiresRole} mức USER nên
 *       "vai trò sai" ở đây là <i>chưa đăng nhập</i>, và câu trả lời đúng là 401.</li>
 *   <li><b>{@code ProfileResponse} thật sự serialize trường mới.</b> Một trường thêm vào
 *       record mà Jackson không phát ra thì giao diện đọc {@code undefined} và im lặng coi
 *       như "chưa xác minh" — sai theo hướng không ai báo.</li>
 * </ol>
 *
 * <p>Và một thứ thứ tư, tình cờ nhưng có giá trị: câu trả lời khi tính năng tắt phải là một
 * lời giải thích ({@code 409 identity.xac_minh_email_tat}), không phải {@code 204} im lặng.
 * Bộ IT là môi trường duy nhất dựng đúng tình huống ấy một cách tự nhiên.
 */
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

        // V13 cố ý không backfill, nên mọi tài khoản có từ trước — kể cả dev-seed — đều là
        // "chưa xác minh". Đó là sự thật, họ chưa từng xác minh.
        assertThat(hoSo)
                .containsEntry("emailVerified", false)
                .doesNotContainKey("emailVerifiedAt");
    }

    @Test
    @DisplayName("★ máy chủ chưa bật → 409 kèm lời giải thích, KHÔNG im lặng trả 204")
    void tinh_nang_tat_thi_noi_thang() {
        var res = goi(http.post().uri(GUI)
                .header("Authorization", "Bearer " + tokenCua("dev")));

        // Một người bấm "gửi mã" rồi ngồi chờ một lá thư không bao giờ tới là kiểu hỏng tệ
        // nhất: không có gì trên màn hình sai, và không có gì nói vì sao.
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
        // Câu công khai phải chỉ ra lối đi tiếp, không chỉ từ chối.
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
