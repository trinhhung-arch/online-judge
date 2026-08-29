package dev.oj.identity.domain;

import java.nio.charset.StandardCharsets;

/**
 * Quy tắc mật khẩu — FR-AUTH-01. Java thuần, test được bằng JUnit trần.
 *
 * <h2>★ Giới hạn trên 72 byte là một lỗi bảo mật thật, không phải sự cầu kỳ</h2>
 * BCrypt <b>cắt cụt im lặng</b> ở byte thứ 72. Nghĩa là nếu không kiểm ở đây thì hai mật khẩu
 * khác nhau trùng 72 byte đầu sẽ băm ra cùng một chuỗi, và mật khẩu thứ hai <i>mở được</i> tài
 * khoản của mật khẩu thứ nhất. Người bị hại lại chính là người cẩn thận nhất — người đặt
 * passphrase dài.
 *
 * <p>Đếm theo <b>byte UTF-8</b>, không phải ký tự: một mật khẩu tiếng Việt có dấu tốn 2–3 byte
 * mỗi ký tự, nên 72 byte có thể chỉ là 24 ký tự. Đếm nhầm bằng {@code length()} là để lọt
 * đúng những mật khẩu bị cắt.
 *
 * <h2>Vì sao không ép "phải có chữ hoa, số và ký tự đặc biệt"</h2>
 * Vì nó không làm mật khẩu mạnh hơn — nó làm mọi người viết {@code Password1!} và dán lên màn
 * hình. FR-AUTH-01 chỉ đòi ≥ 8 ký tự, và đó là quyết định đúng cho một hệ thống mà thiệt hại
 * lớn nhất khi mất tài khoản là mất quyền nộp bài dưới tên mình.
 */
public final class PasswordPolicy {

    /** FR-AUTH-01. */
    public static final int MIN_LENGTH = 8;

    /** Trần cứng của thuật toán BCrypt, tính bằng byte UTF-8. Xem javadoc của class. */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @throws IdentityException {@code INVALID} với câu giải thích cho người dùng */
    public static void kiemTra(String matKhau) {
        if (matKhau == null || matKhau.length() < MIN_LENGTH) {
            throw IdentityException.khongHopLe("identity.mat_khau_qua_ngan",
                    "Mật khẩu phải có ít nhất " + MIN_LENGTH + " ký tự.");
        }
        int soByte = matKhau.getBytes(StandardCharsets.UTF_8).length;
        if (soByte > MAX_BYTES) {
            throw IdentityException.khongHopLe("identity.mat_khau_qua_dai",
                    "Mật khẩu quá dài (tối đa " + MAX_BYTES + " byte, khoảng "
                            + MAX_BYTES + " ký tự tiếng Anh hoặc " + (MAX_BYTES / 3)
                            + " ký tự tiếng Việt có dấu).");
        }
    }
}
