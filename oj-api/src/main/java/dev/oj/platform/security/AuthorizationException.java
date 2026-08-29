package dev.oj.platform.security;

import dev.oj.platform.error.DomainException;

/**
 * Lỗi xác thực và phân quyền ở tầng nền. {@code CLAUDE.md} mục 7: không ném
 * {@code RuntimeException} trần.
 *
 * <h2>Vì sao class này ở {@code platform} chứ không ở {@code identity}</h2>
 * Vì thứ ném nó là {@code JwtCurrentUserProvider} và {@code RequiresRoleAdvisor} — cả hai
 * nằm ở {@code platform.security}, và luật ArchUnit 3b cấm {@code platform} import
 * {@code identity}. {@code IdentityException} nói về <i>đăng nhập</i>; class này nói về
 * <i>request hiện tại có được làm việc này không</i>, một câu hỏi mà mọi module đều hỏi.
 *
 * <h2>401 và 403 nói hai chuyện khác nhau</h2>
 * <ul>
 *   <li>{@code UNAUTHENTICATED} (401) — <i>"tôi không biết bạn là ai"</i>. Client phải làm mới
 *       token rồi thử lại. Frontend đọc {@link #code()} để biết nên gọi
 *       {@code /auth/refresh} hay đá về trang đăng nhập.</li>
 *   <li>{@code FORBIDDEN} (403) — <i>"tôi biết bạn là ai, và không được"</i>. Thử lại là vô ích.</li>
 * </ul>
 *
 * <p><b>Cẩn thận với 403 trên dữ liệu.</b> Vai trò sai thì 403 đúng. Nhưng "bài nộp của người
 * khác" phải là 404, không phải 403 — xem ghi chú NOT_FOUND ở cuối {@link DomainException}.
 * 403 ở đó là xác nhận bản ghi có tồn tại.
 */
public class AuthorizationException extends DomainException {

    private AuthorizationException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    /** Không có header {@code Authorization}, hoặc có mà không phải {@code Bearer}. */
    public static AuthorizationException chuaDangNhap() {
        return new AuthorizationException(Kind.UNAUTHENTICATED, "auth.chua_dang_nhap",
                "Bạn cần đăng nhập để làm việc này.",
                "Request tới một use-case cần danh tính nhưng không mang access token");
    }

    /**
     * Token đúng chữ ký nhưng đã quá {@code exp}.
     *
     * <p>Tách khỏi {@link #tokenKhongHopLe()} vì đây là trạng thái <b>bình thường</b> xảy ra
     * 15 phút một lần với mọi người dùng đang mở tab. Frontend thấy mã này thì lặng lẽ gọi
     * {@code /auth/refresh}; thấy mã kia thì đá về trang đăng nhập. Gộp hai mã làm một nghĩa
     * là hoặc người dùng bị đăng xuất mỗi 15 phút, hoặc frontend thử refresh bằng một token
     * đã bị giả mạo.
     */
    public static AuthorizationException tokenHetHan() {
        return new AuthorizationException(Kind.UNAUTHENTICATED, "auth.token_het_han",
                "Phiên làm việc đã hết hạn.",
                "Access token quá hạn — client nên gọi /api/v1/auth/refresh");
    }

    /**
     * Sai chữ ký, sai định dạng, hoặc header thuật toán không phải cái ta phát ra.
     *
     * <p>Log <b>không</b> ghi giá trị token, kể cả token sai: một lần client gõ nhầm sẽ đưa
     * token thật của người khác vào file log (bất biến #9).
     */
    public static AuthorizationException tokenKhongHopLe() {
        return new AuthorizationException(Kind.UNAUTHENTICATED, "auth.token_khong_hop_le",
                "Phiên làm việc không hợp lệ. Hãy đăng nhập lại.",
                "Access token hỏng hoặc sai chữ ký (không ghi giá trị token — bất biến #9)");
    }

    /** Đã biết người gọi là ai, và vai trò của họ không đủ. */
    public static AuthorizationException thieuQuyen(Role canCo, Role dangCo) {
        return new AuthorizationException(Kind.FORBIDDEN, "auth.thieu_quyen",
                "Bạn không có quyền thực hiện thao tác này.",
                "Cần vai trò " + canCo + " nhưng người gọi là " + dangCo);
    }
}
