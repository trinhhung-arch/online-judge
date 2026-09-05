package dev.oj.identity.application.port;

/**
 * Mã hoá bí mật TOTP trước khi nó chạm database.
 *
 * <h2>★ Vì sao KHÔNG băm như mật khẩu</h2>
 * Mật khẩu chỉ cần so khớp nên băm là đủ và là cách đúng. Bí mật TOTP thì server phải
 * <b>tính lại mã sáu chữ số từ chính nó</b> ở mỗi lần đăng nhập — nên nó bắt buộc phải lấy
 * lại được. Không có lựa chọn "băm" ở đây, chỉ có "mã hoá" hoặc "để trần".
 *
 * <h2>Điều này thật sự mua được gì</h2>
 * Một bản dump database — sao lưu bị bỏ quên, một câu {@code SELECT} qua lỗ hổng đọc — không
 * còn đủ để sinh mã của người khác. Khoá nằm ở biến môi trường, tức là ở một nơi khác với
 * dữ liệu. Cùng lập luận với {@code refresh_tokens} lưu SHA-256 thay vì token thô (V5).
 *
 * <p>Nó KHÔNG chống được kẻ đã vào được tiến trình đang chạy — người đó đọc được cả khoá.
 * Đây là phòng thủ theo chiều sâu, không phải một lời hứa tuyệt đối.
 */
public interface SecretCipher {

    /** @return chuỗi an toàn để lưu vào cột TEXT */
    String maHoa(String roThô);

    /** @throws IllegalStateException nếu bản mã hỏng hoặc khoá đã đổi */
    String giaiMa(String daMaHoa);
}
