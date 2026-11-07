package dev.oj.platform.config;

import java.time.Duration;

public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        int bcryptCost,
        int maxLoginFailures,
        Duration loginWindow,
        Duration lockout,
        int maxRegistrationsPerIp,
        Duration registrationWindow,
        String totpKey,
        boolean requireAdminTwoFactor,
        int bcryptConcurrency,
        Duration bcryptWait,
        Duration loginMinInterval,
        int totpMaxFailures,
        Duration totpLockout,
        TurnstileProperties turnstile,
        EmailVerificationProperties emailVerification) {

public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu OJ_JWT_SECRET. Đây là khoá ký access token — chạy mà thiếu nó "
                            + "nghĩa là không phát được token nào, hoặc tệ hơn: phát bằng "
                            + "một khoá mặc định mà ai đọc mã nguồn cũng biết");
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException("OJ_JWT_SECRET quá ngắn (cần >= 32 ký tự)");
        }
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()
                || accessTtl.toMinutes() > 15) {
            throw new IllegalStateException(
                    "oj.auth.access-ttl = " + accessTtl + ". FR-AUTH-02 chốt 15 phút, và đó "
                            + "là trần của khoảng thời gian một vai trò đã bị hạ vẫn còn "
                            + "hiệu lực. Kéo dài là phải hỏi người — CLAUDE.md mục 5.4");
        }
        if (refreshTtl == null || refreshTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalStateException(
                    "oj.auth.refresh-ttl (" + refreshTtl + ") phải LỚN HƠN access-ttl ("
                            + accessTtl + ") — nếu không thì refresh token hết hạn trước "
                            + "thứ nó dùng để làm mới, và người dùng bị đăng xuất mỗi 15 phút");
        }
        if (bcryptCost != 12) {
            throw new IllegalStateException(
                    "oj.auth.bcrypt-cost = " + bcryptCost + ", nhưng FR-AUTH-01 và comment "
                            + "trên users.password_hash đều ghi 12. Hạ cost là làm yếu "
                            + "TOÀN BỘ mật khẩu đã băm trước đó vẫn còn trong database");
        }
        if (maxLoginFailures != 5 || lockout == null || lockout.toMinutes() != 15
                || loginWindow == null || loginWindow.toSeconds() != 60) {
            throw new IllegalStateException(
                    "oj.auth: FR-AUTH-08 chốt 5 lần sai / 1 phút / IP, khoá 15 phút. "
                            + "Nhận được " + maxLoginFailures + " lần / " + loginWindow
                            + " / khoá " + lockout + ". Đây là một dòng trong bảng giới hạn "
                            + "của oj-api/CLAUDE.md mục 8 — đổi là phải hỏi người");
        }
        if (maxRegistrationsPerIp < 1) {
            throw new IllegalStateException(
                    "oj.auth.max-registrations-per-ip = " + maxRegistrationsPerIp
                            + ". Nhỏ hơn 1 nghĩa là không ai đăng ký được — muốn đóng hẳn cửa "
                            + "đăng ký thì đó là một công tắc system_settings, không phải một "
                            + "giới hạn tốc độ đặt bằng 0");
        }
        if (registrationWindow == null || registrationWindow.isZero()
                || registrationWindow.isNegative()) {
            throw new IllegalStateException(
                    "oj.auth.registration-window = " + registrationWindow + " không hợp lệ");
        }
        if (totpKey == null || totpKey.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu OJ_TOTP_KEY. Đây là khoá mã hoá bí mật TOTP lúc lưu — thiếu nó "
                            + "thì hoặc không bật được 2FA, hoặc bí mật nằm trần trong "
                            + "database. Cùng lý do với OJ_JWT_SECRET: không có mặc định");
        }
        if (totpKey.length() < 32) {
            throw new IllegalStateException("OJ_TOTP_KEY quá ngắn (cần >= 32 ký tự)");
        }
        if (bcryptConcurrency < 1) {
            throw new IllegalStateException(
                    "oj.auth.bcrypt-concurrency = " + bcryptConcurrency + ". Nhỏ hơn 1 nghĩa "
                            + "là không ai đăng nhập được");
        }
        if (bcryptWait == null || bcryptWait.isNegative()) {
            throw new IllegalStateException("oj.auth.bcrypt-wait không hợp lệ");
        }
        if (loginMinInterval == null || loginMinInterval.isNegative()) {
            throw new IllegalStateException("oj.auth.login-min-interval không hợp lệ");
        }
        if (totpMaxFailures != 10 || totpLockout == null || totpLockout.toMinutes() != 15) {
            throw new IllegalStateException(
                    "oj.auth: trần mã hai lớp chốt 10 mã sai liên tiếp / tài khoản, khoá 15 phút. "
                            + "Nhận được " + totpMaxFailures + " / " + totpLockout + ". Đây là một "
                            + "dòng trong bảng giới hạn của oj-api/CLAUDE.md mục 8 — đổi là phải hỏi người");
        }
        if (turnstile == null) {
            throw new IllegalStateException("Thiếu khối oj.auth.turnstile");
        }
        if (emailVerification == null) {
            throw new IllegalStateException("Thiếu khối oj.auth.email-verification");
        }
        if (totpKey.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "OJ_TOTP_KEY trùng OJ_JWT_SECRET. Một khoá một việc: dùng chung thì mỗi "
                            + "lần xoay khoá ký token sẽ làm hỏng đăng ký 2FA của tất cả "
                            + "mọi người, biến một thao tác vệ sinh thành một sự cố");
        }
    }
}
