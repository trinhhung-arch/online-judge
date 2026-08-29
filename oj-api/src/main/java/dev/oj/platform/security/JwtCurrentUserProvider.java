package dev.oj.platform.security;

import org.springframework.stereotype.Component;

/**
 * Hiện thực M4 của {@link CurrentUserProvider} — Bước 4.5.
 *
 * <p>Đây là toàn bộ phần thay đổi mà seam {@code CurrentUserProvider} hứa từ M1: không một
 * use-case nào đã viết phải đổi chữ ký, không một controller nào phải nhận thêm tham số
 * {@code userId}. {@code FixedDevUserProvider} và {@code DevSecurityConfig} đã bị xoá cùng
 * lần thay này — không để lại, kể cả sau {@code @ConditionalOnMissingBean}, vì một cửa hậu
 * còn nằm trong mã nguồn là một cửa hậu chờ được bật lại.
 *
 * <p>Việc thật sự nằm ở {@link JwtAuthFilter} và {@link CurrentUserHolder}; class này chỉ nối
 * hai thứ đó vào interface mà phần còn lại của hệ thống đã dùng suốt sáu tuần qua.
 */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public CurrentUser current() {
        return CurrentUserHolder.batBuoc();
    }
}
