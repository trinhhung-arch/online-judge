package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Đầu vào hỏng ở các endpoint quản trị tài khoản và hồ sơ phải ra 400 với mã ổn định, không
 * ra 500. Thiết kế: {@code Claude outputs/identity-test-cases.md}, ca QT-05 và HS-03.
 *
 * <p>500 ở đây là hai lỗi một lúc: người dùng nhận "có lỗi phía hệ thống" cho một lỗi của chính
 * họ, và bảng theo dõi lỗi máy chủ đầy những sự cố không có thật — đúng kiểu phân loại sai đã
 * từng làm một sự cố MinIO biến mất khỏi mọi biểu đồ (README, mục M6). Mỗi ca còn kiểm rằng dữ
 * liệu hỏng không chạm vào dữ liệu: tài khoản đích và hồ sơ giữ nguyên.
 *
 * <p>Gọi bằng ADMIN dù Spring đọc thân request trước khi use-case kiểm quyền: đo đúng thứ một
 * quản trị viên thật nhận được.
 */
class IdentityDauVaoHongHttpIT extends HttpIT {

    private static final String VAI_TRO = "/api/v1/admin/users/" + SETTER_ID + "/role";
    private static final String HOAT_DONG = "/api/v1/admin/users/" + SETTER_ID + "/active";

    @Test
    @DisplayName("★ QT-05 · đổi vai trò với dữ liệu hỏng → 400 với mã ổn định, không 500; tài khoản đích không đổi")
    void doi_vai_tro_du_lieu_hong() {
        String admin = tokenCua("admin");

        la400(gui(HttpMethod.POST, VAI_TRO, admin, "{\"vaiTro\":\"SUPERADMIN\"}"), "request.malformed");
        la400(gui(HttpMethod.POST, VAI_TRO, admin, "{\"vaiTro\":null}"), "request.invalid");
        la400(gui(HttpMethod.POST, VAI_TRO, admin, null), "request.malformed");
        la400(gui(HttpMethod.POST, "/api/v1/admin/users/khong-phai-so/role", admin,
                "{\"vaiTro\":\"USER\"}"), "request.invalid_parameter");

        assertThat(vaiTro(SETTER_ID)).isEqualTo("SETTER");
        assertThat(soDongAudit("USER_ROLE_CHANGED")).isZero();
    }

    @Test
    @DisplayName("QT-05 · bật/tắt tài khoản thiếu trường, hoặc sai kiểu → 400; tài khoản vẫn ACTIVE")
    void dat_hoat_dong_du_lieu_hong() {
        String admin = tokenCua("admin");

        la400(gui(HttpMethod.POST, HOAT_DONG, admin, "{}"), "request.invalid");
        la400(gui(HttpMethod.POST, HOAT_DONG, admin, "{\"hoatDong\":[1,2]}"), "request.malformed");

        assertThat(trangThai(SETTER_ID)).isEqualTo("ACTIVE");
        assertThat(soDongAudit("USER_DISABLED")).isZero();
    }

    @Test
    @DisplayName("★ HS-03 · ngôn ngữ ưa dùng không tồn tại → 400 identity.ngon_ngu_khong_ton_tai, không 500; hồ sơ không đổi")
    void ngon_ngu_ua_dung_khong_ton_tai() {
        String dev = tokenCua("dev");

        la400(gui(HttpMethod.PATCH, "/api/v1/me", dev,
                "{\"displayName\":\"Tên mới\",\"preferredLanguageId\":9999}"),
                "identity.ngon_ngu_khong_ton_tai");
        la400(gui(HttpMethod.PATCH, "/api/v1/me", dev,
                "{\"displayName\":\"Tên mới\",\"preferredLanguageId\":40000}"),
                "request.malformed");

        assertThat(tenHienThi(USER_ID)).isEqualTo("Người dùng dev");
    }

    // =========================================================================

    /** Thân JSON viết tay — các ca này cần chính những chuỗi một client hỏng sẽ gửi. */
    private ResponseEntity<Map<String, Object>> gui(HttpMethod phuongThuc, String duongDan,
                                                    String token, String thanJson) {
        var spec = http.method(phuongThuc).uri(duongDan)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return goi(thanJson == null ? spec : spec.body(thanJson));
    }

    private static void la400(ResponseEntity<Map<String, Object>> res, String ma) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", ma);
    }

    private String vaiTro(long userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }

    private String trangThai(long userId) {
        return jdbc.sql("SELECT status FROM users WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }

    private String tenHienThi(long userId) {
        return jdbc.sql("SELECT display_name FROM users WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }

    private int soDongAudit(String hanhDong) {
        return jdbc.sql("SELECT count(*)::int FROM audit_log WHERE action = :a")
                .param("a", hanhDong).query(Integer.class).single();
    }
}
