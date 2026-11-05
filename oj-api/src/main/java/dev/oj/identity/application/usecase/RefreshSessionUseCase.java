package dev.oj.identity.application.usecase;

import dev.oj.identity.application.SessionIssuer;
import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.RefreshToken;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.identity.domain.User;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

@PublicAccess("Chính refresh token là thứ xác thực — đòi access token ở đây thì không ai làm "
        + "mới được sau khi access token hết hạn, tức là đúng lúc cần đến nó nhất.")
@Service
public class RefreshSessionUseCase {

    private static final String LY_DO_XOAY_VONG = "xoay vòng";

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final SessionIssuer sessions;
    private final AuditLog auditLog;
    private final Clock clock;

    public RefreshSessionUseCase(RefreshTokenRepository refreshTokens, UserRepository users,
                                 SessionIssuer sessions, AuditLog auditLog, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.sessions = sessions;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = IdentityException.class)
    public SessionIssuer.Session thucHien(String tokenTho, String userAgent, String clientIp) {
        if (tokenTho == null || tokenTho.isBlank()) {
            throw IdentityException.phienKhongHopLe();
        }
        RefreshToken token = refreshTokens.timTheoBam(RefreshTokenSecret.bam(tokenTho))
                .orElseThrow(IdentityException::phienKhongHopLe);

        if (token.daThuHoi()) {
            phatHienDungLai(token);
        }
        if (token.daHetHan(clock.instant())) {
            throw IdentityException.phienKhongHopLe();
        }

        User nguoiDung = users.timTheoId(token.userId())
                .orElseThrow(IdentityException::phienKhongHopLe);
        if (!nguoiDung.status().canLogIn()) {
            throw IdentityException.phienKhongHopLe();
        }

        if (!refreshTokens.thuHoi(token.id(), LY_DO_XOAY_VONG, null)) {
            phatHienDungLai(token);
        }
        return sessions.phat(nguoiDung.id(), nguoiDung.handle(), nguoiDung.role(),
                userAgent, clientIp, token.id());
    }

    private void phatHienDungLai(RefreshToken token) {
        int daThuHoi = refreshTokens.thuHoiTatCa(token.userId(),
                "nghi ngờ token bị đánh cắp — một token đã thu hồi được trình lại");
        auditLog.ghi("REFRESH_TOKEN_REUSE_DETECTED", "user", token.userId(),
                Map.of("soPhienDaThuHoi", daThuHoi, "tokenId", token.id()));
        throw IdentityException.phienBiDungLai();
    }
}
