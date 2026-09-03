package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * FR-ADM-03 — ADMIN đổi vai trò và vô hiệu hoá tài khoản. Bước 6.6.
 *
 * <h2>★ Hai thao tác này có một tính chất mà mọi thao tác khác của hệ thống không có</h2>
 * Chúng đổi <b>quyền của người khác</b>. Vì thế cả hai đều phải:
 *
 * <ol>
 *   <li><b>Thu hồi phiên đăng nhập.</b> Access token sống 15 phút và <i>không</i> tra database
 *       ở mỗi request — đó là cả điểm của JWT. Hạ một người từ ADMIN xuống USER mà không thu
 *       hồi refresh token nghĩa là họ giữ quyền ADMIN thêm tối đa 15 phút, và giữ được vô hạn
 *       nếu họ cứ refresh. Với "vô hiệu hoá vì đang gian lận giữa kỳ thi" thì 15 phút là cả
 *       phần còn lại của kỳ thi.</li>
 *   <li><b>Ghi {@code audit_log}.</b> Đổi vai trò là thao tác mà một tháng sau sẽ có người
 *       hỏi "ai làm việc này" — FR-ADM-02 tồn tại chủ yếu cho những dòng như thế.</li>
 * </ol>
 *
 * <p>15 phút là trần không xoá được: nó là hệ quả của việc chọn JWT không tra database. Thu
 * hồi refresh token biến "vô hạn" thành "tối đa 15 phút", và đó là điều tốt nhất kiến trúc này
 * cho phép — ghi rõ ở đây để lần sau không ai tưởng nó tức thì.
 *
 * <h2>Hai chốt tự-làm-hại-mình</h2>
 * ADMIN không tự hạ vai trò và không tự vô hiệu hoá mình. Không phải để chiều người dùng: hệ
 * thống có thể còn đúng <b>một</b> ADMIN, và lúc đó thao tác ấy khoá vĩnh viễn mọi đường quản
 * trị — không có "quên mật khẩu" nào lấy lại được vai trò. Cùng lập luận với
 * {@code AnonymizeAccountUseCase}.
 */
@RequiresRole(Role.ADMIN)
@Service
public class ManageUserUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLog auditLog;

    public ManageUserUseCase(CurrentUserProvider currentUser, UserRepository users,
                             RefreshTokenRepository refreshTokens, AuditLog auditLog) {
        this.currentUser = currentUser;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
    }

    /** @throws IdentityException {@code CONFLICT} nếu tự đổi vai trò của chính mình */
    public void doiVaiTro(long userId, Role vaiTroMoi) {
        khongPhaiChinhMinh(userId, "đổi vai trò");
        if (!users.doiVaiTro(userId, vaiTroMoi.name())) {
            throw IdentityException.khongTimThayNguoiDung();
        }
        // Thu hồi TRƯỚC khi ghi audit: nếu ghi audit hỏng thì quyền vẫn đã bị thu, còn nếu
        // thu hồi hỏng thì ta chưa ghi một dòng nói rằng nó đã xảy ra.
        refreshTokens.thuHoiTatCa(userId, "vai trò hoặc trạng thái tài khoản thay đổi");
        auditLog.ghi("USER_ROLE_CHANGED", "user", userId, Map.of("vaiTroMoi", vaiTroMoi.name()));
    }

    /** @param batHoatDong {@code false} = vô hiệu hoá, {@code true} = mở lại */
    public void datHoatDong(long userId, boolean batHoatDong) {
        khongPhaiChinhMinh(userId, batHoatDong ? "mở lại" : "vô hiệu hoá");
        UserStatus moi = batHoatDong ? UserStatus.ACTIVE : UserStatus.DISABLED;
        if (!users.doiTrangThai(userId, moi.name())) {
            throw IdentityException.khongTimThayNguoiDung();
        }
        if (!batHoatDong) {
            refreshTokens.thuHoiTatCa(userId, "vai trò hoặc trạng thái tài khoản thay đổi");
        }
        auditLog.ghi(batHoatDong ? "USER_ENABLED" : "USER_DISABLED", "user", userId, Map.of());
    }

    private void khongPhaiChinhMinh(long userId, String thaoTac) {
        if (currentUser.current().id() == userId) {
            throw IdentityException.khongTuThaoTacVoiMinh(thaoTac);
        }
    }
}
