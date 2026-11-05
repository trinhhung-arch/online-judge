package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.PasswordPolicy;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

import java.util.Map;

@RequiresRole
@Service
public class ChangePasswordUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLog auditLog;

    public ChangePasswordUseCase(CurrentUserProvider currentUser, UserRepository users,
                                 PasswordHasher hasher, RefreshTokenRepository refreshTokens,
                                 AuditLog auditLog) {
        this.currentUser = currentUser;
        this.users = users;
        this.hasher = hasher;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
    }

    public void thucHien(String matKhauCu, String matKhauMoi) {
        long userId = currentUser.current().id();
        PasswordPolicy.kiemTra(matKhauMoi);

        Credentials c = users.timCredentialsTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (!hasher.khop(matKhauCu == null ? "" : matKhauCu, c.passwordHash())) {
            throw IdentityException.saiMatKhauCu();
        }

        users.doiMatKhau(userId, hasher.bam(matKhauMoi));
        int daThuHoi = refreshTokens.thuHoiTatCa(userId, "đổi mật khẩu (FR-AUTH-04)");
        auditLog.ghi("PASSWORD_CHANGED", "user", userId,
                Map.of("soPhienDaThuHoi", daThuHoi));
    }
}
