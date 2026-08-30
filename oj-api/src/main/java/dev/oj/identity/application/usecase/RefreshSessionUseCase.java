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

import java.time.Clock;
import java.util.Map;

/**
 * FR-AUTH-02 — đổi refresh token lấy access token mới, và <b>xoay vòng</b> refresh token.
 *
 * <h2>★ Xoay vòng, và vì sao nó phát hiện được token bị đánh cắp</h2>
 * Mỗi lần làm mới sinh một refresh token mới và thu hồi cái cũ. Sau đó, bản cũ chỉ còn tồn tại
 * ở đúng một nơi: <b>trên máy người dùng, trong lịch sử</b>. Nên nếu bản cũ quay lại, chỉ có
 * một cách giải thích — <i>có hai bản sao đang tồn tại</i>, tức là một bản đã bị lấy đi.
 *
 * <p>Phản ứng phải là mạnh nhất: thu hồi <b>toàn bộ</b> phiên của người đó. Kẻ đánh cắp và
 * chủ tài khoản cùng bị đăng xuất, chủ tài khoản đăng nhập lại bằng mật khẩu, kẻ kia thì
 * không. Không thể biết ai vừa trình ra token cũ, nên phải xử lý như thể đó là kẻ tấn công.
 *
 * <p>Không có xoay vòng thì một refresh token bị đánh cắp dùng được <b>bảy ngày</b> mà không
 * để lại dấu vết nào.
 *
 * <h2>Trạng thái tài khoản được đọc lại ở đây</h2>
 * Đây là chỗ duy nhất, mỗi 15 phút, mà database được hỏi <i>"người này còn được vào không"</i>.
 * Access token thì không hỏi — nó mang sẵn vai trò, đó là cả điểm mạnh lẫn điểm yếu của nó
 * ({@code AuthProperties}). Nên một tài khoản bị vô hiệu hoá dừng hẳn ở lần làm mới kế
 * tiếp, chậm nhất 15 phút.
 */
@PublicAccess("Chính refresh token là thứ xác thực — đòi access token ở đây thì không ai làm "
        + "mới được sau khi access token hết hạn, tức là đúng lúc cần đến nó nhất.")
@Service
public class RefreshSessionUseCase {

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
