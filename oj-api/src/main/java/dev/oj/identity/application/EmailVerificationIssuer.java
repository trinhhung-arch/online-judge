package dev.oj.identity.application;

import dev.oj.identity.application.port.EmailSender;
import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.domain.MaXacMinhEmail;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.EmailVerificationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Phát một mã xác minh và gửi nó đi — FR-AUTH-09 (V13).
 *
 * <h2>★ Vì sao đây KHÔNG phải một {@code *UseCase}</h2>
 * Vì nó có <b>hai</b> người gọi với hai lập trường phân quyền ngược nhau, và
 * {@code @RequiresRole} là annotation ở mức <i>class</i>: proxy của nó chặn mọi phương thức,
 * nên một class không mang được cả hai lập trường.
 *
 * <ul>
 *   <li>{@code RegisterUserUseCase} — {@code @PublicAccess}. Lúc nó gọi, chưa có phiên đăng
 *       nhập nào tồn tại; tài khoản vừa được tạo xong một dòng trước đó.</li>
 *   <li>{@code SendVerificationEmailUseCase} — {@code @RequiresRole}. Nút "gửi lại" chỉ dành
 *       cho chủ tài khoản.</li>
 * </ul>
 *
 * <p>Nên phần chung nằm ở đây, trong {@code application} chứ không trong
 * {@code application.usecase} — cùng chỗ và cùng lý do với {@link SessionIssuer} và
 * {@link TotpChecker}. LUẬT 8 của ArchUnit chỉ đòi lập trường ở {@code *UseCase}, và nó đúng
 * khi làm thế: lập trường thuộc về <b>lối vào</b>, không thuộc về một phép tính dùng chung.
 *
 * <h2>★ Không {@code @Transactional}, và đó là một quyết định về SMTP</h2>
 * Phương thức dưới đây làm hai lượt ghi database rồi <b>gọi ra một máy chủ ngoài</b>. Bọc cả
 * ba trong một transaction nghĩa là giữ một connection của pool {@code app} — pool 20
 * connection dùng chung với mọi request khác — suốt thời gian chờ SMTP trả lời. Vài lượt đăng
 * ký trùng một lúc SMTP chậm là pool cạn, và triệu chứng là cả site chậm chứ không phải thư
 * chậm.
 *
 * <p>Cái giá của việc không có transaction: nếu lượt ghi thứ hai hỏng thì người dùng không
 * còn mã sống nào. Đó là trạng thái <b>tự phục hồi</b> — bấm gửi lại là xong — nên nó không
 * đáng đổi lấy rủi ro trên.
 */
@Service
public class EmailVerificationIssuer {

    private final EmailVerificationRepository maXacMinh;
    private final EmailSender emailSender;
    private final EmailVerificationProperties properties;
    private final Clock clock;

    public EmailVerificationIssuer(EmailVerificationRepository maXacMinh, EmailSender emailSender,
                                   AppProperties app, Clock clock) {
        this.maXacMinh = maXacMinh;
        this.emailSender = emailSender;
        this.properties = app.auth().emailVerification();
        this.clock = clock;
    }

    /**
     * Huỷ mã cũ, phát mã mới, gửi thư.
     *
     * <p><b>Thứ tự ba bước là bắt buộc.</b> Huỷ trước vì {@code ux_email_verifications_song}
     * chỉ cho một mã sống mỗi người — nhưng lý do thật sâu hơn một ràng buộc database: mỗi mã
     * còn sống là thêm một lần đoán trúng cho cùng một lượt dò. Mười lần bấm gửi lại mà không
     * huỷ là chia mười lần độ khó của một mã vốn chỉ có một triệu khả năng
     * ({@code V13__xac_minh_email.sql}).
     *
     * <p>Gửi thư là bước <b>cuối</b>, sau khi mã đã nằm trong database: gửi trước thì một lá
     * thư mang mã mà hệ thống không công nhận là chuyện xảy ra được.
     *
     * @param tenHienThi để lá thư gọi đúng tên người
     * @throws dev.oj.identity.domain.IdentityException {@code identity.khong_gui_duoc_thu}
     *         nếu SMTP từ chối. Người gọi quyết định nuốt hay để nổi lên — xem {@link EmailSender}
     */
    public void phatVaGui(long userId, String email, String tenHienThi) {
        if (!properties.enabled()) {
            return;
        }

        maXacMinh.huyMaCu(userId);

        MaXacMinhEmail ma = MaXacMinhEmail.sinh();
        maXacMinh.luu(userId, ma.sha256Hex(), clock.instant().plus(properties.ttl()));

        emailSender.guiMaXacMinh(email, tenHienThi, ma.giaTriTho(), properties.ttl().toMinutes());
    }
}
