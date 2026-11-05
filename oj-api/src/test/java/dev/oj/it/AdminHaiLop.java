package dev.oj.it;

import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.function.Supplier;

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

    static <T> T lay(Supplier<T> viec) {
        try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
            return viec.get();
        }
    }

    static void lam(Runnable viec) {
        lay(() -> {
            viec.run();
            return null;
        });
    }
}
