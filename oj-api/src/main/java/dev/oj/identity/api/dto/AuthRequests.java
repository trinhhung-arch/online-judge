package dev.oj.identity.api.dto;

/**
 * Bốn request mang mật khẩu hoặc token, gom vào một file vì chúng chia sẻ đúng một quy tắc —
 * và quy tắc đó quan trọng hơn việc tách file.
 *
 * <h2>★ Vì sao mọi record ở đây đều ghi đè {@code toString()}</h2>
 * Record Java sinh sẵn {@code toString()} in ra <b>mọi</b> trường. Nghĩa là bất kỳ dòng nào
 * dạng {@code log.debug("nhận {}", request)}, bất kỳ thông báo lỗi validation nào nhắc tới
 * object, bất kỳ khung stack trace nào của một thư viện in tham số — đều đưa mật khẩu người
 * dùng vào file log dưới dạng nguyên văn.
 *
 * <p>Đó chính là bất biến #9, và CLAUDE.md gọi rò rỉ qua log là <i>"đường rò rỉ dễ quên
 * nhất"</i>. Cách chặn không phải là nhớ đừng log request; cách chặn là làm cho việc log nó
 * trở nên vô hại.
 *
 * <p><b>Thêm một record mang mật khẩu vào đây thì ghi đè {@code toString()} luôn.</b>
 * {@code AuthRequestsTest} kiểm bằng phản chiếu rằng không record nào trong file này để lộ
 * trường bí mật — nên quên là test đỏ, không phải là một lỗ hổng im lặng.
 */
public final class AuthRequests {

    private AuthRequests() {
    }

    /** FR-AUTH-01. */
    public record Register(String handle, String email, String displayName, String password) {

        @Override
        public String toString() {
            return "Register[handle=" + handle + "]";
        }
    }

    /**
     * FR-AUTH-02. Một ô nhập duy nhất — người dùng không phải khai họ vừa gõ loại nào.
     *
     * @param maHaiLop mã TOTP hoặc mã dự phòng. {@code null} ở lần gọi đầu là bình thường:
     *                 client chưa biết tài khoản này có bật 2FA. Server trả
     *                 {@code identity.can_totp} rồi client hỏi lại kèm mã
     */
    public record Login(String dinhDanh, String password, String maHaiLop) {

        @Override
        public String toString() {
            return "Login[dinhDanh=" + dinhDanh + "]";
        }
    }

    /** FR-AUTH-10 — xác nhận bật 2FA bằng một mã đúng. */
    public record XacNhanHaiLop(String ma) {

        // Mã TOTP sống 30 giây. Ba mươi giây là thừa đủ để một dòng log bị đọc.
        @Override
        public String toString() {
            return "XacNhanHaiLop[]";
        }
    }

    /** FR-AUTH-10 — tắt 2FA: đòi cả mật khẩu lẫn mã hiện tại. */
    public record TatHaiLop(String password, String ma) {

        @Override
        public String toString() {
            return "TatHaiLop[]";
        }
    }

    /** FR-AUTH-02 và FR-AUTH-03: cùng một trường cho làm mới và đăng xuất. */
    public record Refresh(String refreshToken) {

        @Override
        public String toString() {
            return "Refresh[refreshToken=<ẩn>]";
        }
    }

    /** FR-AUTH-04. */
    public record ChangePassword(String matKhauCu, String matKhauMoi) {

        @Override
        public String toString() {
            return "ChangePassword[]";
        }
    }
}
