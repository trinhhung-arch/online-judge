package dev.oj.platform.config;

import java.time.Duration;

/**
 * Header bảo mật gắn vào MỌI response — {@code nfrplan.md} Phần 4, chuẩn bị mở ra internet.
 *
 * <h2>★ Vì sao CSP quan trọng với dự án này hơn với một trang web thường</h2>
 * Access token nằm trong {@code localStorage} (xem javadoc {@code khung.js}), nên
 * <b>một lỗ XSS là một tài khoản bị chiếm</b>, không phải một trò đùa hiện hộp thoại. Hiện
 * XSS đang bị chặn bằng kỷ luật viết code — {@code textContent} khắp nơi, đúng một chỗ
 * {@code innerHTML} với HTML mà server đã escape. Kỷ luật ấy đúng cho tới lần sửa tiếp theo
 * của một người chưa đọc javadoc đó. CSP là lưới thứ hai, và nó không phụ thuộc vào trí nhớ.
 *
 * <h2>★ Chuỗi CSP nằm trong config, không hardcode — và nó RẤT dễ làm hỏng trang</h2>
 * Một directive thiếu không báo lỗi ở server: trang vẫn trả 200, chỉ có tài nguyên bị chặn
 * lặng lẽ và console của trình duyệt mới nói ra. Nên đổi nó là phải mở trang thật lên xem
 * console, không phải chỉ chạy test.
 *
 * <p>Chính sách mặc định được viết từ việc quét toàn bộ {@code static/}:
 * <ul>
 *   <li><b>Không có một {@code <script>} nội tuyến nào</b> — nên không cần
 *       {@code 'unsafe-inline'} cho script, tức là CSP ở đây thật sự chặn được XSS chứ không
 *       chỉ trang trí. Giữ được điều này là điều kiện để cả chính sách có giá trị.</li>
 *   <li><b>{@code cdn.jsdelivr.net}</b> — KaTeX, và nó đã có {@code integrity=} (SRI) ở cả
 *       bốn thẻ. CSP cho phép origin; SRI mới là thứ chặn CDN bị chiếm.</li>
 *   <li><b>{@code style-src-attr 'unsafe-inline'}</b> — KaTeX sinh ra hàng loạt thuộc tính
 *       {@code style="..."} lúc chạy. Không có dòng này thì công thức toán vỡ hình. Thuộc
 *       tính style là vector yếu hơn hẳn script nội tuyến, và nó KHÔNG nới lỏng
 *       {@code script-src}.</li>
 *   <li><b>{@code frame-ancestors 'none'}</b> — chặn clickjacking lên {@code quan-tri.html}.
 *       Đây là bản hiện đại của {@code X-Frame-Options}; filter gửi cả hai vì trình duyệt cũ
 *       chỉ hiểu cái sau.</li>
 * </ul>
 *
 * <h2>★ HSTS mặc định TẮT, và đó là chủ ý</h2>
 * {@code Strict-Transport-Security} bảo trình duyệt <i>"từ nay chỉ nói chuyện với host này
 * qua HTTPS"</i>, và trình duyệt <b>nhớ điều đó kể cả sau khi header biến mất</b>. Bật nhầm
 * lúc đang chạy {@code http://localhost:8080} là tự khoá trình duyệt của chính mình khỏi máy
 * dev, gỡ ra phải vào {@code chrome://net-internals/#hsts}.
 *
 * <p>Nên nó chỉ bật khi có tên miền thật và HTTPS thật:
 * {@code OJ_HSTS_MAX_AGE=31536000}. Đặt biến ấy là một hành động có ý thức, không phải một
 * mặc định người ta thừa hưởng.
 *
 * @param contentSecurityPolicy chuỗi CSP đầy đủ, gửi nguyên văn
 * @param hstsMaxAge            0 = tắt. Chỉ bật khi đã có HTTPS thật trên tên miền
 */
public record SecurityHeadersProperties(
        String contentSecurityPolicy,
        Duration hstsMaxAge) {

    public SecurityHeadersProperties {
        if (contentSecurityPolicy == null || contentSecurityPolicy.isBlank()) {
            throw new IllegalStateException(
                    "oj.security-headers.content-security-policy rỗng. Một chuỗi rỗng KHÔNG "
                            + "phải là 'tắt CSP một cách an toàn' — nó là mất lưới thứ hai "
                            + "trong khi token vẫn nằm ở localStorage. Muốn tắt thật thì xoá "
                            + "hẳn SecurityHeadersFilter và ghi ADR nói vì sao");
        }
        if (hstsMaxAge == null || hstsMaxAge.isNegative()) {
            throw new IllegalStateException(
                    "oj.security-headers.hsts-max-age = " + hstsMaxAge + ". Dùng 0 để tắt");
        }
    }
}
