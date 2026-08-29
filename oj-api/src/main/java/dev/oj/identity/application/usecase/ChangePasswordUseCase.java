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

/**
 * FR-AUTH-04 — đổi mật khẩu (cần mật khẩu cũ), và <b>thu hồi mọi refresh token</b>.
 *
 * <h2>Vì sao thu hồi hết, kể cả phiên đang thao tác</h2>
 * Vì lý do phổ biến nhất để đổi mật khẩu là <i>nghi ngờ có người khác biết nó</i>. Nếu chỉ đổi
 * cột băm mà để nguyên các phiên đang mở thì người kia vẫn đang đăng nhập — mật khẩu mới không
 * đuổi họ ra, và người dùng tin rằng nó có.
 *
 * <p>Cái giá: người vừa đổi mật khẩu cũng bị đăng xuất trên mọi thiết bị. Đó là hành vi họ
 * mong đợi, và là hành vi của mọi hệ thống nghiêm túc.
 *
 * <h2>Vì sao vẫn phải hỏi mật khẩu cũ dù đã có access token</h2>
 * Vì access token có thể là token bị đánh cắp. Mật khẩu cũ là thứ kẻ đánh cắp token
 * <i>không</i> có, nên nó biến "chiếm được phiên" thành "chưa chiếm được tài khoản".
 */
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
