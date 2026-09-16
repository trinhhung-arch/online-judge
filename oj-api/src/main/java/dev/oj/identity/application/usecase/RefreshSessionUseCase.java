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
 *
 * <h2>★ Thu hồi TRƯỚC, phát SAU — và lượt thu hồi là chốt duy nhất</h2>
 * Bản đầu đọc {@code revoked_at}, thấy {@code NULL}, phát token mới, rồi mới thu hồi token cũ
 * bằng một câu lệnh khác. Mọi request lọt vào giữa lượt đọc và lượt thu hồi đều thấy token còn
 * sống: tám request song song với cùng một token trộm được thì bảy request nhận phiên riêng, và
 * {@code REFRESH_TOKEN_REUSE_DETECTED} không bao giờ được ghi (đo 2026-09-16,
 * {@code SessionLifecycleHttpIT}). Phép kiểm {@code daThuHoi()} phía trên chỉ bắt được lần
 * dùng lại TUẦN TỰ.
 *
 * <p>Giờ {@link RefreshTokenRepository#thuHoi} là một phép so-rồi-đổi nguyên tử, chạy trước khi
 * phát. Postgres xếp hàng các request bằng khoá dòng; đúng một request đổi được dòng, mọi
 * request khác nhận {@code false} và bị xử lý y hệt một lần trình lại token cũ — vì đó chính
 * là điều vừa xảy ra.
 *
 * <p>{@code @Transactional} để lượt thu hồi và lượt phát là một: phát hỏng giữa chừng (mất kết
 * nối database) thì token cũ sống lại, người dùng bấm lại được. Không có nó thì token cũ đã
 * chết mà token mới chưa có, và lần thử lại kế tiếp bị nhận nhầm là token bị đánh cắp.
 * {@code noRollbackFor} vì nhánh phát hiện dùng lại GHI rồi mới ném: thu hồi toàn bộ phiên và
 * dòng {@code audit_log} ấy phải được commit, không phải bị cuộn lại cùng ngoại lệ.
 */
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

        // ★ Chốt: chỉ một request đổi được dòng này. Thua là token đã bị trình ra ở nơi khác.
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
