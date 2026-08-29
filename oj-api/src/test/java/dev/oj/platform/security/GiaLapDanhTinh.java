package dev.oj.platform.security;

/**
 * Cho phép integration test chạy <b>dưới danh nghĩa một người dùng</b> khi gọi thẳng use-case,
 * không qua HTTP.
 *
 * <h2>★ Vì sao class này nằm trong {@code src/test} chứ không phải {@code src/main}</h2>
 * Vì {@link CurrentUserHolder} là package-private, và class này ở <b>cùng package</b> nên đọc
 * được nó. Hệ quả: mã production <i>không có cách nào</i> đặt danh tính của request bằng tay.
 * Đường duy nhất là {@link JwtAuthFilter}, tức là qua một access token đã kiểm chữ ký.
 *
 * <p>Cách khác — mở {@code CurrentUserHolder} thành public rồi thêm một luật ArchUnit cấm gọi
 * — cũng chặn được, nhưng chặn bằng một luật thì có thể bị nới, còn chặn bằng phạm vi truy cập
 * thì trình biên dịch không cho phép thảo luận. Với một hàm mà tác dụng là
 * <i>"trở thành người khác"</i>, chọn cái thứ hai.
 *
 * <p>{@code src/test} không nằm trong artifact được đóng gói, nên class này không tồn tại
 * trên host.
 *
 * <h2>Cách dùng</h2>
 * <pre>{@code
 * try (var phien = GiaLapDanhTinh.dongVai(1L, "dev", Role.USER)) {
 *     submitSolution.thucHien(...);
 * }
 * }</pre>
 * {@code PostgresIT} đã mở sẵn một phiên như vậy quanh mỗi test, nên phần lớn test không cần
 * gọi trực tiếp — chỉ những test cần một vai trò KHÁC mới cần.
 */
public final class GiaLapDanhTinh implements AutoCloseable {

    private GiaLapDanhTinh() {
    }

    public static GiaLapDanhTinh dongVai(long id, String handle, Role role) {
        CurrentUserHolder.dat(new CurrentUserProvider.CurrentUser(id, handle, role));
        return new GiaLapDanhTinh();
    }

    /** Không có ai đang gọi — dùng để kiểm rằng use-case ném 401 chứ không trả dữ liệu. */
    public static GiaLapDanhTinh khach() {
        CurrentUserHolder.xoa();
        return new GiaLapDanhTinh();
    }

    @Override
    public void close() {
        CurrentUserHolder.xoa();
    }
}
