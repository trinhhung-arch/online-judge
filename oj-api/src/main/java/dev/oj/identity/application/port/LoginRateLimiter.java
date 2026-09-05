package dev.oj.identity.application.port;

/**
 * Giãn hai lượt đăng nhập <b>THÀNH CÔNG</b> của cùng một tài khoản — FR-AUTH-08 mở rộng.
 *
 * <h2>★ Chỉ lượt THÀNH CÔNG, và đó là điểm mấu chốt của thiết kế</h2>
 * Áp cho lượt sai thì bất kỳ ai cũng khoá được người khác ra ngoài: chỉ cần gõ sai mật khẩu
 * của họ liên tục. Đó là lý do FR-AUTH-08 tính theo IP chứ không theo tài khoản.
 *
 * <p>Nhưng để chạm được hàng rào này, người gọi phải BIẾT mật khẩu đúng — nghĩa là đó là tài
 * khoản của chính họ. Không có nạn nhân nào để khoá.
 *
 * <h2>★ Nó KHÔNG cứu được CPU, và đừng nhầm rằng có</h2>
 * BCrypt đã chạy xong rồi mới biết đăng nhập là đúng, nên 250ms ấy đã tiêu. Trần CPU là việc
 * của {@code bcrypt-concurrency} trong {@code BCryptPasswordHasher}.
 *
 * <p>Thứ hàng rào này mua được là phần SAU đăng nhập: mỗi lượt thành công ghi một dòng
 * {@code refresh_tokens}. Một bot 1 000 tài khoản đăng nhập liên tục làm bảng ấy phình ra và
 * làm mọi truy vấn thu hồi token chậm dần. Hai hàng rào bù nhau, không thay nhau.
 */
public interface LoginRateLimiter {

    /**
     * @throws dev.oj.identity.domain.IdentityException {@code identity.dang_nhap_qua_nhanh}
     */
    void kiemVaGhiNhan(long userId);
}
