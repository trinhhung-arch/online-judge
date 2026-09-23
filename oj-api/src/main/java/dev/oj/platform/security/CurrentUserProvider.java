package dev.oj.platform.security;

/**
 * Ai đang gọi request này. <b>Đây là một seam</b> — {@code docs/build-order.md} Phần 1 nguyên tắc 4.
 *
 * <h2>Vì sao interface này tồn tại từ M1, khi chưa có đăng nhập</h2>
 * M1 (tuần 1-2) phải cho ra vòng nộp bài chạy được, nhưng {@code identity} và JWT tới tận M4
 * (tuần 7). Cách làm bản năng là cho {@code userId} thành một tham số của controller rồi truyền
 * xuống — và tới tuần 7 thì phải sửa lại hai mươi chỗ, mỗi chỗ một cơ hội để sai.
 *
 * <p>Với seam này thì M4 chỉ là: viết {@link JwtCurrentUserProvider}, xoá
 * {@code FixedDevUserProvider}. <b>Không một use-case nào đã viết phải đổi chữ ký.</b>
 *
 * <pre>
 *   M1   FixedDevUserProvider   trả user seed id=1, chỉ chạy ngoài prod
 *   M4   JwtCurrentUserProvider đọc từ JwtAuthFilter             (Bước 4.5) ✅ ĐÃ THAY
 * </pre>
 *
 * <p><b>Lời hứa đó đã được kiểm chứng.</b> Bước 4.5 thay toàn bộ cơ chế xác thực và số
 * use-case phải sửa chữ ký là <i>không</i>. Ghi lại ở đây vì đó là bằng chứng cho nguyên tắc
 * 4 của {@code build-order.md} Phần 1, không phải một lời tự khen.
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
     * Danh tính đã xác thực. Cố ý tối giản: chỉ những thứ mà mọi use-case đều cần.
     *
     * <p>Thêm {@code email} vào đây là mở đường cho việc nó lọt vào log; thêm
     * {@code List<Permission>} là mở đường cho việc kiểm quyền chạy khỏi tầng use-case.
     *
     * <h2>★ {@code role} là vai trò HIỆU LỰC, không phải vai trò ghi trong token</h2>
     * Token nói ADMIN mà tài khoản chưa bật 2FA thì {@code role} ở đây là {@link Role#SETTER}
     * và {@code adminChuaBatHaiLop} là {@code true} — {@link JwtCurrentUserProvider} hạ nó.
     * Hạ ở gốc chứ không kiểm ở từng use-case, vì quyền ADMIN không chỉ đi qua
     * {@code @RequiresRole(ADMIN)}: nó còn đi qua mọi câu {@code isAdmin()} và mọi câu SQL
     * {@code :requesterRole = 'ADMIN'} nằm trong use-case mức SETTER/USER — tải testdata mọi
     * đề, đọc source mọi người. Kiểm từng chỗ thì chỗ thứ mười viết sau này sẽ quên.
     *
     * @param id                  {@code users.id}
     * @param handle              tên đăng nhập, để hiển thị và ghi {@code audit_log}
     * @param role                vai trò hiệu lực — lấy từ token (<b>không</b> đọc lại từ DB
     *                            mỗi request), rồi hạ xuống nếu ADMIN chưa qua cổng 2FA
     * @param adminChuaBatHaiLop  token là ADMIN nhưng bị hạ vì chưa bật 2FA. Chỉ để
     *                            {@code RequiresRoleAdvisorConfig} nói đúng lý do bị chặn
     *                            ({@code auth.can_hai_lop}); <b>đừng dùng nó để cấp quyền</b>
     */
    record CurrentUser(long id, String handle, Role role, boolean adminChuaBatHaiLop) {

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
            if (adminChuaBatHaiLop && role != Role.SETTER) {
                // Cờ này chỉ tồn tại trên một danh tính ĐÃ bị hạ. Một ADMIN mang cờ là mâu
                // thuẫn: nó vừa nói "chưa qua cổng" vừa giữ nguyên quyền của người đã qua.
                throw new IllegalArgumentException("adminChuaBatHaiLop chỉ đi với vai trò đã hạ");
            }
        }

        /** Danh tính đọc từ token, chưa hỏi cổng 2FA. */
        public CurrentUser(long id, String handle, Role role) {
            this(id, handle, role, false);
        }

        /**
         * Bản đã hạ của một ADMIN chưa bật 2FA — SETTER, không phải USER: người ấy vẫn soạn
         * được đề <i>của chính mình</i>, thứ mà SETTER nào cũng làm được. Chỉ quyền vượt
         * qua chủ sở hữu là mất.
         */
        CurrentUser haVaiTroViChuaBatHaiLop() {
            return new CurrentUser(id, handle, Role.SETTER, true);
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
