package dev.oj.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ {@link AuthProperties} — các chốt "cấu hình sai thì KHÔNG khởi động". Thiết kế:
 * {@code Claude outputs/identity-test-cases.md}, ca CH-01 … CH-04.
 *
 * <p>Mỗi chốt canh một bí mật bắt buộc hoặc một con số đã chốt ({@code CLAUDE.md} mục 5.4,
 * bảng giới hạn của {@code oj-api/CLAUDE.md} mục 8). Mọi test khác dựng cấu hình ĐÚNG qua
 * {@link AppPropertiesGia}, nên không test nào đi qua nhánh ném: gỡ một chốt trong lúc dọn
 * dẹp thì cả bộ test vẫn xanh. Lớp này đi qua từng nhánh một.
 */
class AuthPropertiesTest {

    /** Bản sao sửa được của cấu hình chuẩn — mỗi ca đổi đúng một trường rồi dựng lại. */
    private static final class Ban {
        private final AuthProperties goc = AppPropertiesGia.authMacDinh();
        String jwtSecret = goc.jwtSecret();
        Duration accessTtl = goc.accessTtl();
        Duration refreshTtl = goc.refreshTtl();
        int bcryptCost = goc.bcryptCost();
        int maxLoginFailures = goc.maxLoginFailures();
        Duration loginWindow = goc.loginWindow();
        Duration lockout = goc.lockout();
        String totpKey = goc.totpKey();
        int totpMaxFailures = goc.totpMaxFailures();
        Duration totpLockout = goc.totpLockout();

        AuthProperties dung() {
            return new AuthProperties(jwtSecret, accessTtl, refreshTtl, bcryptCost,
                    maxLoginFailures, loginWindow, lockout, goc.maxRegistrationsPerIp(),
                    goc.registrationWindow(), totpKey, goc.requireAdminTwoFactor(),
                    goc.bcryptConcurrency(), goc.bcryptWait(), goc.loginMinInterval(),
                    totpMaxFailures, totpLockout, goc.turnstile(), goc.emailVerification());
        }
    }

    private static AuthProperties dung(Consumer<Ban> sua) {
        Ban ban = new Ban();
        sua.accept(ban);
        return ban.dung();
    }

    private static void khongKhoiDong(Consumer<Ban> sua, String phaiNhac) {
        assertThatThrownBy(() -> dung(sua))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(phaiNhac);
    }

    @Test
    @DisplayName("cấu hình chuẩn dựng được — để mọi ca dưới đây hỏng vì đúng một trường của nó")
    void cau_hinh_chuan_dung_duoc() {
        assertThatCode(() -> dung(ban -> { })).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "OJ_JWT_SECRET = [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("★ CH-01 · thiếu OJ_JWT_SECRET → không khởi động, không có khoá mặc định nào")
    void thieu_khoa_ky(String khoa) {
        khongKhoiDong(ban -> ban.jwtSecret = khoa, "OJ_JWT_SECRET");
    }

    @Test
    @DisplayName("★ CH-01 · OJ_JWT_SECRET 31 ký tự → không khởi động, câu lỗi không chép khoá; 32 ký tự → được")
    void khoa_ky_bien_32() {
        String ngan = "s".repeat(31);

        assertThatThrownBy(() -> dung(ban -> ban.jwtSecret = ngan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OJ_JWT_SECRET")
                .hasMessageNotContaining(ngan);
        assertThatCode(() -> dung(ban -> ban.jwtSecret = "s".repeat(32))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "OJ_TOTP_KEY = [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   ", "ngan-hon-32-ky-tu-mot-chut-xiu"})
    @DisplayName("★ CH-02 · OJ_TOTP_KEY thiếu hoặc ngắn hơn 32 ký tự → không khởi động")
    void khoa_totp_thieu_hoac_ngan(String khoa) {
        khongKhoiDong(ban -> ban.totpKey = khoa, "OJ_TOTP_KEY");
    }

    @Test
    @DisplayName("★ CH-02 · OJ_TOTP_KEY trùng OJ_JWT_SECRET → không khởi động: một khoá một việc")
    void khoa_totp_trung_khoa_ky() {
        khongKhoiDong(ban -> ban.totpKey = ban.jwtSecret, "trùng OJ_JWT_SECRET");
        assertThatCode(() -> dung(ban -> ban.totpKey = "u".repeat(32))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ CH-03 · các con số đã chốt (BCrypt 12 · 5 lần / 60 s / khoá 15 phút · 2FA 10 / 15 phút) lệch → không khởi động")
    void so_da_chot_lech_thi_khong_khoi_dong() {
        for (Consumer<Ban> sua : List.<Consumer<Ban>>of(
                ban -> ban.bcryptCost = 11, ban -> ban.bcryptCost = 13)) {
            khongKhoiDong(sua, "bcrypt-cost");
        }
        for (Consumer<Ban> sua : List.<Consumer<Ban>>of(
                ban -> ban.maxLoginFailures = 4, ban -> ban.maxLoginFailures = 6,
                ban -> ban.loginWindow = Duration.ofSeconds(59),
                ban -> ban.loginWindow = Duration.ofSeconds(61),
                ban -> ban.lockout = Duration.ofMinutes(14),
                ban -> ban.lockout = Duration.ofMinutes(16),
                ban -> ban.lockout = null)) {
            khongKhoiDong(sua, "FR-AUTH-08");
        }
        for (Consumer<Ban> sua : List.<Consumer<Ban>>of(
                ban -> ban.totpMaxFailures = 9, ban -> ban.totpMaxFailures = 11,
                ban -> ban.totpLockout = Duration.ofMinutes(14))) {
            khongKhoiDong(sua, "hai lớp");
        }
    }

    @Test
    @DisplayName("★ CH-04 · access-ttl bằng 0, âm, quá 15 phút, hoặc refresh-ttl ≤ access-ttl → không khởi động")
    void thoi_han_token_sai_thi_khong_khoi_dong() {
        for (Consumer<Ban> sua : List.<Consumer<Ban>>of(
                ban -> ban.accessTtl = Duration.ZERO,
                ban -> ban.accessTtl = Duration.ofMinutes(-1),
                ban -> ban.accessTtl = Duration.ofMinutes(16),
                ban -> ban.accessTtl = null)) {
            khongKhoiDong(sua, "access-ttl");
        }
        for (Consumer<Ban> sua : List.<Consumer<Ban>>of(
                ban -> ban.refreshTtl = Duration.ofMinutes(15),
                ban -> ban.refreshTtl = Duration.ofMinutes(10),
                ban -> ban.refreshTtl = null)) {
            khongKhoiDong(sua, "refresh-ttl");
        }
    }
}
