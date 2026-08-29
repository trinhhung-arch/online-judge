package dev.oj.identity.domain;

import dev.oj.platform.security.Role;

/**
 * Thứ duy nhất mang băm mật khẩu, và nó <b>không được ra khỏi use-case đã đọc nó</b>.
 *
 * <p>Tách khỏi {@link User} vì lý do viết trong javadoc của {@code User}: record sinh sẵn
 * {@code toString()}, nên trường nào có mặt trong đối tượng được truyền đi khắp nơi thì sớm
 * muộn cũng có mặt trong một dòng log. Ở đây {@code toString()} được ghi đè để ngay cả khi ai
 * đó log nhầm chính object này thì thứ ra file log vẫn không phải băm mật khẩu.
 *
 * <p>Hai chỗ được phép nhận nó: {@code LoginUseCase} (so mật khẩu) và
 * {@code ChangePasswordUseCase} (so mật khẩu cũ). Không có chỗ thứ ba, và không có DTO nào
 * ở tầng {@code api} chứa trường này.
 *
 * @param passwordHash băm BCrypt cost 12. {@code null} với tài khoản đã ẩn danh hoá —
 *                     {@code ck_users_anonymized} bắt buộc thế
 */
public record Credentials(long userId, String handle, Role role, UserStatus status,
                          String passwordHash) {

    /** Tài khoản đã ẩn danh hoá không còn mật khẩu, nên không có mật khẩu nào khớp được. */
    public boolean coTheDangNhap() {
        return status.canLogIn() && passwordHash != null;
    }

    @Override
    public String toString() {
        return "Credentials[userId=" + userId + ", role=" + role + ", status=" + status + "]";
    }
}
