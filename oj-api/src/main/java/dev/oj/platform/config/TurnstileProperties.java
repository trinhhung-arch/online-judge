package dev.oj.platform.config;

import java.time.Duration;
import java.util.List;

public record TurnstileProperties(
        boolean enabled,
        String siteKey,
        String secret,
        List<String> hostnames,
        String verifyUrl,
        Duration timeout) {

    public TurnstileProperties {
        hostnames = hostnames == null ? List.of() : List.copyOf(hostnames);
        if (enabled && (hostnames.isEmpty() || hostnames.stream().anyMatch(String::isBlank))) {
            throw new IllegalStateException(
                    "oj.auth.turnstile.enabled = true nhưng OJ_TURNSTILE_HOSTNAMES rỗng — "
                            + "token giải trên trang của bất kỳ ai (kể cả localhost của họ) sẽ "
                            + "được nhận. Đặt đúng tên miền đang phục vụ trang đăng ký, ví dụ "
                            + "OJ_TURNSTILE_HOSTNAMES=oj.vi-du.com");
        }
        if (enabled && (siteKey == null || siteKey.isBlank())) {
            throw new IllegalStateException(
                    "oj.auth.turnstile.enabled = true nhưng thiếu OJ_TURNSTILE_SITE_KEY — "
                            + "widget ở trình duyệt không dựng được, và người dùng sẽ thấy "
                            + "một form không bao giờ gửi được");
        }
        if (enabled && (secret == null || secret.isBlank())) {
            throw new IllegalStateException(
                    "oj.auth.turnstile.enabled = true nhưng thiếu OJ_TURNSTILE_SECRET. "
                            + "Một hàng rào bật mà không cấu hình là hàng rào tệ nhất: nó "
                            + "trông như đang bảo vệ. Lấy secret ở dashboard Cloudflare, "
                            + "hoặc đặt OJ_TURNSTILE_ENABLED=false");
        }
        if (verifyUrl == null || verifyUrl.isBlank()) {
            throw new IllegalStateException("oj.auth.turnstile.verify-url rỗng");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("oj.auth.turnstile.timeout không hợp lệ");
        }
    }
}
