package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.EmailVerificationRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaXacMinhEmail;
import dev.oj.identity.domain.User;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@RequiresRole
@Service
public class ConfirmEmailUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final EmailVerificationRepository maXacMinh;
    private final AuditLog auditLog;
    private final int soLanThuToiDa;

    public ConfirmEmailUseCase(CurrentUserProvider currentUser, UserRepository users,
                               EmailVerificationRepository maXacMinh, AuditLog auditLog,
                               AppProperties app) {
        this.currentUser = currentUser;
        this.users = users;
        this.maXacMinh = maXacMinh;
        this.auditLog = auditLog;
        this.soLanThuToiDa = app.auth().emailVerification().maxAttempts();
    }

    @Transactional(noRollbackFor = IdentityException.class)
    public void thucHien(String ma) {
        long userId = currentUser.current().id();
        User nguoiDung = users.timTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (nguoiDung.daXacMinhEmail()) {
            throw IdentityException.emailDaXacMinh();
        }

        String daChuanHoa = MaXacMinhEmail.chuanHoa(ma);
        if (daChuanHoa == null || daChuanHoa.isEmpty()) {
            throw IdentityException.maXacMinhSai();
        }

        var dangSong = maXacMinh.timMaDangSong(userId)
                .orElseThrow(IdentityException::maXacMinhSai);

        if (!MaXacMinhEmail.khop(dangSong.sha256Hex(), daChuanHoa)) {
            throw saiHoacChet(userId, dangSong.id());
        }

        if (!maXacMinh.tieuThu(dangSong.id())) {
            throw IdentityException.maXacMinhSai();
        }
        if (!users.danhDauDaXacMinhEmail(userId)) {
            throw IdentityException.emailDaXacMinh();
        }

        auditLog.ghi("EMAIL_VERIFIED", "user", userId, Map.of());
    }

    private IdentityException saiHoacChet(long userId, long maId) {
        int soLan = maXacMinh.ghiNhanThuSai(maId);
        if (soLan >= soLanThuToiDa) {
            maXacMinh.huyMaCu(userId);
            return IdentityException.maXacMinhDaChet();
        }
        return IdentityException.maXacMinhSai();
    }
}
