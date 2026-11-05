package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

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
