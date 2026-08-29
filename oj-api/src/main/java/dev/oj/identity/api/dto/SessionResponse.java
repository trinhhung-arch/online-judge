package dev.oj.identity.api.dto;

import dev.oj.identity.application.SessionIssuer;

/**
 * Phản hồi của đăng nhập và làm mới phiên — FR-AUTH-02.
 *
 * <h2>Vì sao trả cả hai token trong body chứ không đặt cookie</h2>
 * Cookie {@code HttpOnly} chống được XSS đọc token, nhưng nó mở ra CSRF và buộc phải cấu hình
 * {@code SameSite}, domain, và một tầng chống CSRF riêng. Với một API mà client là một trang
 * đơn cùng gốc, trả token trong body và để client tự cất là ít bộ phận chuyển động hơn — và
 * ít bộ phận chuyển động hơn là ít chỗ sai hơn.
 *
 * <p>Đánh đổi phải nói rõ: XSS trên trang này đọc được access token. Bù lại bằng chỗ khác —
 * access token sống 15 phút, và Markdown của đề được render <b>server-side</b> (FR-PROB-02),
 * nên đường XSS rõ ràng nhất của một Online Judge đã bị bịt.
 *
 * @param expiresIn giây còn lại của {@code accessToken} — client hẹn giờ làm mới trước khi
 *                  hết hạn, thay vì chờ 401 rồi mới thử lại
 */
public record SessionResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        long userId,
        String handle,
        String role) {

    public static SessionResponse tu(SessionIssuer.Session session) {
        return new SessionResponse(
                session.accessToken(),
                session.refreshToken(),
                session.accessTtlSeconds(),
                session.userId(),
                session.handle(),
                session.role().name());
    }

    @Override
    public String toString() {
        return "SessionResponse[userId=" + userId + ", handle=" + handle + "]";
    }
}
