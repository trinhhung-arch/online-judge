package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.User;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

@RequiresRole
@Service
public class UpdateProfileUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;

    public UpdateProfileUseCase(CurrentUserProvider currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    public void thucHien(String tenHienThi, Short ngonNguUaDung) {
        User.kiemTraTenHienThi(tenHienThi);
        users.capNhatHoSo(currentUser.current().id(), tenHienThi.trim(), ngonNguUaDung);
    }
}
