package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

/**
 * FR-AUTH-05 — xem hồ sơ <b>của chính mình</b>.
 *
 * <p>Không nhận tham số {@code userId}. Đó không phải sự tối giản mà là cách rẻ nhất để
 * endpoint này không bao giờ trở thành một lỗ hổng IDOR: không có tham số thì không có gì để
 * sửa trong URL. Hồ sơ công khai của người khác (nếu sau này cần) là một use-case riêng với
 * một tập trường riêng — email sẽ không có trong đó.
 */
@RequiresRole
@Service
public class GetProfileUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;

    public GetProfileUseCase(CurrentUserProvider currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    public User thucHien() {
        return users.timTheoId(currentUser.current().id())
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
    }
}
