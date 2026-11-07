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

class SmtpEmailSenderTest {

    private static final String HOST = "smtp.vi-du.test";

    private static AppProperties bat() {
        return AppPropertiesGia.voiXacMinhEmail(new EmailVerificationProperties(
                true, "oj@vi-du.test", Duration.ofMinutes(30), 5, Duration.ofSeconds(60)));
    }

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
        assertThatCode(() -> new SmtpEmailSender(AppPropertiesGia.macDinh(), "", cung(null)))
                .doesNotThrowAnyException();
    }
}
