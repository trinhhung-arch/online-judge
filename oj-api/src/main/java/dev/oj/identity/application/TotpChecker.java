package dev.oj.identity.application;

import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaDuPhong;
import dev.oj.identity.domain.Totp;
import dev.oj.identity.domain.TwoFactor;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Component
public class TotpChecker {

    private static final Logger log = LoggerFactory.getLogger(TotpChecker.class);

    private final TwoFactorRepository repository;
    private final SecretCipher cipher;
    private final AppProperties properties;
    private final AuditLog auditLog;
    private final Clock clock;

    public TotpChecker(TwoFactorRepository repository, SecretCipher cipher, AppProperties properties,
                       AuditLog auditLog, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public boolean dangBat(long userId) {
        return repository.tim(userId).filter(TwoFactor::enabled).isPresent();
    }

    public void kiem(long userId, String ma) {
        Optional<TwoFactor> co = repository.tim(userId).filter(TwoFactor::enabled);
        if (co.isEmpty()) {
            return;
        }
        if (ma == null || ma.isBlank()) {
            throw IdentityException.canTotp();
        }
        Instant bayGio = clock.instant();
        repository.khoaHaiLopToi(userId).filter(toi -> toi.isAfter(bayGio)).ifPresent(toi -> {
            throw IdentityException.haiLopTamKhoa(Duration.between(bayGio, toi));
        });
        if (dung(userId, co.get(), ma.trim())) {
            repository.xoaDemMaSai(userId);
            return;
        }
        ghiSai(userId, bayGio);
        throw IdentityException.totpSai();
    }

    private void ghiSai(long userId, Instant bayGio) {
        var auth = properties.auth();
        Instant toi = bayGio.plus(auth.totpLockout());
        if (repository.ghiMaSai(userId, auth.totpMaxFailures(), toi)) {
            log.warn("Khoá bước hai lớp của user {} tới {} — {} mã sai liên tiếp",
                    userId, toi, auth.totpMaxFailures());
            auditLog.ghi("TWO_FACTOR_LOCKED", "user", userId,
                    Map.of("toi", toi.toString(), "soMaSai", auth.totpMaxFailures()));
        }
    }

    private boolean dung(long userId, TwoFactor tf, String gon) {
        Long buoc = Totp.kiem(cipher.giaiMa(tf.secretEnc()), gon,
                clock.instant().getEpochSecond());
        if (buoc != null) {
            return !tf.buocDaDung(buoc) && repository.ghiBuoc(userId, buoc);
        }

        for (TwoFactorRepository.MaDuPhong m : repository.maDuPhongChuaDung(userId)) {
            if (MaDuPhong.khop(gon, m.codeHash())) {
                return repository.danhDauDaDung(m.id());
            }
        }
        return false;
    }
}
