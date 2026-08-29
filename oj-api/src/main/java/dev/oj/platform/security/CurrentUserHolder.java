package dev.oj.platform.security;

import dev.oj.platform.security.CurrentUserProvider.CurrentUser;

/**
 * Kết quả xác thực của request đang chạy, đặt bởi {@link JwtAuthFilter}.
 *
 * <h2>Vì sao lưu cả LỖI chứ không chỉ người dùng</h2>
 * Vì "không có token" và "token hết hạn" phải dẫn tới hai hành vi khác nhau ở frontend: cái
 * đầu là đá về trang đăng nhập, cái sau là lặng lẽ gọi {@code /auth/refresh}. Nếu chỉ lưu
 * người dùng thì cả hai đều thành "chưa đăng nhập", và mọi người bị đăng xuất mỗi 15 phút.
 *
 * <h2>Vì sao filter KHÔNG tự trả 401 khi token hỏng</h2>
 * Vì phần lớn hệ thống này là công khai: danh sách đề, đề đã xuất bản, bảng xếp hạng. Một
 * token cũ còn sót trong localStorage không có lý do gì làm những trang đó không xem được.
 * Filter chỉ <i>ghi nhận</i>; ai thật sự cần danh tính thì gọi {@link CurrentUserProvider}
 * và lỗi nổ ra ở đó — đúng tầng use-case, đúng chỗ bất biến #11 muốn nó nổ.
 *
 * <h2>ThreadLocal — cùng cái bẫy của MDC</h2>
 * {@link #xoa()} nằm trong {@code finally} của filter và <b>không được bỏ</b>: cả thread nền
 * tảng lẫn virtual thread đều được tái sử dụng, nên quên xoá là request sau chạy dưới danh
 * nghĩa người dùng của request trước. Trên một hệ thống mà thứ nó bán là sự công bằng, đó
 * không phải một lỗi rò rỉ, đó là một lần mạo danh.
 */
final class CurrentUserHolder {

    /** Đúng một trong hai trường có giá trị; cả hai {@code null} nghĩa là request ẩn danh. */
    private record KetQua(CurrentUser nguoiDung, AuthorizationException loi) {
    }

    private static final ThreadLocal<KetQua> HIEN_TAI = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    static void dat(CurrentUser nguoiDung) {
        HIEN_TAI.set(new KetQua(nguoiDung, null));
    }

    static void datLoi(AuthorizationException loi) {
        HIEN_TAI.set(new KetQua(null, loi));
    }

    static void xoa() {
        HIEN_TAI.remove();
    }

    /**
     * @throws AuthorizationException {@code auth.token_het_han} nếu token có mà đã hết hạn,
     *         {@code auth.token_khong_hop_le} nếu token hỏng, {@code auth.chua_dang_nhap}
     *         nếu không có token nào
     */
    static CurrentUser batBuoc() {
        KetQua ketQua = HIEN_TAI.get();
        if (ketQua == null) {
            throw AuthorizationException.chuaDangNhap();
        }
        if (ketQua.loi() != null) {
            throw ketQua.loi();
        }
        return ketQua.nguoiDung();
    }
}
