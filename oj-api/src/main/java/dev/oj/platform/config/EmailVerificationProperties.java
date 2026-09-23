package dev.oj.platform.config;

import java.time.Duration;

/**
 * Xác minh email — FR-AUTH-09 (V13), <b>mức mềm</b>.
 *
 * <h2>★ Mức mềm nghĩa là gì, và vì sao đó là điều kiện để nhóm này tồn tại</h2>
 * Chưa xác minh thì vẫn đăng nhập được, vẫn nộp bài được, vẫn dự thi được. Nhãn
 * {@code users.email_verified_at} không chặn gì cả.
 *
 * <p>Đó không phải sự nửa vời mà là điều kiện để thêm SMTP vào hệ thống này. Nếu xác minh
 * chặn đăng nhập thì một sự cố của nhà cung cấp thư — thứ ta không điều khiển được — trở
 * thành một sự cố truy cập, và đúng vào ngày contest thì nó chặn những người vừa đăng ký ra
 * khỏi kỳ thi. Với mức mềm, SMTP chết chỉ có nghĩa là hôm ấy không ai xác minh được, và
 * không ai mất gì. Xem {@code docs/adr/016-xac-minh-email-muc-mem.md}.
 *
 * <p>Mục tiêu thật của việc xác minh cũng không phải chặn bot — việc đó là của Turnstile
 * ({@link TurnstileProperties}). Nó là để CÓ một kênh liên lạc đã xác minh, thứ mà
 * "quên mật khẩu qua email" sẽ dựa vào.
 *
 * <h2>★ Mặc định TẮT, cùng lý do với Turnstile</h2>
 * Máy dev và bộ IT không có máy chủ SMTP. Bật mặc định thì {@code ./mvnw verify} trên máy
 * trắng sẽ hỏng ở một chỗ không liên quan gì tới thứ đang sửa.
 *
 * <p>{@code SmtpEmailSender} ghi một dòng <b>WARN</b> lúc khởi động khi nó tắt — một máy
 * công khai quên bật thì đó là thứ duy nhất nói ra điều ấy.
 *
 * <p>Ngược lại, bật mà thiếu {@code from} thì <b>crash lúc boot</b>: mọi lá thư sẽ bị máy
 * chủ nhận từ chối, và triệu chứng là "không ai nhận được mã" — một kiểu hỏng im lặng, xuất
 * hiện hàng giờ sau khi deploy.
 *
 * <h2>Ba con số dưới đây là thứ làm cho mã 6 chữ số an toàn</h2>
 * Không phải độ dài mã. Đọc phần đầu {@code V13__xac_minh_email.sql}: một triệu khả năng
 * chỉ đủ khi số lần thử bị chặn, mã có hạn, và mã mới huỷ mã cũ. Nới bất kỳ con số nào ở
 * đây là nới đúng cái đang giữ cho mã ngắn dùng được.
 *
 * @param enabled        bật gửi thư xác minh. Tắt thì không mã nào được sinh ra
 * @param from           địa chỉ người gửi. Phải thuộc tên miền đã cấu hình SPF/DKIM, nếu
 *                       không thì thư vào thẳng hộp spam — và "mã rơi vào spam" là trải
 *                       nghiệm tệ hơn không có xác minh
 * @param ttl            mã sống được bao lâu
 * @param maxAttempts    gõ sai quá ngần này lần thì mã <b>chết</b>, không phải "chờ rồi thử
 *                       tiếp". Đây là thứ chặn việc dò một triệu khả năng
 * @param resendCooldown khoảng cách tối thiểu giữa hai lần gửi cho cùng một tài khoản. Nó
 *                       chặn việc dùng hệ thống này làm máy gửi thư rác tới một địa chỉ —
 *                       người dùng đã đăng nhập vẫn là người gọi được endpoint gửi lại
 */
public record EmailVerificationProperties(
        boolean enabled,
        String from,
        Duration ttl,
        int maxAttempts,
        Duration resendCooldown) {

    public EmailVerificationProperties {
        if (enabled && (from == null || from.isBlank())) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.enabled = true nhưng thiếu OJ_MAIL_FROM. "
                            + "Không có địa chỉ người gửi thì mọi lá thư bị từ chối, và "
                            + "triệu chứng là 'không ai nhận được mã' — một kiểu hỏng im "
                            + "lặng phát hiện được hàng giờ sau khi deploy");
        }
        if (enabled && !from.contains("@")) {
            throw new IllegalStateException(
                    "OJ_MAIL_FROM = " + from + " không phải một địa chỉ email");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.ttl = " + ttl + " không hợp lệ");
        }
        if (ttl.toHours() > 24) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.ttl = " + ttl + " — quá 24 giờ. Hạn dùng là "
                            + "một trong ba thứ làm cho mã 6 chữ số an toàn (V13): nó giới "
                            + "hạn cửa sổ đoán. Cần lâu hơn thì đổi sang mã dài hơn, đừng "
                            + "nới hạn");
        }
        if (maxAttempts < 1) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.max-attempts = " + maxAttempts
                            + ". Nhỏ hơn 1 nghĩa là không ai xác minh được");
        }
        if (maxAttempts > 10) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.max-attempts = " + maxAttempts + " — quá 10. "
                            + "Đây là hàng rào DUY NHẤT chặn việc dò một triệu khả năng của "
                            + "mã 6 chữ số. Nới nó là hạ mã xuống mức đoán được");
        }
        if (resendCooldown == null || resendCooldown.isNegative()) {
            throw new IllegalStateException(
                    "oj.auth.email-verification.resend-cooldown = " + resendCooldown
                            + " không hợp lệ");
        }
    }
}
