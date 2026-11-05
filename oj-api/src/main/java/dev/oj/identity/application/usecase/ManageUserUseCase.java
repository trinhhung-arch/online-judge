package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class ManageUserUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLog auditLog;

    public ManageUserUseCase(CurrentUserProvider currentUser, UserRepository users,
                             RefreshTokenRepository refreshTokens, AuditLog auditLog) {
        this.currentUser = currentUser;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
    }

    public void doiVaiTro(long userId, Role vaiTroMoi) {
        khongPhaiChinhMinh(userId, "đổi vai trò");
        if (!users.doiVaiTro(userId, vaiTroMoi.name())) {
            throw IdentityException.khongTimThayNguoiDung();
        }
        refreshTokens.thuHoiTatCa(userId, "vai trò hoặc trạng thái tài khoản thay đổi");
        auditLog.ghi("USER_ROLE_CHANGED", "user", userId, Map.of("vaiTroMoi", vaiTroMoi.name()));
    }

    public void datHoatDong(long userId, boolean batHoatDong) {
        khongPhaiChinhMinh(userId, batHoatDong ? "mở lại" : "vô hiệu hoá");
        UserStatus moi = batHoatDong ? UserStatus.ACTIVE : UserStatus.DISABLED;
        if (!users.doiTrangThai(userId, moi.name())) {
            throw IdentityException.khongTimThayNguoiDung();
        }
        if (!batHoatDong) {
            refreshTokens.thuHoiTatCa(userId, "vai trò hoặc trạng thái tài khoản thay đổi");
        }
        auditLog.ghi(batHoatDong ? "USER_ENABLED" : "USER_DISABLED", "user", userId, Map.of());
    }

    private void khongPhaiChinhMinh(long userId, String thaoTac) {
        if (currentUser.current().id() == userId) {
            throw IdentityException.khongTuThaoTacVoiMinh(thaoTac);
        }
    }
}
