package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.RefreshTokenRepository;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.User;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * FR-AUTH-07 — <b>ẩn danh hoá, không xoá</b>.
 *
 * <h2>Vì sao không có DELETE, và vì sao đó không phải sự tiếc rẻ dữ liệu</h2>
 * {@code submissions.user_id} có khoá ngoại tới {@code users}. Xoá một người là hoặc xoá theo
 * mọi bài nộp của họ — làm thủng bảng xếp hạng của mọi kỳ thi họ từng dự, kể cả những kỳ thi
 * đã kết thúc và đã trao giải — hoặc để lại một hàng mồ côi mà database từ chối nhận.
 *
 * <p>Ẩn danh hoá trả lời đúng yêu cầu thật của người dùng: <i>"đừng để tên tôi ở đó nữa"</i>.
 * Email và mật khẩu bị xoá thật, tên hiển thị thành {@code [đã xoá #1234]}, kết quả thi giữ
 * nguyên. {@code ck_users_anonymized} của V1 biến điều đó thành một ràng buộc chứ không phải
 * một lời hứa: trạng thái này mà còn email hoặc còn băm mật khẩu là database từ chối ghi.
 *
 * <h2>Một chiều, và không ADMIN nào tự làm với chính mình</h2>
 * Không có đường quay lại — dữ liệu định danh đã bị xoá thật thì không dựng lại được. Và
 * ADMIN tự ẩn danh hoá mình là tự khoá quyền quản trị, có thể để hệ thống không còn ADMIN nào.
 * Cả hai đều bị chặn tường minh chứ không dựa vào việc người dùng cẩn thận.
 */
@RequiresRole(Role.ADMIN)
@Service
public class AnonymizeAccountUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AuditLog auditLog;

    public AnonymizeAccountUseCase(CurrentUserProvider currentUser, UserRepository users,
                                   RefreshTokenRepository refreshTokens, AuditLog auditLog) {
        this.currentUser = currentUser;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auditLog = auditLog;
    }

    public void thucHien(long userId) {
        if (currentUser.current().id() == userId) {
            throw IdentityException.khongTheAnDanhChinhMinh();
        }
        User nguoiDung = users.timTheoId(userId)
                .orElseThrow(IdentityException::khongTimThayNguoiDung);
        if (nguoiDung.status() == UserStatus.ANONYMIZED) {
            return;   // idempotent: đã ẩn danh rồi thì không có gì để làm nữa
        }

        users.anDanhHoa(userId, User.tenHienThiSauAnDanh(userId));
        int daThuHoi = refreshTokens.thuHoiTatCa(userId, "tài khoản đã ẩn danh hoá (FR-AUTH-07)");

        // Ghi handle CŨ vào audit_log: đó là bằng chứng cho thấy hành động này đã xảy ra và ai
        // làm. Không ghi email — nó vừa bị xoá theo yêu cầu, chép lại sang bảng khác là làm
        // hỏng chính việc vừa làm.
        auditLog.ghi("USER_ANONYMIZED", "user", userId,
                Map.of("handleCu", nguoiDung.handle(), "soPhienDaThuHoi", daThuHoi));
    }
}
