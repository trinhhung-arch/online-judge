package dev.oj.identity.application.port;

/**
 * Chặn tạo tài khoản hàng loạt từ một địa chỉ — FR-AUTH-01.
 *
 * <h2>★ Vì sao đăng ký cần giới hạn RIÊNG, dù đã có rate limit nộp bài</h2>
 * Rate limit nộp bài tính <b>theo người dùng</b>: mỗi tài khoản 10 giây một bài. Nó không nói
 * gì về việc có bao nhiêu tài khoản. Một bot tạo 1 000 tài khoản vẫn tuân thủ hoàn hảo giới
 * hạn ấy trong khi rót 100 bài/giây vào 6 judge slot.
 *
 * <p>Và bài nộp không phải dữ liệu — nó là <b>mã sẽ được thực thi trên máy chấm</b>. Nên cửa
 * đăng ký mở tự do là đường rẻ nhất để biến một máy cá nhân thành máy chạy thuê, mà không cần
 * một lỗ hổng nào: chỉ cần dùng hệ thống đúng như nó được thiết kế.
 *
 * <p>Đây là lý do giới hạn nằm ở đây chứ không ở tầng mạng: Cloudflare chặn được lưu lượng
 * bất thường, nhưng 1 000 lượt đăng ký rải trong một giờ không bất thường chút nào.
 */
public interface RegistrationRateLimiter {

    /**
     * Đếm một lượt đăng ký từ {@code clientIp} và ném nếu đã vượt ngưỡng.
     *
     * <p>Gọi <b>trước</b> khi tạo tài khoản. Đếm cả lượt hỏng là cố ý: một bot dò handle nào
     * còn trống cũng phải trả giá, nếu không thì "đăng ký hỏng" thành một endpoint dò dữ liệu
     * miễn phí.
     *
     * @throws dev.oj.identity.domain.IdentityException {@code identity.dang_ky_qua_nhieu}
     */
    void kiemVaGhiNhan(String clientIp);
}
