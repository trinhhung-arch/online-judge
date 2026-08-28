package dev.oj.platform.security;

/**
 * Ai đang gọi request này. <b>Đây là một seam</b> — {@code docs/build-order.md} Phần 1 nguyên tắc 4.
 *
 * <h2>Vì sao interface này tồn tại từ M1, khi chưa có đăng nhập</h2>
 * M1 (tuần 1-2) phải cho ra vòng nộp bài chạy được, nhưng {@code identity} và JWT tới tận M4
 * (tuần 7). Cách làm bản năng là cho {@code userId} thành một tham số của controller rồi truyền
 * xuống — và tới tuần 7 thì phải sửa lại hai mươi chỗ, mỗi chỗ một cơ hội để sai.
 *
 * <p>Với seam này thì M4 chỉ là: viết {@code JwtCurrentUserProvider}, xoá
 * {@link FixedDevUserProvider}. <b>Không một use-case nào đã viết phải đổi chữ ký.</b>
 *
 * <pre>
 *   M1   FixedDevUserProvider   trả user seed id=1, chỉ chạy ngoài prod
 *   M4   JwtCurrentUserProvider đọc từ SecurityContext           (Bước 4.5)
 * </pre>
 *
 * <h2>Nó KHÔNG phải chỗ kiểm quyền</h2>
 * Class này chỉ trả lời <i>"ai đang gọi"</i>. Câu <i>"người này được làm việc đó không"</i>
 * thuộc về tầng use-case ({@code @RequiresRole}, M4) và về câu query của repository (quyền
 * theo sở hữu). Kiểm quyền ở controller là kiểm ở chỗ dễ đi vòng nhất — một request API trực
 * tiếp bỏ qua UI là chuyện 5 phút (bất biến #11).
 */
public interface CurrentUserProvider {

    /**
     * Người dùng của request hiện tại.
     *
     * @throws dev.oj.platform.error.DomainException với {@code Kind.UNAUTHENTICATED} nếu chưa
     *         đăng nhập. Cố ý không trả {@code Optional}: gần như mọi chỗ gọi đều cần một
     *         người dùng thật, và {@code Optional} chỉ dẫn tới một chuỗi {@code orElseThrow}
     *         lặp lại. Endpoint công khai thì đừng gọi hàm này.
     */
    CurrentUser current();

    /**
     * Danh tính đã xác thực. Cố ý tối giản: chỉ ba thứ mà mọi use-case đều cần.
     *
     * <p>Thêm {@code email} vào đây là mở đường cho việc nó lọt vào log; thêm
     * {@code List<Permission>} là mở đường cho việc kiểm quyền chạy khỏi tầng use-case.
     *
     * @param id      {@code users.id}
     * @param handle  tên đăng nhập, để hiển thị và ghi {@code audit_log}
     * @param role    vai trò tại thời điểm phát token — <b>không</b> đọc lại từ DB mỗi request
     */
    record CurrentUser(long id, String handle, Role role) {

        public CurrentUser {
            if (id <= 0) {
                throw new IllegalArgumentException("id phải dương");
            }
            if (handle == null || handle.isBlank()) {
                throw new IllegalArgumentException("handle không được rỗng");
            }
            if (role == null) {
                throw new NullPointerException("role");
            }
        }

        public boolean isAdmin() {
            return role.isAdmin();
        }

        /**
         * Người này là chủ của bản ghi thuộc về {@code ownerId}?
         *
         * <p><b>Đừng dùng hàm này để lọc dữ liệu sau khi đã load.</b> Chống IDOR phải làm bằng
         * điều kiện chủ sở hữu <i>trong câu query</i> (truy vấn 9 của {@code duong_nong.sql}) —
         * một câu {@code if} viết đúng ở service vẫn là lỗ hổng nếu câu query lấy về quá nhiều.
         * Hàm này chỉ để quyết định hiển thị, ví dụ có hiện nút "chấm lại" hay không.
         */
        public boolean owns(long ownerId) {
            return this.id == ownerId;
        }
    }
}
