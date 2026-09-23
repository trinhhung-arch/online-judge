package dev.oj.identity.infrastructure;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.config.EmailVerificationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Hai chốt "thà không khởi động được còn hơn chạy sai" của {@link SmtpEmailSender}.
 *
 * <h2>Vì sao cả hai đều cần một ca riêng, và vì sao chúng đứng ở đây chứ không ở một IT</h2>
 * Cả hai đều là kiểu hỏng <b>im lặng</b>: ứng dụng khởi động bình thường, mọi test chức năng
 * xanh, và hậu quả chỉ lộ ra hàng giờ sau khi deploy — "không ai nhận được mã", hoặc "cả site
 * không phản hồi". Một bộ IT chạy với tính năng TẮT không đi qua nhánh nào trong hai nhánh ấy.
 *
 * <p>Constructor gọi thẳng được, không cần Spring: nó nhận ba tham số và ném ngay trong thân.
 * Đó chính là lý do hai chốt này được đặt ở constructor thay vì ở một {@code @PostConstruct}
 * hay một lời kiểm lúc gửi.
 *
 * <h2>Ca thứ nhất canh một lỗi ĐÃ CÓ THẬT</h2>
 * Bản đầu của constructor kiểm {@code mailSender == null} thay vì kiểm chuỗi host, dựa trên
 * giả định rằng Boot không dựng bean khi thiếu {@code spring.mail.host}. Đo bằng
 * {@code ApplicationContextRunner} ngày 2026-09-20: {@code spring.mail.host=} (rỗng) <b>vẫn</b>
 * dựng bean, với host {@code ''}. Mà {@code application.yml} viết
 * {@code host: ${OJ_MAIL_HOST:}}, nên thuộc tính luôn tồn tại — phép kiểm cũ không bao giờ nổ.
 */
class SmtpEmailSenderTest {

    private static final String HOST = "smtp.vi-du.test";

    private static AppProperties bat() {
        return AppPropertiesGia.voiXacMinhEmail(new EmailVerificationProperties(
                true, "oj@vi-du.test", Duration.ofMinutes(30), 5, Duration.ofSeconds(60)));
    }

    /** {@code ObjectProvider} không phải functional interface — hai phương thức trừu tượng. */
    private static ObjectProvider<JavaMailSender> cung(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject() throws BeansException {
                return sender;
            }

            @Override
            public JavaMailSender getObject(Object... args) throws BeansException {
                return sender;
            }

            @Override
            public JavaMailSender getIfAvailable() throws BeansException {
                return sender;
            }

            @Override
            public JavaMailSender getIfUnique() throws BeansException {
                return sender;
            }
        };
    }

    private static JavaMailSenderImpl senderVoiTimeout(String... khoa) {
        var impl = new JavaMailSenderImpl();
        impl.setHost(HOST);
        Properties p = new Properties();
        for (String k : khoa) {
            p.setProperty(k, "10000");
        }
        impl.setJavaMailProperties(p);
        return impl;
    }

    private static JavaMailSenderImpl senderDayDu() {
        return senderVoiTimeout("mail.smtp.connectiontimeout", "mail.smtp.timeout",
                "mail.smtp.writetimeout");
    }

    @Test
    @DisplayName("★ bật mà thiếu OJ_MAIL_HOST → KHÔNG khởi động được, dù bean vẫn tồn tại")
    void bat_ma_thieu_host_thi_khong_boot() {
        // Bean CÓ mặt — đúng như production, nơi `host: ${OJ_MAIL_HOST:}` làm thuộc tính luôn
        // tồn tại. Chốt phải nổ vì chuỗi host rỗng, không phải vì thiếu bean.
        assertThatThrownBy(() -> new SmtpEmailSender(bat(), "", cung(senderDayDu())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OJ_MAIL_HOST");

        assertThatThrownBy(() -> new SmtpEmailSender(bat(), "   ", cung(senderDayDu())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SmtpEmailSender(bat(), null, cung(senderDayDu())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★ thiếu MỘT trong ba timeout SMTP → KHÔNG khởi động được")
    void thieu_timeout_thi_khong_boot() {
        // Mặc định của JavaMail là chờ VÔ HẠN: một máy chủ treo giữ luồng Tomcat của lượt
        // đăng ký mãi mãi, và triệu chứng là "cả site không phản hồi", không phải "không gửi
        // được thư". Ba khoá, nên ca này thử thiếu từng khoá một.
        var thieuWrite = senderVoiTimeout("mail.smtp.connectiontimeout", "mail.smtp.timeout");
        var thieuRead = senderVoiTimeout("mail.smtp.connectiontimeout", "mail.smtp.writetimeout");
        var thieuHet = senderVoiTimeout();

        for (var sender : new JavaMailSenderImpl[] { thieuWrite, thieuRead, thieuHet }) {
            assertThatThrownBy(() -> new SmtpEmailSender(bat(), HOST, cung(sender)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timeout SMTP");
        }
    }

    @Test
    @DisplayName("đủ host và đủ ba timeout → dựng được")
    void du_cau_hinh_thi_dung_duoc() {
        assertThatCode(() -> new SmtpEmailSender(bat(), HOST, cung(senderDayDu())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ TẮT thì không chốt nào nổ — bộ IT và máy dev không có SMTP")
    void tat_thi_khong_doi_gi() {
        // Đây là điều kiện để `./mvnw verify` chạy được trên một máy trắng. Nếu ca này đỏ thì
        // mọi IT của dự án đỏ theo, vì không context nào dựng được.
        assertThatCode(() -> new SmtpEmailSender(AppPropertiesGia.macDinh(), "", cung(null)))
                .doesNotThrowAnyException();
    }
}
