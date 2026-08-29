package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.PasswordPolicy;
import dev.oj.identity.domain.User;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * FR-AUTH-01 — đăng ký bằng email + mật khẩu, băm BCrypt cost 12.
 *
 * <h2>Vai trò khi đăng ký luôn là USER, không có tham số</h2>
 * Đây là endpoint công khai. Nếu vai trò nhận từ đầu vào — dù chỉ để "tiện seed dữ liệu" —
 * thì bất kỳ ai gọi được API cũng tự tạo cho mình một tài khoản ADMIN, và với một hệ thống mà
 * ADMIN đọc được testdata mọi đề thì đó là kết thúc của tính công bằng.
 *
 * <p>Nâng vai trò là một thao tác riêng của ADMIN (M6, FR-ADM-*), không phải một trường trong
 * form đăng ký.
 *
 * <h2>Kiểm trùng hai lần, cố ý</h2>
 * Kiểm trước khi chèn cho ra câu <i>"handle này đã có người dùng"</i>; unique index của
 * database mới là chốt thật. Khoảng giữa hai bước có đua tranh, và kết quả của cuộc đua đó
 * là một thông báo chung chung thay vì một dòng dữ liệu sai — đánh đổi đúng chiều.
 */
@PublicAccess("Đăng ký là cửa vào hệ thống — người chưa có tài khoản thì không thể có token.")
@Service
public class RegisterUserUseCase {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final AuditLog auditLog;

    public RegisterUserUseCase(UserRepository users, PasswordHasher hasher, AuditLog auditLog) {
        this.users = users;
        this.hasher = hasher;
        this.auditLog = auditLog;
    }

    /** @return {@code users.id} vừa tạo */
    public long thucHien(String handle, String email, String tenHienThi, String matKhau) {
        User.kiemTraHandle(handle);
        User.kiemTraEmail(email);
        User.kiemTraTenHienThi(tenHienThi);
        PasswordPolicy.kiemTra(matKhau);

        String handleChuan = User.chuanHoaHandle(handle);
        String emailChuan = User.chuanHoaEmail(email);
        if (users.daCoHandle(handleChuan)) {
            throw IdentityException.daTonTai("Tên đăng nhập");
        }
        if (users.daCoEmail(emailChuan)) {
            throw IdentityException.daTonTai("Email");
        }

        long id = users.taoMoi(handle.trim(), emailChuan, tenHienThi.trim(), hasher.bam(matKhau));
        auditLog.ghi("USER_REGISTERED", "user", id, Map.of("handle", handle.trim()));
        return id;
    }
}
