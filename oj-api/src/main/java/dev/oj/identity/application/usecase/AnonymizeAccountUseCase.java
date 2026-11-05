package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class AnonymizeAccountUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLog auditLog;

    public AnonymizeAccountUseCase(CurrentUserProvider currentUser, UserRepository users,
                                   RefreshTokenRepository refreshTokens, AuditLog auditLog) {
        this.currentUser = currentUser;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
    }

    public void thucHien(long userId) {
        if (currentUser.current().id() == userId) {
            throw IdentityException.khongTheAnDanhChinhMinh();
        }
        User nguoiDung = users.timTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (nguoiDung.status() == UserStatus.ANONYMIZED) {
            return;
        }

        users.anDanhHoa(userId, User.tenHienThiSauAnDanh(userId));
        int daThuHoi = refreshTokens.thuHoiTatCa(userId, "tài khoản đã ẩn danh hoá (FR-AUTH-07)");

        auditLog.ghi("USER_ANONYMIZED", "user", userId,
                Map.of("handleCu", nguoiDung.handle(), "soPhienDaThuHoi", daThuHoi));
    }
}
