package dev.oj.identity.domain;

/**
 * Vòng đời một tài khoản. Khớp đúng {@code CHECK (status IN ('ACTIVE','DISABLED','ANONYMIZED'))}
 * của bảng {@code users} (V1).
 *
 * <h2>Không có trạng thái "đã xoá" — cố ý</h2>
 * FR-AUTH-07 cấm xoá cứng, và lý do không phải là sự tiếc rẻ dữ liệu. {@code submissions} có
 * khoá ngoại tới {@code users}: xoá một người là hoặc xoá theo mọi bài nộp của họ — làm thủng
 * bảng xếp hạng của mọi kỳ thi họ từng dự — hoặc để lại một hàng mồ côi mà database từ chối.
 * Cả hai đều tệ hơn việc giữ lại một dòng không còn dữ liệu định danh nào.
 */
public enum UserStatus {

    /** Đăng nhập được, nộp bài được. */
    ACTIVE,

    /**
     * ADMIN đã vô hiệu hoá (FR-AUTH-07). Không đăng nhập được nữa, nhưng dữ liệu còn nguyên
     * và <b>khôi phục được</b> — đây là trạng thái có đường quay lại.
     */
    DISABLED,

    /**
     * Đã ẩn danh hoá. Email và mật khẩu bị xoá thật, tên hiển thị thành {@code [đã xoá #id]},
     * bài nộp và thứ hạng giữ nguyên ({@code frplan.md} mục 3, cách viết lại FR-AUTH-07).
     *
     * <p><b>Một chiều, không có đường về.</b> Database ép điều đó bằng
     * {@code ck_users_anonymized}: trạng thái này mà còn email hoặc còn password_hash là
     * vi phạm ràng buộc, nên không có cách nào "bỏ ẩn danh" mà không tự dựng lại dữ liệu
     * đã mất.
     */
    ANONYMIZED;

    /** Trạng thái duy nhất đăng nhập được. */
    public boolean canLogIn() {
        return this == ACTIVE;
    }

    public static UserStatus fromCode(String code) {
        for (UserStatus s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Trạng thái tài khoản không hợp lệ: " + code);
    }
}
