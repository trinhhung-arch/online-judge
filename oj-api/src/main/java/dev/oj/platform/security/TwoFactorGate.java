package dev.oj.platform.security;

/**
 * Hỏi xem một tài khoản đã đủ điều kiện 2FA để dùng quyền ADMIN chưa.
 *
 * <h2>★ Vì sao là interface ở {@code platform}, không phải lời gọi thẳng sang {@code identity}</h2>
 * {@link RequiresRoleAdvisorConfig} sống ở {@code platform}, mà {@code platform} không được
 * import {@code identity} — chiều phụ thuộc chỉ đi một hướng ({@code CLAUDE.md} mục 3).
 * Nên {@code platform} khai báo cái nó cần, {@code identity} cài đặt. Chiều import vẫn là
 * {@code identity → platform}, ArchUnit vẫn xanh.
 *
 * <h2>★ Vì sao KHÔNG nhét vào claim của JWT</h2>
 * Nhét vào token thì đọc rẻ hơn, nhưng token sống 15 phút: một tài khoản ADMIN vừa bị tắt
 * 2FA vẫn giữ quyền ADMIN thêm 15 phút nữa. Với vai trò thì dự án chấp nhận độ trễ ấy
 * ({@code AuthProperties}); với 2FA thì không nên, vì 2FA tồn tại đúng cho tình huống tài
 * khoản đang bị chiếm.
 *
 * <p>Cái giá là một lượt đọc database mỗi lời gọi use-case ADMIN. Đó là bề mặt hiếm được
 * gọi nhất trong hệ thống — không có ADMIN nào bấm 100 lần một giây.
 */
public interface TwoFactorGate {

    /**
     * @return {@code true} nếu người này được phép dùng quyền ADMIN
     */
    boolean duocDungQuyenAdmin(long userId);
}
