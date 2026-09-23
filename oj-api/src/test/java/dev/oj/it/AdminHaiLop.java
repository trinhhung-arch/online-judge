package dev.oj.it;

import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.function.Supplier;

/**
 * Hai trạng thái 2FA của tài khoản ADMIN fixture ({@code users.id = 3}), cho các IT đo đường
 * vòng qua cổng hai lớp.
 *
 * <p>{@code PostgresIT} bật 2FA cho ADMIN trước MỖI test, nên gỡ ở đây không rò sang test
 * khác. Bộ IT dùng chung một database và không truncate, nên đừng gỡ ở một {@code @BeforeEach}
 * chung — xem javadoc {@code goHaiLopCuaAdmin} trong {@code AuthorizationIT}.
 */
final class AdminHaiLop {

    private static final long ADMIN_ID = 3L;

    private AdminHaiLop() {
    }

    static void go(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM user_two_factor WHERE user_id = :id").param("id", ADMIN_ID).update();
    }

    static void bat(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO user_two_factor (user_id, secret_enc, enabled, confirmed_at)
                VALUES (:id, 'fixture-khong-can-giai-ma', TRUE, now())
                ON CONFLICT (user_id) DO UPDATE SET enabled = TRUE, confirmed_at = now()
                """).param("id", ADMIN_ID).update();
    }

    /**
     * Chạy {@code viec} dưới danh nghĩa ADMIN, qua {@code JwtCurrentUserProvider} thật — tức là
     * qua đúng chỗ hạ vai trò. Mỗi lần gọi là một "request" mới: cổng được hỏi lại từ đầu.
     *
     * <p>Đóng phiên xong thì request hiện tại KHÔNG còn danh tính nào. Test cần danh tính USER
     * của {@code PostgresIT} phải làm phần ấy TRƯỚC.
     */
    static <T> T lay(Supplier<T> viec) {
        try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
            return viec.get();
        }
    }

    /** Như {@link #lay}, cho việc không trả gì. Tên khác để lambda không mơ hồ giữa hai bản. */
    static void lam(Runnable viec) {
        lay(() -> {
            viec.run();
            return null;
        });
    }
}
