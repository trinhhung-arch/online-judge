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

class MigrationTrenDuLieuCoSanIT {

    @Test
    @DisplayName("★ V9 gắn được index mới lên một bảng jobs đã có dữ liệu")
    void chay_duoc_tren_db_da_co_du_lieu() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();

            flyway(pg).target(org.flywaydb.core.api.MigrationVersion.fromVersion("8")).load()
                    .migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("""
                        INSERT INTO users (handle, email, display_name, password_hash, role)
                        VALUES ('nguoi', 'n@oj.test', 'Người', 'x', 'ADMIN')
                        """);
                st.execute("""
                        INSERT INTO jobs (type, status, params, created_by)
                        VALUES ('TESTDATA_IMPORT', 'RUNNING', '{"problemId": 1}'::jsonb, 1)
                        """);
                assertThat(chenDuocKhong(st, """
                        INSERT INTO jobs (type, status, params, created_by)
                        VALUES ('TESTDATA_IMPORT', 'PENDING', '{"problemId": 2}'::jsonb, 1)
                        """))
                        .as("trước V9: hai job cùng loại bị chặn dù khác đề — đó là lỗi V9 sửa")
                        .isFalse();
            }

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

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("SELECT create_audit_log_partition("
                        + "(CURRENT_DATE + INTERVAL '2 month')::date)");
            }
        }
    }

    @Test
    @DisplayName("★ V12 bỏ được cột ordinal kể cả khi dữ liệu đang có hai đề trùng ordinal")
    void v12_chay_duoc_tren_du_lieu_ordinal_trung() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();

            flyway(pg).target(org.flywaydb.core.api.MigrationVersion.fromVersion("11")).load()
                    .migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("""
                        INSERT INTO users (handle, email, display_name, password_hash, role)
                        VALUES ('nguoi', 'n@oj.test', 'Người', 'x', 'ADMIN')
                        """);
                st.execute("""
                        INSERT INTO problems (code, title, statement_md, statement_hash,
                                              time_limit_ms, memory_limit_kb, owner_id)
                        VALUES ('DE-A', 'Đề A', 'x', repeat('a', 64), 1000, 262144, 1),
                               ('DE-D', 'Đề D', 'x', repeat('d', 64), 1000, 262144, 1)
                        """);
                st.execute("""
                        INSERT INTO contests (slug, title, format, starts_at, ends_at,
                                              created_by)
                        VALUES ('ky-thi', 'Kỳ thi', 'ICPC', now() + INTERVAL '1 hour',
                                now() + INTERVAL '4 hour', 1)
                        """);
                st.execute("""
                        INSERT INTO contest_problems (contest_id, problem_id, label, ordinal,
                                                      points)
                        SELECT c.id, p.id, CASE WHEN p.code = 'DE-A' THEN 'A' ELSE 'D' END, 1, 100
                          FROM contests c, problems p
                         WHERE c.slug = 'ky-thi' AND p.code IN ('DE-A', 'DE-D')
                        """);
            }

            flyway(pg).load().migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                assertThat(chenDuocKhong(st, "SELECT ordinal FROM contest_problems"))
                        .as("cột ordinal phải biến mất, không còn đường nào đọc nó")
                        .isFalse();

                var rs = st.executeQuery("""
                        SELECT label FROM contest_problems
                         ORDER BY length(label), label
                        """);
                var nhan = new java.util.ArrayList<String>();
                while (rs.next()) nhan.add(rs.getString(1));
                assertThat(nhan)
                        .as("thứ tự giờ do nhãn quyết định, và nó xác định")
                        .containsExactly("A", "D");
            }
        }
    }

    @Test
    @DisplayName("★ V13 thay được ck_users_anonymized khi đã có tài khoản ẩn danh hoá")
    void v13_chay_duoc_khi_da_co_tai_khoan_an_danh() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();

            flyway(pg).target(org.flywaydb.core.api.MigrationVersion.fromVersion("12")).load()
                    .migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("""
                        INSERT INTO users (handle, email, display_name, password_hash, role)
                        VALUES ('con-dung', 'con@oj.test', 'Còn dùng', 'x', 'USER')
                        """);
                st.execute("""
                        INSERT INTO users (handle, email, display_name, password_hash, status)
                        VALUES ('da-xoa', NULL, '[đã xoá #2]', NULL, 'ANONYMIZED')
                        """);
            }

            flyway(pg).load().migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                var rs = st.executeQuery(
                        "SELECT count(*) FROM users WHERE email_verified_at IS NOT NULL");
                rs.next();
                assertThat(rs.getInt(1))
                        .as("★ V13 cố ý KHÔNG backfill: tài khoản cũ chưa từng xác minh, và "
                                + "đánh dấu chúng là đã xác minh là ghi một điều không đúng")
                        .isZero();

                assertThat(chenDuocKhong(st, """
                        UPDATE users SET email_verified_at = now() WHERE handle = 'con-dung'
                        """))
                        .as("tài khoản đang dùng thì xác minh được bình thường")
                        .isTrue();

                assertThat(chenDuocKhong(st, """
                        UPDATE users SET email_verified_at = now() WHERE handle = 'da-xoa'
                        """))
                        .as("★ nhưng tài khoản đã ẩn danh hoá thì KHÔNG — FR-AUTH-07 hứa xoá "
                                + "dữ liệu định danh, và một mốc 'đã xác minh email lúc 10:03' "
                                + "là một khẳng định về một địa chỉ vừa bị xoá")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("★ V15 chạy trên tài khoản đang bật 2FA: không khoá oan, jar cũ vẫn ghi được")
    void v15_chay_duoc_khi_da_co_nguoi_bat_hai_lop() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();
            flyway(pg).target(org.flywaydb.core.api.MigrationVersion.fromVersion("14")).load()
                    .migrate();
            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("""
                        INSERT INTO users (handle, email, display_name, password_hash, role)
                        VALUES ('co-2fa', 'a@oj.test', 'A', 'x', 'ADMIN'),
                               ('chua-2fa', 'b@oj.test', 'B', 'x', 'USER');
                        INSERT INTO user_two_factor (user_id, secret_enc, enabled, last_step, confirmed_at)
                        VALUES (1, 'bi-mat-da-ma-hoa', TRUE, 12345, now());
                        """);
            }
            flyway(pg).load().migrate();
            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                var rs = st.executeQuery(
                        "SELECT failed_attempts, locked_until FROM user_two_factor WHERE user_id = 1");
                rs.next();
                assertThat(rs.getObject(1)).as("dòng cũ nhận DEFAULT 0, không phải NULL").isEqualTo(0);
                assertThat(rs.getObject(2)).as("★ không ai bị khoá bước hai lớp chỉ vì deploy").isNull();
                assertThat(chenDuocKhong(st, """
                        INSERT INTO user_two_factor (user_id, secret_enc, enabled)
                        VALUES (2, 'ban-nhap', FALSE)
                        """)).as("★ jar cũ vẫn ghi được sau V15").isTrue();
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

    private static boolean chenDuocKhong(Statement st, String sql) {
        try {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
