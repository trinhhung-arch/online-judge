package dev.oj.identity.application;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.TwoFactorGate;
import org.springframework.stereotype.Component;

/**
 * Cài đặt {@link TwoFactorGate} — tra thẳng bảng {@code user_two_factor}.
 *
 * <p>Tắt {@code oj.auth.require-admin-two-factor} thì cổng luôn mở. Cờ ấy có để chạy thử
 * trên máy dev; trên máy công khai thì đừng tắt, vì ADMIN đọc được testdata mọi đề.
 */
@Component
public class DatabaseTwoFactorGate implements TwoFactorGate {

    private final TotpChecker checker;
    private final boolean batBuoc;

    public DatabaseTwoFactorGate(TotpChecker checker, AppProperties properties) {
        this.checker = checker;
        this.batBuoc = properties.auth().requireAdminTwoFactor();
    }

    @Override
    public boolean duocDungQuyenAdmin(long userId) {
        return !batBuoc || checker.dangBat(userId);
    }
}
