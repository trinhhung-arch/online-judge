package dev.oj.identity.application;

import dev.oj.identity.application.port.EmailSender;
import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.domain.MaXacMinhEmail;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.EmailVerificationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;

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
