package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.EmailSender;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.ThuXacMinhEmail;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.EmailVerificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * Gửi thư qua SMTP — FR-AUTH-09 (V13).
 *
 * <h2>★ Ba timeout của SMTP, và vì sao thiếu chúng thì class này crash lúc boot</h2>
 * Mặc định của JavaMail là <b>chờ vô hạn</b>. Một máy chủ SMTP treo — không từ chối, không
 * đóng kết nối, chỉ im lặng — giữ luôn luồng Tomcat đang chạy lượt đăng ký, và giữ mãi.
 * Vài chục lượt như thế là hết luồng, và triệu chứng không phải "không gửi được thư" mà là
 * "cả site không phản hồi".
 *
 * <p>Đây đúng là bài học đã ghi ở {@code TurnstileVerifier.HttpConfig}: một trần thời gian
 * khai trong {@code application.yml} mà không tới được nơi cần là kiểu hỏng tệ nhất — im
 * lặng và trông như đúng. Nên ở đây nó không được phép chỉ là một dòng yml: constructor
 * <b>đọc lại</b> ba thuộc tính ấy trên chính đối tượng sẽ gửi thư, và từ chối khởi động nếu
 * thiếu. Thà không chạy được còn hơn chạy với một trần không tồn tại.
 *
 * <h2>★ Mặc định TẮT, và nó nói ra điều đó</h2>
 * Máy dev không có SMTP. Tắt thì {@link #guiMaXacMinh} không làm gì — nhưng nó cũng không
 * được gọi tới, vì {@code SendVerificationEmailUseCase} đã dừng trước đó. Dòng WARN lúc khởi
 * động là thứ duy nhất nói cho một máy công khai biết nó đang quên bật.
 *
 * <h2>★ KHÔNG log mã, và KHÔNG log địa chỉ</h2>
 * Mã là thông tin xác thực dùng một lần (bất biến #9). Địa chỉ là dữ liệu định danh mà
 * FR-AUTH-07 hứa xoá được — một dòng log thì không xoá được, nên chép nó vào log là phá
 * chính lời hứa ấy từ một hướng khác. Mọi dòng log ở đây chỉ có {@code userId} của người
 * nhận, thứ vốn đã có mặt khắp nơi.
 *
 * <p>Có một lối tắt rất tiện mà <b>không được đi</b>: in mã ra log khi tính năng tắt, để dev
 * tự xác minh trên máy mình. Đó là ghi thẳng một thông tin xác thực vào file log, và một
 * dòng như thế sẽ sống sót qua ngày bật tính năng lên.
 *
 * <h2>Câu chữ của lá thư KHÔNG ở đây</h2>
 * Nó ở {@link ThuXacMinhEmail}, trong {@code domain}. Class này chỉ biết <i>cách</i> gửi —
 * máy chủ, cổng, TLS, timeout. Xem javadoc ở đó để biết vì sao ranh giới nằm đúng chỗ ấy,
 * và luật ArchUnit nào đã chỉ ra nó.
 */
@Component
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    /** Ba thuộc tính JavaMail phải có mặt, nếu không thì chờ vô hạn — xem javadoc. */
    private static final List<String> TIMEOUT_BAT_BUOC = List.of(
            "mail.smtp.connectiontimeout",
            "mail.smtp.timeout",
            "mail.smtp.writetimeout");

    private final EmailVerificationProperties properties;
    private final JavaMailSender mailSender;

    /**
     * ★ Chốt "thiếu máy chủ thư thì không boot" kiểm CHUỖI {@code mailHost}, không kiểm sự
     * tồn tại của bean — và khác biệt ấy là một lỗi đã đo được, không phải sự cẩn thận thừa.
     *
     * <p>Bản đầu chỉ có {@code mailSender.getIfAvailable() == null}, dựa trên giả định rằng
     * Spring Boot không dựng {@code JavaMailSender} khi thiếu {@code spring.mail.host}. Đo
     * thật ngày 2026-09-20 bằng {@code ApplicationContextRunner}:
     *
     * <pre>
     *   không khai spring.mail.host   →  KHÔNG có bean
     *   spring.mail.host=             →  CÓ bean, với host = ''      ← chính là ta
     *   spring.mail.host=smtp...      →  CÓ bean
     * </pre>
     *
     * <p>{@code application.yml} viết {@code host: ${OJ_MAIL_HOST:}}, nên khi biến môi trường
     * vắng mặt thì thuộc tính vẫn <b>tồn tại</b> với giá trị rỗng — và
     * {@code @ConditionalOnProperty} coi "tồn tại nhưng rỗng" là có. Bean luôn được dựng, nên
     * phép kiểm cũ <b>không bao giờ nổ</b>: bật tính năng mà quên {@code OJ_MAIL_HOST} thì
     * ứng dụng khởi động bình thường rồi hỏng ở lượt gửi đầu tiên — đúng kiểu hỏng im lặng mà
     * cả class này được viết ra để chặn.
     *
     * @param mailHost   {@code spring.mail.host}. Rỗng = chưa cấu hình máy chủ thư
     * @param mailSender vẫn là {@code ObjectProvider}: nếu ai đó bỏ dòng {@code host} khỏi yml
     *                   thì bean biến mất thật, và một tham số bắt buộc sẽ làm cả context
     *                   không dựng được — tức là bộ IT hỏng vì một tính năng đang tắt
     */
    public SmtpEmailSender(AppProperties app,
                           @Value("${spring.mail.host:}") String mailHost,
                           ObjectProvider<JavaMailSender> mailSender) {
        this.properties = app.auth().emailVerification();
        this.mailSender = mailSender.getIfAvailable();

        if (!properties.enabled()) {
            log.warn("Xác minh email TẮT — không ai nhận được mã, và cột users.email_verified_at "
                    + "sẽ rỗng với mọi tài khoản mới. Trên máy công khai hãy đặt "
                    + "OJ_EMAIL_VERIFICATION_ENABLED=true kèm khối spring.mail.");
            return;
        }
        if (mailHost == null || mailHost.isBlank()) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.enabled = true nhưng thiếu OJ_MAIL_HOST. "
                            + "Không có máy chủ thư thì không lá thư nào đi được, và triệu "
                            + "chứng là 'không ai nhận được mã' — một kiểu hỏng im lặng phát "
                            + "hiện được hàng giờ sau khi deploy. Đặt OJ_MAIL_HOST, hoặc tắt "
                            + "bằng OJ_EMAIL_VERIFICATION_ENABLED=false");
        }
        if (this.mailSender == null) {
            throw new IllegalStateException(
                    "Có OJ_MAIL_HOST nhưng không có bean JavaMailSender. Nhiều khả năng khối "
                            + "spring.mail đã bị gỡ khỏi application.yml — xem javadoc của "
                            + "constructor này");
        }
        kiemTimeout(this.mailSender);
    }

    /**
     * Đọc lại ba timeout trên chính đối tượng sẽ gửi thư.
     *
     * <p>Không kiểm {@code application.yml}: yml là thứ ta viết, còn đây là thứ thư viện thật
     * sự dùng. Hai thứ đó lệch nhau được — một tiền tố sai, một khối đặt nhầm chỗ — và chỉ vế
     * thứ hai mới có hậu quả.
     */
    private static void kiemTimeout(JavaMailSender sender) {
        if (!(sender instanceof JavaMailSenderImpl impl)) {
            // Một hiện thực khác (test, hay một bean tự dựng) thì ta không đọc được cấu hình
            // của nó. Nói ra thay vì im lặng bỏ qua phép kiểm.
            log.warn("JavaMailSender là {} — không kiểm được timeout SMTP. Bảo đảm nó có trần "
                    + "thời gian, nếu không một máy chủ treo sẽ giữ luồng Tomcat vô hạn.",
                    sender.getClass().getName());
            return;
        }
        Properties p = impl.getJavaMailProperties();
        List<String> thieu = TIMEOUT_BAT_BUOC.stream()
                .filter(khoa -> p.getProperty(khoa) == null || p.getProperty(khoa).isBlank())
                .toList();
        if (!thieu.isEmpty()) {
            throw new IllegalStateException(
                    "Thiếu timeout SMTP: " + thieu + ". Mặc định của JavaMail là CHỜ VÔ HẠN, "
                            + "nên một máy chủ thư treo sẽ giữ luồng Tomcat của lượt đăng ký "
                            + "mãi mãi — và triệu chứng không phải 'không gửi được thư' mà là "
                            + "'cả site không phản hồi'. Khai chúng dưới "
                            + "spring.mail.properties.mail.smtp.* trong application.yml");
        }
    }

    @Override
    public void guiMaXacMinh(String den, String tenHienThi, String ma, long soPhutConHan) {
        if (!properties.enabled()) {
            return;
        }

        var thu = new SimpleMailMessage();
        thu.setFrom(properties.from());
        thu.setTo(den);
        thu.setSubject(ThuXacMinhEmail.tieuDe());
        thu.setText(ThuXacMinhEmail.than(tenHienThi, ma, soPhutConHan));

        try {
            mailSender.send(thu);
        } catch (RuntimeException e) {
            // e.toString() mang tên máy chủ SMTP và mã lỗi — hữu ích khi gỡ cấu hình, và
            // không chứa gì bí mật. Địa chỉ người nhận thì KHÔNG có ở đây (xem javadoc class).
            log.warn("Không gửi được thư xác minh: {}", e.toString());
            throw IdentityException.khongGuiDuocThu();
        }
    }
}
