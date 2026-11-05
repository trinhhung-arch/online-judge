package dev.oj.identity.application.usecase;

import dev.oj.identity.application.EmailVerificationIssuer;
import dev.oj.identity.application.port.CaptchaVerifier;
import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.RegistrationRateLimiter;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.PasswordPolicy;
import dev.oj.identity.domain.User;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.ClientIp;
import dev.oj.platform.security.PublicAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@PublicAccess("Đăng ký là cửa vào hệ thống — người chưa có tài khoản thì không thể có token.")
@Service
public class RegisterUserUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterUserUseCase.class);

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final AuditLog auditLog;
    private final RegistrationRateLimiter rateLimiter;
    private final CaptchaVerifier captcha;
    private final EmailVerificationIssuer xacMinhEmail;

    public RegisterUserUseCase(UserRepository users, PasswordHasher hasher, AuditLog auditLog,
                               RegistrationRateLimiter rateLimiter, CaptchaVerifier captcha,
                               EmailVerificationIssuer xacMinhEmail) {
        this.users = users;
        this.hasher = hasher;
        this.auditLog = auditLog;
        this.rateLimiter = rateLimiter;
        this.captcha = captcha;
        this.xacMinhEmail = xacMinhEmail;
    }

    public long thucHien(String handle, String email, String tenHienThi, String matKhau,
                         String clientIp, String captchaToken) {
        rateLimiter.kiemVaGhiNhan(ClientIp.khoaGioiHan(clientIp));

        captcha.kiem(captchaToken, clientIp);

        User.kiemTraHandle(handle);
        User.kiemTraEmail(email);
        User.kiemTraTenHienThi(tenHienThi);
        PasswordPolicy.kiemTra(matKhau);

        String handleChuan = User.chuanHoaHandle(handle);
        String emailChuan = User.chuanHoaEmail(email);
        if (users.daCoHandle(handleChuan)) {
            throw IdentityException.daTonTai("Tên đăng nhập");
        }
        if (users.daCoEmail(emailChuan)) {
            throw IdentityException.daTonTai("Email");
        }

        long id = users.taoMoi(handle.trim(), emailChuan, tenHienThi.trim(), hasher.bam(matKhau));
        auditLog.ghi("USER_REGISTERED", "user", id, Map.of("handle", handle.trim()));

        guiThuXacMinh(id, emailChuan, tenHienThi.trim());
        return id;
    }

    private void guiThuXacMinh(long userId, String email, String tenHienThi) {
        try {
            xacMinhEmail.phatVaGui(userId, email, tenHienThi);
        } catch (RuntimeException e) {
            log.warn("Đăng ký xong nhưng không gửi được thư xác minh cho userId={}: {}. "
                    + "Tài khoản vẫn dùng được; người dùng bấm gửi lại ở trang hồ sơ.",
                    userId, e.toString());
        }
    }
}
