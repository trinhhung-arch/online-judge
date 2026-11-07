package dev.oj.platform.config;

import java.time.Duration;

public record SecurityHeadersProperties(
        String contentSecurityPolicy,
        Duration hstsMaxAge) {

    public SecurityHeadersProperties {
        if (contentSecurityPolicy == null || contentSecurityPolicy.isBlank()) {
            throw new IllegalStateException(
                    "oj.security-headers.content-security-policy rỗng. Một chuỗi rỗng KHÔNG "
                            + "phải là 'tắt CSP một cách an toàn' — nó là mất lưới thứ hai "
                            + "trong khi token vẫn nằm ở localStorage. Muốn tắt thật thì xoá "
                            + "hẳn SecurityHeadersFilter và ghi ADR nói vì sao");
        }
        if (hstsMaxAge == null || hstsMaxAge.isNegative()) {
            throw new IllegalStateException(
                    "oj.security-headers.hsts-max-age = " + hstsMaxAge + ". Dùng 0 để tắt");
        }
    }
}
