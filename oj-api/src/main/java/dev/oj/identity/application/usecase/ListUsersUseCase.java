package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ★ Danh sách người dùng cho ADMIN — FR-ADM-03.
 *
 * <h2>Vì sao lớp này phải tồn tại</h2>
 * {@link ManageUserUseCase} đã cho ADMIN đổi vai trò và khoá tài khoản từ M4, và cả hai chạy
 * đúng. Nhưng chúng nhận {@code userId} — một con số — và <b>không có đường nào tìm ra con số
 * ấy</b>. Trang quản trị vì thế bắt gõ tay mã số lấy từ nhật ký hoặc từ database.
 *
 * <p>Một quyền dùng được chỉ khi mở {@code psql} thì trên thực tế là một quyền chưa có.
 *
 * <h2>Phân trang là bắt buộc, không phải tuỳ chọn</h2>
 * Bất biến #8. Bảng {@code users} là bảng sẽ lớn, và một endpoint ADMIN trả về mọi người dùng
 * là đúng thứ làm sập trang quản trị vào ngày nó cần nhất.
 */
@RequiresRole(Role.ADMIN)
@Service
public class ListUsersUseCase {

    private final UserRepository users;
    private final AppProperties properties;

    public ListUsersUseCase(UserRepository users, AppProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    public CursorPage<UserRepository.TomTatNguoiDung> thucHien(String tim, String cursor,
                                                               Integer size) {
        int gioiHan = CursorPage.clampSize(size,
                properties.page().defaultSize(), properties.page().maxSize());

        List<UserRepository.TomTatNguoiDung> dong =
                users.danhSach(tim, docCursor(cursor), gioiHan + 1);

        return CursorPage.of(dong, gioiHan, t -> String.valueOf(t.id()));
    }

    /**
     * Cursor rác trả về trang đầu, <b>không</b> ném lỗi — cùng lập luận đã ghi ở
     * {@code ListContestsUseCase}: cursor là chi tiết nội bộ mà client chỉ chép lại.
     */
    private static Long docCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
