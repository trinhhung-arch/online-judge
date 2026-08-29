package dev.oj.identity.application.port;

/**
 * Băm và so mật khẩu — FR-AUTH-01, BCrypt cost 12.
 *
 * <p>Là một port chứ không phải lời gọi thẳng {@code BCryptPasswordEncoder} vì một lý do rất
 * thực dụng: BCrypt cost 12 tốn khoảng <b>250ms mỗi lần</b> theo đúng thiết kế. Một bộ unit
 * test có ba mươi ca đăng ký sẽ mất tám giây chỉ để băm, và một bộ test chậm là một bộ test
 * người ta thôi chạy. Fake trong test băm bằng một phép biến đổi tầm thường.
 *
 * <p>{@code IdentityIT} vẫn chạy bằng hiện thực thật, nên chi phí thật vẫn được đo ở đúng
 * một chỗ.
 */
public interface PasswordHasher {

    String bam(String matKhauTho);

    /**
     * @param bamDaLuu có thể {@code null} với tài khoản đã ẩn danh hoá. Hiện thực <b>vẫn phải
     *                 tốn thời gian tương đương</b> khi gặp {@code null}, nếu không thì thời
     *                 gian phản hồi tố cáo rằng tài khoản không tồn tại
     */
    boolean khop(String matKhauTho, String bamDaLuu);
}
