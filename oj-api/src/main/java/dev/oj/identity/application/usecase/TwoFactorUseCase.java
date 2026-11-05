package dev.oj.identity.application.usecase;

import dev.oj.identity.application.TotpChecker;
import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaDuPhong;
import dev.oj.identity.domain.Totp;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiresRole
@Service
public class TwoFactorUseCase {

    private static final int SO_MA_DU_PHONG = 10;

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final TwoFactorRepository repository;
    private final SecretCipher cipher;
    private final PasswordHasher hasher;
    private final TotpChecker checker;
    private final AuditLog auditLog;
    private final java.time.Clock clock;
    private final String issuer;

    public TwoFactorUseCase(CurrentUserProvider currentUser, UserRepository users,
                            TwoFactorRepository repository,
                            SecretCipher cipher, PasswordHasher hasher, TotpChecker checker,
                            AuditLog auditLog, java.time.Clock clock) {
        this.currentUser = currentUser;
        this.users = users;
        this.repository = repository;
        this.cipher = cipher;
        this.hasher = hasher;
        this.checker = checker;
        this.auditLog = auditLog;
        this.clock = clock;
        this.issuer = "Online Judge";
    }

    public boolean daBat() {
        return checker.dangBat(currentUser.current().id());
    }

    @Transactional
    public BanNhap batDau() {
        var nguoi = currentUser.current();
        String biMat = Totp.sinhBiMat();
        if (!repository.luuBanNhap(nguoi.id(), cipher.maHoa(biMat))) {
            throw IdentityException.daBatHaiLop();
        }
        return new BanNhap(biMat, Totp.uriOtpauth(issuer, nguoi.handle(), biMat));
    }

    @Transactional
    public List<String> xacNhan(String ma) {
        var nguoi = currentUser.current();
        var tf = repository.tim(nguoi.id()).orElseThrow(IdentityException::chuaBatHaiLop);
        if (tf.enabled()) {
            throw IdentityException.daBatHaiLop();
        }
        Long buoc = Totp.kiem(cipher.giaiMa(tf.secretEnc()), ma == null ? "" : ma.trim(),
                clock.instant().getEpochSecond());
        if (buoc == null) {
            throw IdentityException.totpSai();
        }
        repository.bat(nguoi.id(), buoc);

        List<String> maDuPhong = sinhMaDuPhong();
        repository.thayMaDuPhong(nguoi.id(),
                maDuPhong.stream().map(MaDuPhong::bam).toList());
        auditLog.ghi("TWO_FACTOR_ENABLED", "user", nguoi.id(), Map.of());
        return maDuPhong;
    }

    @Transactional(noRollbackFor = IdentityException.class)
    public void tat(String matKhau, String ma) {
        var nguoi = currentUser.current();
        if (!checker.dangBat(nguoi.id())) {
            throw IdentityException.chuaBatHaiLop();
        }
        xacMinhMatKhau(nguoi.id(), matKhau);
        checker.kiem(nguoi.id(), ma);

        repository.xoa(nguoi.id());
        auditLog.ghi("TWO_FACTOR_DISABLED", "user", nguoi.id(), Map.of());
    }

    private void xacMinhMatKhau(long userId, String matKhau) {
        var c = users.timCredentialsTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (!hasher.khop(matKhau == null ? "" : matKhau, c.passwordHash())) {
            throw IdentityException.saiMatKhauCu();
        }
    }

    private static List<String> sinhMaDuPhong() {
        List<String> ra = new ArrayList<>(SO_MA_DU_PHONG);
        for (int i = 0; i < SO_MA_DU_PHONG; i++) {
            ra.add(MaDuPhong.sinh());
        }
        return ra;
    }

    public record BanNhap(String secretBase32, String otpauthUri) {
        @Override
        public String toString() {
            return "BanNhap[đã ẩn]";
        }
    }
}
