package dev.oj.platform.config;

import java.time.Duration;
import java.util.List;

public final class AppPropertiesGia {

    private AppPropertiesGia() {
    }

    public static AppProperties macDinh() {
        return voi(internalMacDinh(), authMacDinh(), submissionMacDinh());
    }

    public static AppProperties voiInternalSecret(String secret) {
        return voi(new AppProperties.Internal(secret), authMacDinh(), submissionMacDinh());
    }

    public static AppProperties voiAuth(AuthProperties auth) {
        return voi(internalMacDinh(), auth, submissionMacDinh());
    }

    public static AppProperties voiSecurityHeaders(SecurityHeadersProperties headers) {
        return voi(internalMacDinh(), authMacDinh(), submissionMacDinh(), headers);
    }

    public static AppProperties voiBcrypt(int soSuat, Duration cho) {
        var auth = new AuthProperties("k".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7),
                12, 5, Duration.ofSeconds(60), Duration.ofMinutes(15),
                10, Duration.ofHours(1), "t".repeat(32), true,
                soSuat, cho, Duration.ofSeconds(2), 10, Duration.ofMinutes(15),
                new TurnstileProperties(false, "", "", List.of(),
                        "https://vi-du.test/siteverify", Duration.ofSeconds(3)),
                xacMinhEmailMacDinh());
        return voi(internalMacDinh(), auth, submissionMacDinh());
    }

    public static AppProperties voiGioiHanApi(int moiNguoi, int moiIp) {
        var a = macDinh();
        return new AppProperties(a.submission(), a.judge(), a.page(), a.internal(), a.sse(),
                a.securityHeaders(), a.auth(), a.jobs(), a.contest(),
                new AppProperties.ApiRateLimit(moiNguoi, moiIp, Duration.ofMinutes(1)), a.ai());
    }

    public static AppProperties voiTurnstile(TurnstileProperties turnstile) {
        var a = authMacDinh();
        var auth = new AuthProperties(a.jwtSecret(), a.accessTtl(), a.refreshTtl(),
                a.bcryptCost(), a.maxLoginFailures(), a.loginWindow(), a.lockout(),
                a.maxRegistrationsPerIp(), a.registrationWindow(), a.totpKey(),
                a.requireAdminTwoFactor(), a.bcryptConcurrency(), a.bcryptWait(),
                a.loginMinInterval(), a.totpMaxFailures(), a.totpLockout(),
                turnstile, a.emailVerification());
        return voi(internalMacDinh(), auth, submissionMacDinh());
    }

    public static EmailVerificationProperties xacMinhEmailMacDinh() {
        return new EmailVerificationProperties(false, "", Duration.ofMinutes(30), 5,
                Duration.ofSeconds(60));
    }

    public static AppProperties voiXacMinhEmail(EmailVerificationProperties xacMinh) {
        var a = authMacDinh();
        var auth = new AuthProperties(a.jwtSecret(), a.accessTtl(), a.refreshTtl(),
                a.bcryptCost(), a.maxLoginFailures(), a.loginWindow(), a.lockout(),
                a.maxRegistrationsPerIp(), a.registrationWindow(), a.totpKey(),
                a.requireAdminTwoFactor(), a.bcryptConcurrency(), a.bcryptWait(),
                a.loginMinInterval(), a.totpMaxFailures(), a.totpLockout(),
                a.turnstile(), xacMinh);
        return voi(internalMacDinh(), auth, submissionMacDinh());
    }

    public static AppProperties voiKhoaTotp(String khoa) {
        var a = authMacDinh();
        var auth = new AuthProperties(a.jwtSecret(), a.accessTtl(), a.refreshTtl(),
                a.bcryptCost(), a.maxLoginFailures(), a.loginWindow(), a.lockout(),
                a.maxRegistrationsPerIp(), a.registrationWindow(), khoa,
                a.requireAdminTwoFactor(), a.bcryptConcurrency(), a.bcryptWait(),
                a.loginMinInterval(), a.totpMaxFailures(), a.totpLockout(),
                a.turnstile(), a.emailVerification());
        return voi(internalMacDinh(), auth, submissionMacDinh());
    }

    public static AuthProperties authMacDinh() {
        return new AuthProperties("k".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7),
                12, 5, Duration.ofSeconds(60), Duration.ofMinutes(15),
                10, Duration.ofHours(1), "t".repeat(32), true,
                4, Duration.ofMillis(150), Duration.ofSeconds(2), 10, Duration.ofMinutes(15),
                new TurnstileProperties(false, "", "", List.of(),
                        "https://vi-du.test/siteverify", Duration.ofSeconds(3)),
                xacMinhEmailMacDinh());
    }

    private static AppProperties.Submission submissionMacDinh() {
        return new AppProperties.Submission(65_536, Duration.ofSeconds(10));
    }

    private static AppProperties.Internal internalMacDinh() {
        return new AppProperties.Internal("x".repeat(32));
    }

    private static AppProperties voi(AppProperties.Internal internal,
                                     AuthProperties auth,
                                     AppProperties.Submission submission) {
        return voi(internal, auth, submission,
                new SecurityHeadersProperties("default-src 'none'", Duration.ZERO));
    }

    private static AppProperties voi(AppProperties.Internal internal,
                                     AuthProperties auth,
                                     AppProperties.Submission submission,
                                     SecurityHeadersProperties headers) {
        return new AppProperties(
                submission,
                new AppProperties.Judge(Duration.ofSeconds(120), Duration.ofSeconds(15),
                        2, 2, 20, "mac-m1max-host", Duration.ofMinutes(30), 5.0,
                        Duration.ofSeconds(10),
                        new RejudgeProperties(2, Duration.ofSeconds(5), 200)),
                new AppProperties.Page(20, 50),
                internal,
                new AppProperties.Sse(Duration.ofMinutes(5), Duration.ofSeconds(15)),
                headers,
                auth,
                new AppProperties.Jobs(Duration.ofSeconds(120), Duration.ofSeconds(5)),
                new ContestProperties(Duration.ofSeconds(2), 500,
                        Duration.ofMinutes(5), 50,
                        Duration.ofMinutes(15), Duration.ofDays(7), 1440, 2),
                new AppProperties.ApiRateLimit(100, 600, Duration.ofMinutes(1)),
                new AppProperties.Ai(5, Duration.ofSeconds(30)));
    }
}
