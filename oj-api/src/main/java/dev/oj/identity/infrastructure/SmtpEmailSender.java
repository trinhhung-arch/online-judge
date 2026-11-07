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

@Component
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private static final List<String> TIMEOUT_BAT_BUOC = List.of(
            "mail.smtp.connectiontimeout",
            "mail.smtp.timeout",
            "mail.smtp.writetimeout");

    private final EmailVerificationProperties properties;
    private final JavaMailSender mailSender;

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

    private static void kiemTimeout(JavaMailSender sender) {
        if (!(sender instanceof JavaMailSenderImpl impl)) {
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
            log.warn("Không gửi được thư xác minh: {}", e.toString());
            throw IdentityException.khongGuiDuocThu();
        }
    }
}
