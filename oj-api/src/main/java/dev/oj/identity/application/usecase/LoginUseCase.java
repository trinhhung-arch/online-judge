package dev.oj.identity.application.usecase;

import dev.oj.identity.application.SessionIssuer;
import dev.oj.identity.application.TotpChecker;
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
    private final TotpChecker totp;
    private final SessionIssuer sessions;
    private final AppProperties properties;
    private final Clock clock;

    public LoginUseCase(UserRepository users, PasswordHasher hasher,
                        LoginAttemptRepository attempts, SessionIssuer sessions,
                        AppProperties properties, Clock clock, TotpChecker totp) {
        this.users = users;
        this.hasher = hasher;
        this.attempts = attempts;
        this.totp = totp;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param maHaiLop mã TOTP hoặc mã dự phòng. {@code null} là bình thường ở lần gọi đầu —
     *                 client chưa biết tài khoản này có bật 2FA hay không
     */
    public SessionIssuer.Session thucHien(String handleHoacEmail, String matKhau,
                                          String userAgent, String clientIp, String maHaiLop) {
        kiemKhoa(clientIp);

        Optional<Credentials> tim = handleHoacEmail == null
                ? Optional.empty()
                : users.timCredentials(User.chuanHoaHandle(handleHoacEmail));

        // Băm LUÔN chạy, kể cả khi không tìm thấy — xem điểm 1 trong javadoc.
        String bamDaLuu = tim.map(Credentials::passwordHash).orElse(null);
        boolean khop = hasher.khop(matKhau == null ? "" : matKhau, bamDaLuu);

        boolean matKhauDung = khop && tim.isPresent() && tim.get().coTheDangNhap();
        if (!matKhauDung) {
            attempts.ghiNhan(handleHoacEmail, clientIp, false);
            khoaNeuQuaNhieu(clientIp);
            throw IdentityException.saiThongTinDangNhap();
        }

        Credentials c = tim.get();

        // ★ YẾU TỐ THỨ HAI — và ba chi tiết ở đây đều là bảo mật, không phải tiện dụng.
        //
        // 1. Lượt đăng nhập chỉ được ghi THÀNH CÔNG sau khi qua CẢ HAI yếu tố. Bản trước
        //    ghi ngay sau khi mật khẩu khớp; giữ nguyên thì một người có mật khẩu nhưng
        //    không có điện thoại vẫn để lại dấu vết "đăng nhập thành công" trong
        //    login_attempts, và người trực đọc nhật ký sẽ tin là họ đã vào được.
        //
        // 2. Mã SAI phải tính là một lần đăng nhập hỏng. Không tính thì FR-AUTH-08 (5 lần
        //    sai/phút/IP) không còn áp cho TOTP, và một mã sáu chữ số chỉ có một triệu khả
        //    năng — dò được trong vài giờ.
        //
        // 3. THIẾU mã thì KHÔNG tính là hỏng. Đó là bước bình thường của luồng: client gọi
        //    lần đầu chưa biết tài khoản này có 2FA. Tính nó là hỏng nghĩa là mọi người bật
        //    2FA đều tự khoá IP của mình sau năm lần đăng nhập bình thường.
        if (totp.dangBat(c.userId())) {
            if (maHaiLop == null || maHaiLop.isBlank()) {
                throw IdentityException.canTotp();
            }
            try {
                totp.kiem(c.userId(), maHaiLop);
            } catch (IdentityException e) {
                attempts.ghiNhan(handleHoacEmail, clientIp, false);
                khoaNeuQuaNhieu(clientIp);
                throw e;
            }
        }

        attempts.ghiNhan(handleHoacEmail, clientIp, true);
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
