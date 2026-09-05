package dev.oj.platform.config;

import java.time.Duration;

/**
 * Cloudflare Turnstile — chặn bot ở cửa đăng ký (FR-AUTH-01).
 *
 * <h2>★ Vì sao Turnstile chứ không phải xác minh email</h2>
 * Mục tiêu là chặn <b>bot tạo tài khoản hàng loạt</b>, và xác minh email gần như không làm
 * được việc đó: dịch vụ mail dùng-một-lần có hàng nghìn tên miền, một script lấy hộp thư tạm
 * mất vài trăm mili-giây. Nó chặn người lười, không chặn bot có chủ đích.
 *
 * <p>Xác minh email vẫn là thứ đáng làm — nhưng cho một mục tiêu <i>khác</i>: có một kênh
 * liên lạc đã xác minh để quên mật khẩu và báo kết quả. Đó là FR-AUTH-09, đang hoãn sang
 * v1.1 vì SMTP là một điểm hỏng nữa ({@code frplan.md}).
 *
 * <p>Turnstile không cần SMTP, không thêm điểm hỏng nào vào đường chạy, và hệ thống đằng nào
 * cũng đứng sau Cloudflare.
 *
 * <h2>★ Mặc định TẮT, và đó là đánh đổi có chủ ý</h2>
 * Bật mặc định thì máy dev và bộ IT không chạy được nếu không có khoá — mà khoá thì phải xin
 * từ dashboard Cloudflare. Nên mặc định tắt, và {@code TurnstileVerifier} ghi một dòng
 * <b>WARN</b> lúc khởi động khi nó tắt, để một máy công khai quên bật không im lặng.
 *
 * <p>Ngược lại, {@code enabled = true} mà thiếu secret thì <b>crash lúc boot</b>: một hàng rào
 * bật-nhưng-không-cấu-hình là hàng rào tệ nhất, vì nó trông như đang bảo vệ.
 *
 * <h2>{@code siteKey} là công khai, {@code secret} thì không</h2>
 * Site key nằm trong HTML của mọi trang đăng ký — nó phải công khai để widget chạy được, và
 * biết nó không giúp gì cho người tấn công. Secret thì ngược lại: nó là thứ duy nhất chứng
 * minh lời hỏi siteverify đến từ ta.
 *
 * <p>Site key được trả qua {@code GET /api/v1/auth/captcha} chứ không nhúng cứng vào HTML, vì
 * mỗi môi trường một khoá — nhúng cứng nghĩa là bản dev và bản thật khác nhau ở file tĩnh.
 *
 * @param enabled   bật kiểm captcha ở cửa đăng ký
 * @param siteKey   khoá công khai cho widget ở trình duyệt
 * @param secret    secret key từ dashboard Cloudflare. Bắt buộc khi {@code enabled}
 * @param verifyUrl endpoint siteverify. Tham số hoá để test trỏ vào máy chủ giả
 * @param timeout   chờ tối đa ngần này cho một lượt siteverify
 */
public record TurnstileProperties(
        boolean enabled,
        String siteKey,
        String secret,
        String verifyUrl,
        Duration timeout) {

    public TurnstileProperties {
        if (enabled && (siteKey == null || siteKey.isBlank())) {
            throw new IllegalStateException(
                    "oj.auth.turnstile.enabled = true nhưng thiếu OJ_TURNSTILE_SITE_KEY — "
                            + "widget ở trình duyệt không dựng được, và người dùng sẽ thấy "
                            + "một form không bao giờ gửi được");
        }
        if (enabled && (secret == null || secret.isBlank())) {
            throw new IllegalStateException(
                    "oj.auth.turnstile.enabled = true nhưng thiếu OJ_TURNSTILE_SECRET. "
                            + "Một hàng rào bật mà không cấu hình là hàng rào tệ nhất: nó "
                            + "trông như đang bảo vệ. Lấy secret ở dashboard Cloudflare, "
                            + "hoặc đặt OJ_TURNSTILE_ENABLED=false");
        }
        if (verifyUrl == null || verifyUrl.isBlank()) {
            throw new IllegalStateException("oj.auth.turnstile.verify-url rỗng");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("oj.auth.turnstile.timeout không hợp lệ");
        }
    }
}
