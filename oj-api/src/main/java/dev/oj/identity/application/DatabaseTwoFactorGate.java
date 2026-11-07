package dev.oj.identity.application;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.TwoFactorGate;
import org.springframework.stereotype.Component;

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
