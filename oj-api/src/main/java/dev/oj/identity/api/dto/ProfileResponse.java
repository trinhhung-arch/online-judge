package dev.oj.identity.api.dto;

import dev.oj.identity.domain.User;

import java.time.Instant;

/**
 * Hồ sơ <b>của chính mình</b> — FR-AUTH-05.
 *
 * <p>Có {@code email} vì đây là hồ sơ của người đang gọi, và họ đã biết email của mình. Nếu
 * sau này cần hồ sơ công khai của người khác thì đó là một record <b>riêng</b> không có
 * trường này — đừng thêm một cờ {@code boolean anGiau} vào đây, vì một cờ đặt sai là một lần
 * rò rỉ toàn bộ danh sách email người dùng.
 *
 * <p>Không có {@code status}: một người dùng đang gọi được API thì đương nhiên là
 * {@code ACTIVE}. Không có {@code passwordHash} — nó thậm chí không tồn tại trong
 * {@link User}, xem javadoc ở đó.
 */
public record ProfileResponse(
        long id,
        String handle,
        String email,
        String displayName,
        String role,
        Short preferredLanguageId,
        Instant createdAt) {

    public static ProfileResponse tu(User user) {
        return new ProfileResponse(
                user.id(), user.handle(), user.email(), user.displayName(),
                user.role().name(), user.preferredLanguageId(), user.createdAt());
    }
}
