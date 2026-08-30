package dev.oj.identity.domain;

import dev.oj.platform.security.Role;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Một tài khoản. Java thuần — {@code CLAUDE.md} mục 3 luật 1.
 *
 * <h2>★ Ở đây KHÔNG có {@code passwordHash}, và đó là điểm chính của thiết kế</h2>
 * Băm mật khẩu sống ở {@link Credentials}, một record riêng mà <b>chỉ</b>
 * {@code LoginUseCase} và {@code ChangePasswordUseCase} nhìn thấy. Lý do rất cụ thể: record
 * Java sinh sẵn {@code toString()} in ra mọi trường, nên một dòng
 * {@code log.debug("user={}", user)} viết vô ý ở bất kỳ đâu sẽ đưa băm mật khẩu vào file log —
 * đúng bất biến #9, và là <i>đường rò rỉ dễ quên nhất</i> theo cách CLAUDE.md gọi tên nó.
 *
 * <p>Cách chặn không phải là nhớ đừng log. Cách chặn là làm cho thứ nguy hiểm không có mặt
 * trong đối tượng mà mọi người truyền đi khắp nơi.
 *
 * @param id                  {@code users.id}
 * @param handle              tên đăng nhập. Duy nhất <b>không phân biệt hoa thường</b>
 *                            ({@code ux_users_handle_lower})
 * @param email               có thể {@code null} sau khi ẩn danh hoá
 * @param displayName         tên hiển thị
 * @param role                vai trò hiện tại trong database — <b>không</b> phải vai trò trong
 *                            token, hai thứ này lệch nhau tối đa 15 phút (xem {@code AuthProperties})
 * @param status              {@link UserStatus}
 * @param preferredLanguageId ngôn ngữ ưa dùng, {@code null} nếu chưa chọn (FR-AUTH-05)
 * @param createdAt           lúc đăng ký
 */
public record User(
        long id,
        String handle,
        String email,
        String displayName,
        Role role,
        UserStatus status,
        Short preferredLanguageId,
        Instant createdAt) {

    /**
     * Khớp <b>từng ký tự</b> với {@code ck_users_handle_format} trong V1.
     *
     * <p>Hai bản kiểm tra cho cùng một quy tắc là một sự trùng lặp có chủ ý: bản ở đây cho ra
     * một thông báo người dùng đọc được, bản trong database bảo đảm rằng một đường ghi nào đó
     * quên gọi hàm này vẫn không tạo được dữ liệu sai. Nếu bạn sửa một bên, sửa cả hai —
     * {@code IdentityDomainTest} sẽ đỏ nếu chuỗi regex không còn giống nhau.
     */
    public static final String HANDLE_REGEX = "^[A-Za-z0-9_.-]{3,32}$";

    private static final Pattern HANDLE = Pattern.compile(HANDLE_REGEX);

    /**
     * Kiểm tra thô nhất có thể: có {@code @}, có ký tự trước và sau nó, không có khoảng trắng.
     *
     * <p>Cố ý <b>không</b> dùng regex "đúng RFC 5322". Những biểu thức đó dài hàng trăm ký tự,
     * vẫn từ chối nhầm các địa chỉ hợp lệ, và không trả lời được câu hỏi thật sự quan trọng —
     * hộp thư đó có tồn tại không. Câu đó chỉ có một cách trả lời là gửi thư tới, mà
     * FR-AUTH-09 (quên mật khẩu qua email) đã bị hoãn sang v1.1 vì SMTP là một điểm hỏng nữa.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public User {
        if (id <= 0) {
            throw new IllegalArgumentException("id phải dương");
        }
        if (role == null || status == null) {
            throw new NullPointerException("role và status bắt buộc");
        }
    }

    /** Handle đã chuẩn hoá để tra cứu — khớp {@code lower(handle)} của unique index. */
    public static String chuanHoaHandle(String handle) {
        return handle == null ? null : handle.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static String chuanHoaEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** @throws IdentityException {@code INVALID} kèm câu giải thích cho người dùng */
    public static void kiemTraHandle(String handle) {
        if (handle == null || !HANDLE.matcher(handle).matches()) {
            throw IdentityException.khongHopLe("identity.handle_khong_hop_le",
                    "Tên đăng nhập phải dài 3–32 ký tự và chỉ gồm chữ, số, dấu chấm, "
                            + "gạch dưới hoặc gạch ngang.");
        }
    }

    /** @throws IdentityException {@code INVALID} kèm câu giải thích cho người dùng */
    public static void kiemTraEmail(String email) {
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw IdentityException.khongHopLe("identity.email_khong_hop_le",
                    "Địa chỉ email không hợp lệ.");
        }
    }

    /** @throws IdentityException {@code INVALID} nếu rỗng hoặc quá dài */
    public static void kiemTraTenHienThi(String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 64) {
            throw IdentityException.khongHopLe("identity.ten_hien_thi_khong_hop_le",
                    "Tên hiển thị phải có từ 1 đến 64 ký tự.");
        }
    }

    /** Tên hiển thị của một tài khoản đã ẩn danh hoá — {@code frplan.md} mục 3, FR-AUTH-07. */
    public static String tenHienThiSauAnDanh(long userId) {
        return "[đã xoá #" + userId + "]";
    }
}
