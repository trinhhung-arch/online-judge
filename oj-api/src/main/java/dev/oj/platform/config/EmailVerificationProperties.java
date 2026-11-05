package dev.oj.platform.config;

import java.time.Duration;

public record EmailVerificationProperties(
        boolean enabled,
        String from,
        Duration ttl,
        int maxAttempts,
        Duration resendCooldown) {

    public EmailVerificationProperties {
        if (enabled && (from == null || from.isBlank())) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.enabled = true nhưng thiếu OJ_MAIL_FROM. "
                            + "Không có địa chỉ người gửi thì mọi lá thư bị từ chối, và "
                            + "triệu chứng là 'không ai nhận được mã' — một kiểu hỏng im "
                            + "lặng phát hiện được hàng giờ sau khi deploy");
        }
        if (enabled && !from.contains("@")) {
            throw new IllegalStateException(
                    "OJ_MAIL_FROM = " + from + " không phải một địa chỉ email");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.ttl = " + ttl + " không hợp lệ");
        }
        if (ttl.toHours() > 24) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.ttl = " + ttl + " — quá 24 giờ. Hạn dùng là "
                            + "một trong ba thứ làm cho mã 6 chữ số an toàn (V13): nó giới "
                            + "hạn cửa sổ đoán. Cần lâu hơn thì đổi sang mã dài hơn, đừng "
                            + "nới hạn");
        }
        if (maxAttempts < 1) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.max-attempts = " + maxAttempts
                            + ". Nhỏ hơn 1 nghĩa là không ai xác minh được");
        }
        if (maxAttempts > 10) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.max-attempts = " + maxAttempts + " — quá 10. "
                            + "Đây là hàng rào DUY NHẤT chặn việc dò một triệu khả năng của "
                            + "mã 6 chữ số. Nới nó là hạ mã xuống mức đoán được");
        }
        if (resendCooldown == null || resendCooldown.isNegative()) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.resend-cooldown = " + resendCooldown
                            + " không hợp lệ");
        }
    }
}
