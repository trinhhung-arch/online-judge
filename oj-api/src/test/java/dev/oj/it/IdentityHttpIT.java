package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Vòng đời danh tính đi qua <b>HTTP thật</b>, với <b>BCrypt thật</b> — Bước 4.3 → 4.7.
 *
 * <h2>Vì sao lớp này tồn tại bên cạnh {@code IdentityUseCasesTest}</h2>
 * Bộ unit test chạy bằng {@code IdentityFakes.BamGia} — băm bằng một phép nối chuỗi, để cả bộ
 * xong trong một giây. Nghĩa là nó <b>không</b> chứng minh được rằng cost 12 thật hoạt động,
 * rằng JSON serialize đúng, rằng {@code JwtAuthFilter} đứng đúng chỗ trong chuỗi filter, hay
 * rằng cột {@code inet} nhận được thứ {@code ClientIp} trả về.
 *
 * <p>Bốn thứ đó chỉ hiện ra khi có một cổng TCP thật và một Postgres thật, và cả bốn đều là
 * kiểu lỗi mà mọi test trước đó đều xanh trong khi hệ thống hỏng.
 *
 * <p>Vòng đời phiên (xoay vòng, thu hồi, khoá đăng nhập) nằm ở {@link SessionLifecycleHttpIT} —
 * tách ra vì trần 300 dòng của {@code CLAUDE.md} mục 7.
 */
class IdentityHttpIT extends HttpIT {

    @Nested
    @DisplayName("★ FR-AUTH-01/02/05 · vòng đăng ký → đăng nhập → xem hồ sơ")
    class VongDayDu {

