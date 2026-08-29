package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.User;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

/**
 * FR-AUTH-05 — sửa tên hiển thị và ngôn ngữ ưa dùng.
 *
 * <h2>Đổi được đúng hai trường, và danh sách đó là cố ý ngắn</h2>
 * Không đổi được {@code handle} (nó là định danh xuất hiện trong bảng xếp hạng đã lưu và
 * trong {@code audit_log} — đổi được là làm lịch sử nói dối), không đổi được {@code email}
 * (cần xác minh hộp thư mới, mà SMTP đã bị hoãn sang v1.1 cùng FR-AUTH-09), và tuyệt đối
 * không đổi được {@code role} hay {@code status}.
 *
 * <p>Điều cuối là điểm quan trọng: nếu use-case này nhận một {@code User} đầy đủ rồi ghi đè
 * cả dòng, thì một request thừa trường {@code "role": "ADMIN"} là một lần tự thăng chức. Nhận
 * đúng hai tham số nguyên thuỷ thì lỗ hổng đó không tồn tại được.
 *
 * <h2>Ngôn ngữ ưa dùng được database kiểm, không phải use-case</h2>
 * Luật ArchUnit 3 cấm {@code identity} biết {@code judging} tồn tại, mà bảng {@code languages}
 * thuộc về phía kia. Nên chốt là khoá ngoại {@code users.preferred_language_id}, và
 * {@code JdbcUserRepository} dịch lỗi ràng buộc thành một câu người dùng đọc được.
 */
@RequiresRole
@Service
public class UpdateProfileUseCase {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;

    public UpdateProfileUseCase(CurrentUserProvider currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    /** @param ngonNguUaDung {@code null} để xoá lựa chọn */
    public void thucHien(String tenHienThi, Short ngonNguUaDung) {
        User.kiemTraTenHienThi(tenHienThi);
        users.capNhatHoSo(currentUser.current().id(), tenHienThi.trim(), ngonNguUaDung);
    }
}
