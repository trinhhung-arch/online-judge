package dev.oj.platform.config;

import java.time.Duration;

/**
 * Danh tính — Bước 4.5, FR-AUTH-02 và FR-AUTH-08.
 *
 * <h2>Bốn con số ở đây KHÔNG được đổi mà không hỏi người</h2>
 * {@code access-ttl} 15 phút và {@code refresh-ttl} 7 ngày là chữ của FR-AUTH-02;
 * {@code max-login-failures} 5 và {@code lockout} 15 phút là hai dòng trong bảng giới hạn
 * của {@code oj-api/CLAUDE.md} mục 8, mà mục đó kết thúc bằng đúng câu <i>"đổi bất kỳ con
 * số nào ở bảng này là phải hỏi người"</i>. Các compact constructor dưới đây <b>crash lúc
 * boot</b> nếu ai đó lặng lẽ nới chúng ra.
 *
 * <h2>Vì sao {@code access-ttl} ngắn là điều kiện để thiết kế này đúng</h2>
 * Access token <b>không tra cứu database</b> — vai trò nằm ngay trong token
 * ({@code CurrentUserProvider.CurrentUser}). Đó là thứ làm nó rẻ, và cũng là thứ làm nó
 * <i>cũ</i>: hạ vai trò một người từ ADMIN xuống USER thì token cũ vẫn còn ADMIN cho tới
 * khi hết hạn. Mười lăm phút là trần của khoảng cũ đó. Kéo dài ra để "đỡ phải refresh"
 * là kéo dài đúng khoảng thời gian ấy.
 *
 * @param jwtSecret        khoá HMAC-SHA256. Đọc từ env, không có mặc định, tối thiểu 32 ký tự
 * @param accessTtl        FR-AUTH-02 — 15 phút
 * @param refreshTtl       FR-AUTH-02 — 7 ngày
 * @param bcryptCost       FR-AUTH-01 — 12. Khớp comment trên {@code users.password_hash}
 * @param maxLoginFailures FR-AUTH-08 — 5 lần sai
 * @param loginWindow      FR-AUTH-08 — trong 1 phút
 * @param lockout          FR-AUTH-08 — khoá 15 phút
 */
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        int bcryptCost,
        int maxLoginFailures,
        Duration loginWindow,
        Duration lockout) {

public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu OJ_JWT_SECRET. Đây là khoá ký access token — chạy mà thiếu nó "
                            + "nghĩa là không phát được token nào, hoặc tệ hơn: phát bằng "
                            + "một khoá mặc định mà ai đọc mã nguồn cũng biết");
        }
        // HMAC-SHA256 sinh khoá 32 byte. Ngắn hơn thế thì phần entropy thiếu được bù bằng
        // padding của HMAC, tức là khoá yếu hơn thuật toán — hạ giá cả chữ ký lẫn token.
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException("OJ_JWT_SECRET quá ngắn (cần >= 32 ký tự)");
        }
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()
                || accessTtl.toMinutes() > 15) {
            throw new IllegalStateException(
                    "oj.auth.access-ttl = " + accessTtl + ". FR-AUTH-02 chốt 15 phút, và đó "
                            + "là trần của khoảng thời gian một vai trò đã bị hạ vẫn còn "
                            + "hiệu lực. Kéo dài là phải hỏi người — CLAUDE.md mục 5.4");
        }
        if (refreshTtl == null || refreshTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalStateException(
                    "oj.auth.refresh-ttl (" + refreshTtl + ") phải LỚN HƠN access-ttl ("
                            + accessTtl + ") — nếu không thì refresh token hết hạn trước "
                            + "thứ nó dùng để làm mới, và người dùng bị đăng xuất mỗi 15 phút");
        }
        if (bcryptCost != 12) {
            throw new IllegalStateException(
                    "oj.auth.bcrypt-cost = " + bcryptCost + ", nhưng FR-AUTH-01 và comment "
                            + "trên users.password_hash đều ghi 12. Hạ cost là làm yếu "
                            + "TOÀN BỘ mật khẩu đã băm trước đó vẫn còn trong database");
        }
        if (maxLoginFailures != 5 || lockout == null || lockout.toMinutes() != 15
                || loginWindow == null || loginWindow.toSeconds() != 60) {
            throw new IllegalStateException(
                    "oj.auth: FR-AUTH-08 chốt 5 lần sai / 1 phút / IP, khoá 15 phút. "
                            + "Nhận được " + maxLoginFailures + " lần / " + loginWindow
                            + " / khoá " + lockout + ". Đây là một dòng trong bảng giới hạn "
                            + "của oj-api/CLAUDE.md mục 8 — đổi là phải hỏi người");
        }
    }
}
