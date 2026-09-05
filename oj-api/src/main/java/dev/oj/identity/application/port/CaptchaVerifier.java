package dev.oj.identity.application.port;

/**
 * Kiểm một lời giải captcha do trình duyệt gửi lên — FR-AUTH-01.
 *
 * <h2>★ Kiểm ở SERVER, luôn luôn</h2>
 * Widget ở trình duyệt chỉ sinh ra một token; nó không chứng minh gì cả cho tới khi server
 * hỏi lại Cloudflare rằng token ấy có thật và chưa dùng. Bỏ bước hỏi lại nghĩa là bot chỉ
 * cần gửi một chuỗi bất kỳ vào trường ấy — và hàng rào biến mất trong khi giao diện vẫn hiện
 * ô "tôi không phải robot".
 */
public interface CaptchaVerifier {

    /**
     * @param token    giá trị {@code cf-turnstile-response} từ form
     * @param clientIp IP người gọi, gửi kèm để Cloudflare chấm điểm chính xác hơn
     * @throws dev.oj.identity.domain.IdentityException {@code identity.captcha_khong_hop_le}
     */
    void kiem(String token, String clientIp);
}
