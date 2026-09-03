package dev.oj.identity.api.dto;

import dev.oj.platform.security.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Thân request của {@code AccountAdminController} — FR-ADM-03, Bước 6.6.
 *
 * <p>Không có {@code toString()} nào bị ghi đè ở đây, khác với {@code AuthRequests}: hai
 * record dưới đây <b>không chứa gì bí mật</b> — một vai trò và một cờ boolean. Nói ra điều đó
 * để lần sau thêm một trường mới thì có người nhớ kiểm lại (bất biến #9).
 */
public final class AdminUserRequests {

    private AdminUserRequests() {
    }

    /**
     * @param vaiTro dùng enum chứ không phải {@code String}: một chuỗi lạ bị Jackson từ chối ở
     *               tầng deserialize và thành 400, thay vì đi sâu tới câu {@code UPDATE} rồi
     *               vỡ ở ràng buộc {@code CHECK (role IN (...))} dưới dạng 500
     */
    public record DoiVaiTro(@NotNull Role vaiTro) {
    }

    /** @param hoatDong {@code false} = vô hiệu hoá, {@code true} = mở lại */
    public record DatHoatDong(@NotNull Boolean hoatDong) {
    }
}
