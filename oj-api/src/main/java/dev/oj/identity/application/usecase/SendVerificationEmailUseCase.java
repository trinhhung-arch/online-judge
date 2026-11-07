package dev.oj.identity.application.usecase;

import dev.oj.identity.application.EmailVerificationIssuer;
import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.EmailVerificationProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

@RequiresRole
@Service
public class SendVerificationEmailUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final EmailVerificationRepository maXacMinh;
    private final EmailVerificationIssuer issuer;
    private final EmailVerificationProperties properties;
    private final Clock clock;

    public SendVerificationEmailUseCase(CurrentUserProvider currentUser, UserRepository users,
                                        EmailVerificationRepository maXacMinh,
                                        EmailVerificationIssuer issuer,
                                        AppProperties app, Clock clock) {
        this.currentUser = currentUser;
        this.users = users;
        this.maXacMinh = maXacMinh;
        this.issuer = issuer;
        this.properties = app.auth().emailVerification();
        this.clock = clock;
    }

    public void thucHien() {
        if (!properties.enabled()) {
            throw IdentityException.xacMinhEmailTat();
        }

        User nguoiDung = users.timTheoId(currentUser.current().id())
                .orElseThrow(IdentityException::khongTimThayNguoiDung);

        if (nguoiDung.daXacMinhEmail()) {
            throw IdentityException.emailDaXacMinh();
        }
        if (nguoiDung.email() == null) {
            throw IdentityException.khongTimThayNguoiDung();
        }

        khoangCho(nguoiDung.id());

        issuer.phatVaGui(nguoiDung.id(), nguoiDung.email(), nguoiDung.displayName());
    }

    private void khoangCho(long userId) {
        var dangSong = maXacMinh.timMaDangSong(userId);
        if (dangSong.isEmpty()) {
            return;
        }
        Duration daQua = Duration.between(dangSong.get().taoLuc(), clock.instant());
        if (daQua.compareTo(properties.resendCooldown()) < 0) {
            throw IdentityException.guiLaiQuaNhanh(properties.resendCooldown().minus(daQua));
        }
    }
}
