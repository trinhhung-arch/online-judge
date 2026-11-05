package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLifecycleHttpIT extends HttpIT {

    @Nested
    @DisplayName("★ FR-AUTH-02/04 · xoay vòng phiên và thu hồi")
    class Phien {

        @Test
        @DisplayName("làm mới trả token mới, và token cũ dùng lại thì giết cả phiên mới")
        void xoay_vong_va_phat_hien_dung_lai() {
            String cu = (String) login("dev", MAT_KHAU_DEV).getBody().get("refreshToken");

            Map<String, Object> moi = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", cu)).retrieve().body(THAN_JSON);
            assertThat((String) moi.get("refreshToken")).isNotEqualTo(cu);

            var dungLai = http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", cu))
                    .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                            .body(r.bodyTo(THAN_JSON)), false);
            assertThat(dungLai.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(dungLai.getBody()).containsEntry("code", "identity.phien_bi_dung_lai");

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

            quenLuotDangNhapVuaRoi(USER_ID);

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

        @Test
        @DisplayName("★ cùng một refresh token gửi SONG SONG → đúng MỘT lượt thắng, còn lại là dùng lại")
        void lam_moi_song_song_chi_mot_luot_thang() throws Exception {
            String cu = (String) login("dev", MAT_KHAU_DEV).getBody().get("refreshToken");
            int soLuot = 8;
            var chot = new CountDownLatch(1);
            List<Future<ResponseEntity<Map<String, Object>>>> hen = new ArrayList<>();
            try (ExecutorService luong = Executors.newFixedThreadPool(soLuot)) {
                for (int i = 0; i < soLuot; i++) {
                    hen.add(luong.submit(() -> {
                        chot.await();
                        return lamMoi(cu);
                    }));
                }
                chot.countDown();
            }

            List<ResponseEntity<Map<String, Object>>> ketQua = new ArrayList<>();
            for (var h : hen) {
                ketQua.add(h.get());
            }
            var thang = ketQua.stream()
                    .filter(r -> HttpStatus.OK.equals(r.getStatusCode()))
                    .toList();
            assertThat(thang).as("số lượt làm mới thành công").hasSize(1);
            assertThat(ketQua).filteredOn(r -> !HttpStatus.OK.equals(r.getStatusCode()))
                    .allSatisfy(r -> assertThat(r.getBody())
                            .containsEntry("code", "identity.phien_bi_dung_lai"));

            assertThat(lamMoi((String) thang.get(0).getBody().get("refreshToken")).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        private ResponseEntity<Map<String, Object>> lamMoi(String refreshToken) {
            return http.post().uri("/api/v1/auth/refresh")
                    .body(Map.of("refreshToken", refreshToken))
                    .exchange((req, r) -> ResponseEntity.status(r.getStatusCode())
                            .body(r.bodyTo(THAN_JSON)), false);
        }
    }

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
