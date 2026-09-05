package dev.oj.identity.domain;

/**
 * Trạng thái 2FA của một tài khoản. Cùng nguyên tắc với {@link Credentials}: thứ nguy hiểm
 * phải <b>vắng mặt</b> khỏi {@link User}, chứ không phải có mặt và mọi người nhớ đừng chạm.
 *
 * <p>{@code toString()} được ghi đè để một dòng {@code log.debug("2fa={}", tf)} viết vô ý
 * không đưa bí mật vào file log — bất biến #9. Bí mật ở đây <i>đã mã hoá</i>, nhưng in bản
 * mã ra log cũng là in một nửa của bài toán ra log.
 *
 * @param userId     {@code users.id}
 * @param secretEnc  bí mật TOTP đã qua {@code SecretCipher}. Chưa dùng được cho tới khi
 *                   giải mã
 * @param enabled    {@code false} = đã sinh bí mật nhưng người dùng chưa chứng minh quét
 *                   được mã QR. Hàng chưa bật KHÔNG có tác dụng gì lúc đăng nhập
 * @param lastStep   bước TOTP đã dùng lần cuối, {@code null} nếu chưa dùng lần nào.
 *                   Chống phát lại — xem javadoc {@link Totp}
 */
public record TwoFactor(long userId, String secretEnc, boolean enabled, Long lastStep) {

    /**
     * Một mã chỉ được dùng MỘT lần.
     *
     * <p>{@code <=} chứ không phải {@code <}: bước bằng nhau là chính mã vừa dùng, và cửa sổ
     * ±1 làm bước nhỏ hơn cũng còn hợp lệ về mặt toán học. Nhận cả hai là mở lại đúng cái lỗ
     * mà {@code last_step} sinh ra để bịt.
     */
    public boolean buocDaDung(long buoc) {
        return lastStep != null && buoc <= lastStep;
    }

    @Override
    public String toString() {
        return "TwoFactor[userId=" + userId + ", enabled=" + enabled + "]";
    }
}