        @Test
        @DisplayName("đăng ký, đăng nhập, gọi /me — tất cả qua HTTP với BCrypt thật")
        void vong_day_du() {
            var dangKy = http.post().uri("/api/v1/auth/register")
                    .body(Map.of("handle", "nguoi-moi", "email", "moi@oj.test",
                            "displayName", "Người mới", "password", "matkhau-that-su-123"))
                    .retrieve().toEntity(THAN_JSON);
            assertThat(dangKy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            long id = ((Number) dangKy.getBody().get("userId")).longValue();

            var phien = login("nguoi-moi", "matkhau-that-su-123");
            assertThat(phien.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(phien.getBody())
                    .containsEntry("handle", "nguoi-moi")
                    .containsEntry("role", "USER")
                    .containsEntry("expiresIn", 900)
                    .containsKeys("accessToken", "refreshToken");

            Map<String, Object> hoSo = http.get().uri("/api/v1/me")
                    .header("Authorization", "Bearer " + phien.getBody().get("accessToken"))
                    .retrieve().body(THAN_JSON);

            assertThat(hoSo).containsEntry("id", (int) id)
                    .containsEntry("handle", "nguoi-moi")
                    .containsEntry("email", "moi@oj.test");
            // Hồ sơ KHÔNG được mang băm mật khẩu ra ngoài dưới bất kỳ tên trường nào.
            assertThat(hoSo.toString()).doesNotContain("$2a$", "password");
        }

        @Test
        @DisplayName("đăng nhập bằng email cũng được, cùng một ô nhập")
        void dang_nhap_bang_email() {
            assertThat(login("dev@oj.test", MAT_KHAU_DEV).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("handle trùng → 409 với mã ổn định")
        void trung_handle_409() {
            var res = http.post().uri("/api/v1/auth/register")
                    .body(Map.of("handle", "dev", "email", "khac@oj.test",
                            "displayName", "Trùng", "password", "matkhau-that-su-123"))
                    .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                            .body(r.bodyTo(THAN_JSON)), false);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody()).containsEntry("code", "identity.da_ton_tai");
        }

        @Test
        @DisplayName("★ sai mật khẩu và sai handle cho CÙNG một câu, CÙNG một mã")
        void khong_phan_biet_duoc_tai_khoan_co_that() {
            var saiMatKhau = login("dev", "hoan-toan-khong-dung");
            var khongTonTai = login("khong-he-ton-tai", "hoan-toan-khong-dung");

            assertThat(saiMatKhau.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(khongTonTai.getStatusCode()).isEqualTo(saiMatKhau.getStatusCode());
            assertThat(khongTonTai.getBody().get("code"))
                    .isEqualTo(saiMatKhau.getBody().get("code"));
            assertThat(khongTonTai.getBody().get("message"))
                    .isEqualTo(saiMatKhau.getBody().get("message"));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ JwtAuthFilter đứng đúng chỗ")
    class ChuoiFilter {

        @Test
        @DisplayName("không có token → 401 auth.chua_dang_nhap")
        void khong_token() {
            var res = http.get().uri("/api/v1/me")
                    .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                            .body(r.bodyTo(THAN_JSON)), false);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.getBody()).containsEntry("code", "auth.chua_dang_nhap");
        }

        @Test
        @DisplayName("★ token bịa đặt → 401, và một token đã sửa payload cũng vậy")
        void token_gia_bi_loai() {
            String that = tokenCua("dev");
            String[] doan = that.split("\\.");
            String sua = doan[0] + "." + doan[1] + "."
                    + (doan[2].charAt(0) == 'A' ? "B" : "A") + doan[2].substring(1);

            for (String xau : new String[]{"khong-phai-token", "a.b.c", sua}) {
                var res = http.get().uri("/api/v1/me")
                        .header("Authorization", "Bearer " + xau)
                        .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                                .body(r.bodyTo(THAN_JSON)), false);

                assertThat(res.getStatusCode())
                        .describedAs("token '%s'", xau).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(res.getBody()).containsEntry("code", "auth.token_khong_hop_le");
            }
        }

        @Test
        @DisplayName("token hợp lệ nhưng thiếu tiền tố Bearer thì không được nhận")
        void thieu_tien_to_bearer() {
            HttpStatusCode res = http.get().uri("/api/v1/me")
                    .header("Authorization", tokenCua("dev"))
                    .exchange((req, r) -> r.getStatusCode(), false);

            assertThat(res).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-AUTH-06 · vai trò sai trả 403, KHÔNG phải 200 rỗng")
    class PhanQuyenQuaHttp {

        @Test
        @DisplayName("USER gọi endpoint ADMIN → 403 và tài khoản đích không đổi")
        void user_goi_endpoint_admin() {
            var res = http.post().uri("/api/v1/admin/users/" + SETTER_ID + "/anonymize")
                    .header("Authorization", "Bearer " + tokenCua("dev"))
                    .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                            .body(r.bodyTo(THAN_JSON)), false);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.getBody()).containsEntry("code", "auth.thieu_quyen");
            assertThat(jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", SETTER_ID).query(String.class).single()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("ADMIN thì 204, và dữ liệu định danh bị xoá thật")
        void admin_thi_lam_duoc() {
            HttpStatusCode status = http.post()
                    .uri("/api/v1/admin/users/" + SETTER_ID + "/anonymize")
                    .header("Authorization", "Bearer " + tokenCua("admin"))
                    .exchange((req, r) -> r.getStatusCode(), false);

            assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(jdbc.sql("SELECT email FROM users WHERE id = :id")
                    .param("id", SETTER_ID).query(String.class).optional()).isEmpty();
            assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'USER_ANONYMIZED'")
                    .query(Integer.class).single()).isEqualTo(1);
        }
    }

    // =========================================================================
    // =========================================================================

    @org.springframework.beans.factory.annotation.Autowired
    dev.oj.identity.application.port.SecretCipher cipher;

    /**
     * ★ V11 · FR-AUTH-09 — đường đăng nhập 2FA THẬT, qua HTTP.
     *
     * <p>{@code TwoFactorUseCaseTest} đã đo logic bằng fake; ca ở đây đo phần mà fake không
     * chạm tới: {@code AuthRequests.Login} có thêm trường {@code maHaiLop}, Jackson phải bind
     * được nó, và client CŨ không gửi trường ấy vẫn phải đăng nhập được như trước.
     */
    @Nested
    @DisplayName("★ V11 · đăng nhập hai lớp qua HTTP")
    class DangNhapHaiLop {

        /** Hạt giống RFC 6238 — cùng chuỗi mà TotpTest dùng, nên mã tính ra đối chiếu được. */
        private static final String BI_MAT = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        private void batHaiLopChoDev() {
            jdbc.sql("""
                    INSERT INTO user_two_factor (user_id, secret_enc, enabled, confirmed_at)
                    VALUES (:id, :bi, TRUE, now())
                    ON CONFLICT (user_id) DO UPDATE
                       SET secret_enc = EXCLUDED.secret_enc, enabled = TRUE,
                           last_step = NULL, confirmed_at = now()
                    """)
                    .param("id", USER_ID).param("bi", cipher.maHoa(BI_MAT)).update();
        }

        private String maBayGio() {
            long giay = java.time.Instant.now().getEpochSecond();
            for (int i = 0; i < 1_000_000; i++) {
                String ma = String.format("%06d", i);
                Long buoc = dev.oj.identity.domain.Totp.kiem(BI_MAT, ma, giay);
                if (buoc != null
                        && buoc == Math.floorDiv(giay, dev.oj.identity.domain.Totp.BUOC_GIAY)) {
                    return ma;
                }
            }
            throw new AssertionError("không dò được mã");
        }

        @Test
        @DisplayName("client cũ không gửi maHaiLop vẫn đăng nhập được khi chưa bật 2FA")
        void client_cu_van_chay() {
            assertThat(login("dev", MAT_KHAU_DEV).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("★ bật 2FA rồi: đúng mật khẩu nhưng thiếu mã → 401 identity.can_totp")
        void thieu_ma_thi_401() {
            batHaiLopChoDev();

            var res = login("dev", MAT_KHAU_DEV);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.getBody()).containsEntry("code", "identity.can_totp");
            assertThat(res.getBody().get("accessToken"))
                    .as("không được phát token khi mới qua một yếu tố")
                    .isNull();
        }

        @Test
        @DisplayName("★ gửi kèm mã đúng thì vào được — và DÙNG LẠI mã ấy thì KHÔNG")
        void ma_dung_roi_phat_lai() {
            batHaiLopChoDev();
            String ma = maBayGio();

            var lanMot = goi(http.post().uri("/api/v1/auth/login")
                    .body(java.util.Map.of("dinhDanh", "dev", "password", MAT_KHAU_DEV,
                            "maHaiLop", ma)));
            assertThat(lanMot.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(lanMot.getBody().get("accessToken")).isNotNull();

            var lanHai = goi(http.post().uri("/api/v1/auth/login")
                    .body(java.util.Map.of("dinhDanh", "dev", "password", MAT_KHAU_DEV,
                            "maHaiLop", ma)));
            assertThat(lanHai.getStatusCode())
                    .as("chống phát lại: ai đọc được một request cũ trong 30 giây "
                            + "vẫn không vào được")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(lanHai.getBody()).containsEntry("code", "identity.totp_sai");
        }
    }

}
