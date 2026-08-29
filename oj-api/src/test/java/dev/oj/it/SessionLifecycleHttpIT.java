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
 * ★ Vòng đời một phiên đăng nhập, qua HTTP thật — FR-AUTH-02, 03, 04, 08.
 *
 * <p>Nửa kia của {@link IdentityHttpIT}, nơi kiểm đăng ký, đăng nhập và phân quyền. Đường cắt
 * theo chủ đề chứ không theo số dòng: file kia hỏi <i>"ai vào được"</i>, file này hỏi
 * <i>"phiên sống và chết thế nào"</i>.
 *
 * <h2>Vì sao ba ca dưới đây phải chạy qua HTTP</h2>
 * Chúng kiểm những thứ chỉ đúng khi <b>trạng thái sống qua nhiều request</b>: một refresh token
 * bị thu hồi ở request này phải chết ở request sau, và bộ đếm đăng nhập sai phải cộng dồn
 * đúng IP qua sáu lần gọi khác nhau. Một test trong cùng một tiến trình có thể xanh nhờ trạng
 * thái còn trong bộ nhớ — thứ mà production không có.
 */
class SessionLifecycleHttpIT extends HttpIT {

    @Nested
    @DisplayName("★ FR-AUTH-02/04 · xoay vòng phiên và thu hồi")
    class Phien {

        @Test
        @DisplayName("làm mới trả token mới, và token cũ dùng lại thì giết cả phiên mới")
        void xoay_vong_va_phat_hien_dung_lai() {
            String cu = (String) login("dev", MAT_KHAU_DEV).getBody().get("refreshToken");

            @SuppressWarnings("unchecked")
            Map<String, Object> moi = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", cu)).retrieve().body(Map.class);
            assertThat((String) moi.get("refreshToken")).isNotEqualTo(cu);

            // Trình lại bản cũ: hệ thống hiểu là có hai bản sao đang tồn tại.
            var dungLai = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", cu))
                    .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                            .body(r.bodyTo(Map.class)), false);
            assertThat(dungLai.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(dungLai.getBody()).containsEntry("code", "identity.phien_bi_dung_lai");

            // ...và phiên hợp lệ của chủ tài khoản cũng bị thu hồi theo.
            HttpStatusCode sauDo = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", moi.get("refreshToken")))
                    .exchange((req, r) -> r.getStatusCode(), false);
            assertThat(sauDo).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("★ đổi mật khẩu thu hồi MỌI phiên, kể cả phiên vừa dùng để đổi")
        void doi_mat_khau_thu_hoi_het() {
            var phien = login("dev", MAT_KHAU_DEV).getBody();

            HttpStatusCode doi = http.post().uri("/api/v1/me/password")
                    .header("Authorization", "Bearer " + phien.get("accessToken"))
                    .body(Map.of("matKhauCu", MAT_KHAU_DEV, "matKhauMoi", "mat-khau-hoan-toan-moi"))
                    .exchange((req, r) -> r.getStatusCode(), false);
            assertThat(doi).isEqualTo(HttpStatus.NO_CONTENT);

            HttpStatusCode lamMoi = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", phien.get("refreshToken")))
                    .exchange((req, r) -> r.getStatusCode(), false);
            assertThat(lamMoi).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(login("dev", MAT_KHAU_DEV).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(login("dev", "mat-khau-hoan-toan-moi").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("đăng xuất thu hồi refresh token, và gọi lại vẫn 204 — idempotent")
        void dang_xuat_idempotent() {
            String token = (String) login("dev", MAT_KHAU_DEV).getBody().get("refreshToken");

            for (int lan = 0; lan < 2; lan++) {
                HttpStatusCode status = http.post().uri("/api/v1/auth/logout")
                        .body(Map.of("refreshToken", token))
                        .exchange((req, r) -> r.getStatusCode(), false);
                assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);
            }
            HttpStatusCode sauKhiDangXuat = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", token))
                    .exchange((req, r) -> r.getStatusCode(), false);
            assertThat(sauKhiDangXuat).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-AUTH-08 · khoá 5 lần sai / phút / IP")
    class KhoaDangNhap {

        @Test
        @DisplayName("lần thứ 6 trả 429 kèm Retry-After, kể cả khi mật khẩu đúng")
        void khoa_sau_5_lan() {
            for (int i = 0; i < 5; i++) {
                assertThat(login("dev", "sai-mat-khau-roi").getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }

            var lanSau = login("dev", MAT_KHAU_DEV);

            assertThat(lanSau.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(lanSau.getHeaders().getFirst("Retry-After")).isNotNull();
            assertThat(lanSau.getBody()).containsEntry("code", "identity.khoa_tam");
        }

        @Test
        @DisplayName("★ mọi lần thử được ghi lại, nhưng KHÔNG ghi mật khẩu đã thử")
        void ghi_lai_nhung_khong_ghi_mat_khau() {
            login("dev", "mat-khau-bi-mat-cua-toi");

            assertThat(jdbc.sql("SELECT handle_tried FROM login_attempts")
                    .query(String.class).list())
                    .containsExactly("dev");
            assertThat(jdbc.sql("SELECT count(*) FROM login_attempts WHERE NOT succeeded")
                    .query(Integer.class).single()).isEqualTo(1);
        }
    }
}
