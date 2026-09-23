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
import dev.oj.platform.security.PublicAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * FR-AUTH-01 — đăng ký bằng email + mật khẩu, băm BCrypt cost 12.
 *
 * <h2>Vai trò khi đăng ký luôn là USER, không có tham số</h2>
 * Đây là endpoint công khai. Nếu vai trò nhận từ đầu vào — dù chỉ để "tiện seed dữ liệu" —
 * thì bất kỳ ai gọi được API cũng tự tạo cho mình một tài khoản ADMIN, và với một hệ thống mà
 * ADMIN đọc được testdata mọi đề thì đó là kết thúc của tính công bằng.
 *
 * <p>Nâng vai trò là một thao tác riêng của ADMIN (M6, FR-ADM-*), không phải một trường trong
 * form đăng ký.
 *
 * <h2>★ Thư xác minh gửi ở CUỐI, và hỏng thì KHÔNG làm hỏng lượt đăng ký</h2>
 * Tài khoản đã được ghi vào database trước khi lá thư được gửi. Nếu SMTP từ chối, use-case
 * này <b>nuốt</b> ngoại lệ và vẫn trả về {@code userId}: người dùng có tài khoản thật, đăng
 * nhập được ngay, và bấm "gửi lại" trên trang hồ sơ khi nào cũng được — vì xác minh ở mức
 * mềm không chặn gì cả (ADR 016).
 *
 * <p>Làm ngược lại là để một sự cố của nhà cung cấp thư biến thành "không ai đăng ký được",
 * trong khi các tài khoản thì vẫn đang được tạo ra bình thường ở dưới. Cùng lập trường với
 * {@code AuditLog}: việc phụ hỏng không được kéo theo việc chính đã thành công.
 *
 * <p><b>Đây là lời gọi ra ngoài thứ HAI của cửa đăng ký</b> (sau Turnstile), nên nó cộng
 * thêm độ trễ. Chấp nhận được vì đăng ký không nằm trên đường {@code nộp bài → verdict} —
 * ngân sách 2 giây của {@code nfrplan.md} 2.1 không tính nó. Nhưng cả hai lời gọi ấy
 * <b>phải</b> có trần thời gian, nếu không một máy chủ treo là một luồng Tomcat bị giữ:
 * xem {@code SmtpEmailSender.kiemTimeout}.
 *
 * <h2>Kiểm trùng hai lần, cố ý</h2>
 * Kiểm trước khi chèn cho ra câu <i>"handle này đã có người dùng"</i>; unique index của
 * database mới là chốt thật. Khoảng giữa hai bước có đua tranh, và kết quả của cuộc đua đó
 * là một thông báo chung chung thay vì một dòng dữ liệu sai — đánh đổi đúng chiều.
 */
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

    /**
     * @param clientIp {@code ClientIp.cua(request)} — dùng để đếm, không lưu vào bảng nào
     * @return {@code users.id} vừa tạo
     */
    public long thucHien(String handle, String email, String tenHienThi, String matKhau,
                         String clientIp, String captchaToken) {
        // ★ ĐẾM TRƯỚC MỌI THỨ, kể cả trước khi kiểm định dạng.
        //
        // Đặt sau phần kiểm sẽ biến chính phần kiểm thành cửa miễn phí: một bot dò xem handle
        // nào còn trống chỉ cần gửi email sai định dạng là không bị đếm, mà vẫn nhận được câu
        // trả lời "handle này đã có người dùng". Lượt hỏng cũng tốn tài nguyên và cũng rò rỉ
        // thông tin, nên lượt hỏng cũng phải trả giá.
        rateLimiter.kiemVaGhiNhan(clientIp);

        // ★ CAPTCHA SAU rate limit, TRƯỚC mọi thứ khác — thứ tự này là chủ ý.
        //
        // Sau rate limit: kiểm captcha là một lượt gọi HTTPS ra ngoài. Đặt nó trước thì một
        // đợt dội 1 000 request biến thành 1 000 lượt gọi ra Cloudflare — ta tự khuếch đại
        // đòn tấn công. Bộ đếm cục bộ chặn trước thì chỉ 10 lượt đi ra.
        //
        // Trước phần kiểm định dạng và trước BCrypt: cả hai đều tốn hơn, và không có lý do
        // gì tiêu chúng cho một request chưa chứng minh được nó do người gửi.
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

    /**
     * FR-AUTH-09 — nuốt mọi lỗi. Xem javadoc của class để biết vì sao.
     *
     * <p>Dòng log KHÔNG mang email và KHÔNG mang mã (bất biến #9). Nó mang {@code userId},
     * thứ đủ để nối với dòng {@code USER_REGISTERED} vừa ghi ở trên.
     */
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
