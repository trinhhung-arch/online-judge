package dev.oj.platform.config;

import java.time.Duration;
import java.util.List;

/**
 * Cloudflare Turnstile — chặn bot ở cửa đăng ký (FR-AUTH-01).
 *
 * <h2>★ Vì sao Turnstile chứ không phải xác minh email</h2>
 * Mục tiêu là chặn <b>bot tạo tài khoản hàng loạt</b>, và xác minh email gần như không làm
 * được việc đó: dịch vụ mail dùng-một-lần có hàng nghìn tên miền, một script lấy hộp thư tạm
 * mất vài trăm mili-giây. Nó chặn người lười, không chặn bot có chủ đích.
 *
 * <p>Xác minh email vẫn là thứ đáng làm — nhưng cho một mục tiêu <i>khác</i>: có một kênh
 * liên lạc đã xác minh để quên mật khẩu và báo kết quả. Đó là FR-AUTH-09, và nó <b>đã được
 * làm</b> ở V13 — {@link EmailVerificationProperties}, mức mềm.
 *
 * <p>Hai thứ ấy sống cạnh nhau mà không thay thế nhau, và điều đó quan trọng: tắt xác minh
 * email KHÔNG hạ mức chống bot đi chút nào, còn tắt Turnstile thì có. Ai định bỏ một trong
 * hai vì "đã có cái kia rồi" thì đang bỏ một hàng rào mà không được gì.
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
 * <h2>★ {@code hostnames}: token phải được giải trên CHÍNH trang của ta</h2>
 * {@code success=true} chỉ nói "token này có thật và chưa dùng" — không nói nó được giải ở đâu.
 * Một widget cho phép cả {@code localhost} (thêm vào dashboard lúc thử ở máy dev, rồi quên) là
 * đủ để ai đó dựng trang {@code localhost} của riêng mình, giải token hàng loạt ở đó (bằng tay
 * hoặc thuê dịch vụ giải) rồi đem nộp vào cửa đăng ký của ta. Siteverify trả về {@code hostname}
 * nơi token được giải; đối chiếu nó với danh sách này là đóng đường ấy ở phía server, không phụ
 * thuộc vào việc danh sách tên miền của widget trên dashboard có được giữ sạch hay không.
 * Bắt buộc khi {@code enabled}, cùng lý do với secret.
 *
 * @param enabled   bật kiểm captcha ở cửa đăng ký
 * @param siteKey   khoá công khai cho widget ở trình duyệt
 * @param secret    secret key từ dashboard Cloudflare. Bắt buộc khi {@code enabled}
 * @param hostnames tên miền được phép làm nơi giải token — so không phân biệt hoa thường.
 *                  Bắt buộc khi {@code enabled}
 * @param verifyUrl endpoint siteverify. Tham số hoá để test trỏ vào máy chủ giả
 * @param timeout   chờ tối đa ngần này cho một lượt siteverify
 */
public record TurnstileProperties(
        boolean enabled,
        String siteKey,
        String secret,
        List<String> hostnames,
        String verifyUrl,
        Duration timeout) {

    public TurnstileProperties {
        hostnames = hostnames == null ? List.of() : List.copyOf(hostnames);
        if (enabled && (hostnames.isEmpty() || hostnames.stream().anyMatch(String::isBlank))) {
            throw new IllegalStateException(
                    "oj.auth.turnstile.enabled = true nhưng OJ_TURNSTILE_HOSTNAMES rỗng — "
                            + "token giải trên trang của bất kỳ ai (kể cả localhost của họ) sẽ "
                            + "được nhận. Đặt đúng tên miền đang phục vụ trang đăng ký, ví dụ "
                            + "OJ_TURNSTILE_HOSTNAMES=oj.vi-du.com");
        }
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
