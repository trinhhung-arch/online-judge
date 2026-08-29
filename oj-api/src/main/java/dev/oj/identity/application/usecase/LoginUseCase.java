package dev.oj.identity.application.usecase;

import dev.oj.identity.application.SessionIssuer;
import dev.oj.identity.application.port.LoginAttemptRepository;
import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.Credentials;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * FR-AUTH-02 và FR-AUTH-08 — đăng nhập, và khoá tạm 5 lần sai / phút / IP.
 *
 * <h2>★ Ba chỗ cố ý làm chậm hoặc cố ý im lặng</h2>
 *
 * <ol>
 *   <li><b>Không tìm thấy tài khoản vẫn phải băm một lần.</b> BCrypt cost 12 tốn ~250ms. Nếu
 *       nhánh "không tồn tại" trả lời trong 2ms còn nhánh "sai mật khẩu" tốn 250ms thì thời
 *       gian phản hồi <i>chính là</i> câu trả lời cho <i>"tài khoản này có thật không"</i> —
 *       và người dò không cần đọc nội dung response nữa.</li>
 *   <li><b>Bốn nguyên nhân, một thông báo.</b> Xem javadoc của {@link IdentityException}.</li>
 *   <li><b>Khoá theo IP chứ không theo tài khoản.</b> Xem javadoc của
 *       {@link LoginAttemptRepository} — khoá theo tài khoản là trao cho người lạ một nút
 *       khoá tài khoản của người khác.</li>
 * </ol>
 *
 * <h2>Ghi nhận lần thử TRƯỚC khi ném lỗi</h2>
 * Nếu ghi sau thì một ngoại lệ giữa chừng làm mất bản ghi, và bộ đếm 5 lần sai không bao giờ
 * chạm ngưỡng — tức là FR-AUTH-08 tồn tại trên giấy mà không tồn tại lúc chạy.
 */
@PublicAccess("Đăng nhập là cách người dùng LẤY token — đòi token ở đây là một vòng lặp vô tận.")
@Service
public class LoginUseCase {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final LoginAttemptRepository attempts;
    private final SessionIssuer sessions;
    private final AppProperties properties;
    private final Clock clock;

    public LoginUseCase(UserRepository users, PasswordHasher hasher,
                        LoginAttemptRepository attempts, SessionIssuer sessions,
                        AppProperties properties, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.attempts = attempts;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    public SessionIssuer.Session thucHien(String handleHoacEmail, String matKhau,
                                          String userAgent, String clientIp) {
        kiemKhoa(clientIp);

        Optional<Credentials> tim = handleHoacEmail == null
                ? Optional.empty()
                : users.timCredentials(User.chuanHoaHandle(handleHoacEmail));

        // Băm LUÔN chạy, kể cả khi không tìm thấy — xem điểm 1 trong javadoc.
        String bamDaLuu = tim.map(Credentials::passwordHash).orElse(null);
        boolean khop = hasher.khop(matKhau == null ? "" : matKhau, bamDaLuu);

        boolean thanhCong = khop && tim.isPresent() && tim.get().coTheDangNhap();
        attempts.ghiNhan(handleHoacEmail, clientIp, thanhCong);

        if (!thanhCong) {
            khoaNeuQuaNhieu(clientIp);
            throw IdentityException.saiThongTinDangNhap();
        }

        Credentials c = tim.get();
        return sessions.phat(c.userId(), c.handle(), c.role(), userAgent, clientIp, null);
    }

    private void kiemKhoa(String clientIp) {
        Instant bayGio = clock.instant();
        attempts.khoaToi(clientIp)
                .filter(toi -> toi.isAfter(bayGio))
                .ifPresent(toi -> {
                    throw IdentityException.daKhoaTam(Duration.between(bayGio, toi));
                });
    }

    private void khoaNeuQuaNhieu(String clientIp) {
        var auth = properties.auth();
        Instant bayGio = clock.instant();
        int soLanSai = attempts.demThatBaiTu(clientIp, bayGio.minus(auth.loginWindow()));
        if (soLanSai >= auth.maxLoginFailures()) {
            attempts.khoa(clientIp, bayGio.plus(auth.lockout()),
                    "vượt ngưỡng đăng nhập sai (FR-AUTH-08)");
        }
    }
}
