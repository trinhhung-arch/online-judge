package dev.oj.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ {@code CLAUDE.md} mục 6, dòng cuối bảng: <b>migration phải chạy được trên DB rỗng
 * <i>và</i> DB đã có dữ liệu.</b>
 *
 * <p>Nửa đầu đã được cả bộ IT chứng minh — {@link PostgresIT} dựng một container trắng và
 * Flyway chạy hết. Nửa sau thì không ai chứng minh, và nó là nửa nguy hiểm: trên máy dev,
 * migration mới luôn gặp một database trắng; trên host thật, nó luôn gặp một database đầy.
 *
 * <h2>V9 là migration đầu tiên của dự án ĐỘNG VÀO MỘT INDEX ĐANG CÓ</h2>
 * Nó {@code DROP INDEX ux_jobs_one_active_per_type} rồi tạo một unique index khác trên cùng
 * bảng. Một unique index chỉ tạo được nếu dữ liệu <b>hiện có</b> thoả nó — và nếu không thoả
 * thì Flyway hỏng <i>giữa lúc deploy</i>, với một database đã áp một nửa số migration.
 *
 * <p>Ca dưới đây dựng đúng tình huống đáng ngờ nhất: hai job cùng loại đang sống, thuộc hai
 * đề khác nhau. Trước V9 chúng không tồn tại được; sau V9 chúng hợp lệ. Điểm cần kiểm là
 * <b>bước chuyển</b> — và nó chỉ kiểm được bằng cách chạy tới V8, chèn dữ liệu, rồi chạy tiếp.
 *
 * <h2>Container riêng, không dùng chung với {@link PostgresIT}</h2>
 * Bắt buộc: container kia đã ở phiên bản mới nhất từ lúc khởi động. Cái giá là khoảng hai
 * giây và một kết nối — không đi qua Spring context nên không tốn 26 connection của một pool.
 */
class MigrationTrenDuLieuCoSanIT {

    @Test
    @DisplayName("★ V9 gắn được index mới lên một bảng jobs đã có dữ liệu")
    void chay_duoc_tren_db_da_co_du_lieu() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();

            // 1. Chạy tới V8 — trạng thái của một host đang chạy trước khi deploy M6 phần cuối.
            flyway(pg).target(org.flywaydb.core.api.MigrationVersion.fromVersion("8")).load()
                    .migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                // 2. Dữ liệu thật: một người dùng, hai đề, và HAI job cùng loại đang sống.
                st.execute("""
                        INSERT INTO users (handle, email, display_name, password_hash, role)
                        VALUES ('nguoi', 'n@oj.test', 'Người', 'x', 'ADMIN')
                        """);
                st.execute("""
                        INSERT INTO jobs (type, status, params, created_by)
                        VALUES ('TESTDATA_IMPORT', 'RUNNING', '{"problemId": 1}'::jsonb, 1)
                        """);
                // Dòng thứ hai chỉ chèn được VÌ index cũ đã bị V9 thay — nhưng V9 chưa chạy,
                // nên ở đây nó phải bị TỪ CHỐI. Kiểm luôn để ca này chứng minh đúng bước chuyển.
                assertThat(chenDuocKhong(st, """
                        INSERT INTO jobs (type, status, params, created_by)
                        VALUES ('TESTDATA_IMPORT', 'PENDING', '{"problemId": 2}'::jsonb, 1)
                        """))
                        .as("trước V9: hai job cùng loại bị chặn dù khác đề — đó là lỗi V9 sửa")
                        .isFalse();
            }

            // 3. Chạy nốt V9 trên database ĐÃ CÓ dữ liệu.
            flyway(pg).load().migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                assertThat(chenDuocKhong(st, """
                        INSERT INTO jobs (type, status, params, created_by)
                        VALUES ('TESTDATA_IMPORT', 'PENDING', '{"problemId": 2}'::jsonb, 1)
                        """))
                        .as("sau V9: hai SETTER nạp testdata cho hai đề khác nhau không đụng nhau")
                        .isTrue();

                assertThat(chenDuocKhong(st, """
                        INSERT INTO jobs (type, status, params, created_by)
                        VALUES ('TESTDATA_IMPORT', 'PENDING', '{"problemId": 1}'::jsonb, 1)
                        """))
                        .as("★ nhưng một cú double click trên CÙNG một đề vẫn bị chặn — "
                                + "đó là thứ V6 sinh ra để chặn và V9 phải giữ")
                        .isFalse();
            }

            // 4. Hàm tạo partition audit_log vẫn gọi được sau khi V8 đổi nó thành SECURITY
            //    DEFINER — nếu cú pháp sai thì Flyway đã hỏng ở bước 1, nhưng gọi thật mới
            //    chứng minh nó CHẠY được.
            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("SELECT create_audit_log_partition("
                        + "(CURRENT_DATE + INTERVAL '2 month')::date)");
            }
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(
            PostgreSQLContainer pg) {
        return Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration");
    }

    private static Connection ket(PostgreSQLContainer pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    /** {@code true} nếu câu {@code INSERT} đi qua được ràng buộc. */
    private static boolean chenDuocKhong(Statement st, String sql) {
        try {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
