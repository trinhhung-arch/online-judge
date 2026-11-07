package dev.oj.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogChiGhiThemIT {

    private static final String MAT_KHAU_OJ_APP = "ojapp";

    private static final List<String> MOI_QUYEN =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE");

    @Test
    @DisplayName("★ V14 khoá mọi partition đang có — trên DB ĐÃ CÓ dòng audit, lỗ có thật trước đó")
    void v14_khoa_partition_tren_db_da_co_du_lieu() throws SQLException {
        try (PostgreSQLContainer pg = container()) {
            pg.start();

            flyway(pg).target(MigrationVersion.fromVersion("13")).load().migrate();
            try (Connection chu = ketChu(pg); Statement st = chu.createStatement()) {
                st.execute("INSERT INTO audit_log (action, entity_type) "
                        + "VALUES ('TRUOC_V14', 'x'), ('TRUOC_V14', 'y')");
            }
            String thangNay;
            try (Connection app = ketApp(pg)) {
                thangNay = motGiaTri(app, "SELECT format('audit_log_%s', "
                        + "to_char(date_trunc('month', now()), 'YYYY_MM'))");
                assertThat(quyen(app, thangNay, "DELETE"))
                        .as("trước V14 lỗ phải CÓ THẬT — không thì ca này không chứng minh gì")
                        .isTrue();
            }

            flyway(pg).load().migrate();

            try (Connection app = ketApp(pg)) {
                assertThat(duoc(app, "DELETE FROM " + thangNay)).isFalse();
                assertThat(duoc(app, "UPDATE " + thangNay + " SET action = 'DA_SUA'")).isFalse();
                assertThat(duoc(app, "TRUNCATE audit_log_default")).isFalse();
                khongQuyenNaoTrenPartition(app);
                assertThat(demDong(app, "TRUOC_V14"))
                        .as("V14 không động vào dữ liệu — và đọc qua bảng cha vẫn chạy")
                        .isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("★ ứng dụng vẫn ghi và đọc được, và partition tạo SAU V14 cũng khoá sẵn")
    void duong_ghi_van_chay_va_partition_moi_cung_khoa() throws SQLException {
        try (PostgreSQLContainer pg = container()) {
            pg.start();
            flyway(pg).load().migrate();

            try (Connection app = ketApp(pg)) {
                assertThat(duoc(app, "INSERT INTO audit_log (action, entity_type) "
                        + "VALUES ('SAU_V14', 'x')")).isTrue();
                assertThat(demDong(app, "SAU_V14")).isEqualTo(1);
                assertThat(duoc(app, "DELETE FROM audit_log")).as("V8 vẫn giữ bảng cha").isFalse();

                String moi = motGiaTri(app, "SELECT format('audit_log_%s', "
                        + "to_char(date_trunc('month', now() + interval '6 months'), 'YYYY_MM'))");
                assertThat(duoc(app, "SELECT create_audit_log_partition("
                        + "CAST(now() + interval '6 months' AS date))")).isTrue();
                assertThat(partitions(app)).contains(moi);

                assertThat(duoc(app, "DELETE FROM " + moi)).isFalse();
                khongQuyenNaoTrenPartition(app);
            }
        }
    }

    private static void khongQuyenNaoTrenPartition(Connection app) throws SQLException {
        List<String> ds = partitions(app);
        assertThat(ds).as("không có partition nào thì vòng dưới xanh mà không kiểm gì")
                .contains("audit_log_default").hasSizeGreaterThan(1);
        for (String p : ds) {
            for (String q : MOI_QUYEN) {
                assertThat(quyen(app, p, q)).as("oj_app có %s trên %s", q, p).isFalse();
            }
        }
    }

    private static PostgreSQLContainer container() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withCopyFileToContainer(MountableFile.forHostPath(fileRoles()),
                        "/docker-entrypoint-initdb.d/01-roles.sql");
    }

    private static Path fileRoles() {
        for (String goc : List.of("..", ".")) {
            Path p = Path.of(goc, "infra", "postgres", "init", "01-roles.sql").toAbsolutePath()
                    .normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("Không thấy infra/postgres/init/01-roles.sql — test này "
                + "phải dựng role bằng ĐÚNG file của host, không phải một bản chép lại");
    }

    private static FluentConfiguration flyway(PostgreSQLContainer pg) {
        return Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration");
    }

    private static Connection ketChu(PostgreSQLContainer pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static Connection ketApp(PostgreSQLContainer pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), "oj_app", MAT_KHAU_OJ_APP);
    }

    private static boolean duoc(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return false;
            }
            throw e;
        }
    }

    private static boolean quyen(Connection con, String bang, String loai) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT has_table_privilege(current_user, ?, ?)")) {
            ps.setString(1, bang);
            ps.setString(2, loai);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static List<String> partitions(Connection con) throws SQLException {
        List<String> ds = new ArrayList<>();
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT inhrelid::regclass::text FROM pg_inherits "
                        + "WHERE inhparent = 'audit_log'::regclass ORDER BY 1")) {
            while (rs.next()) {
                ds.add(rs.getString(1));
            }
        }
        return ds;
    }

    private static int demDong(Connection con, String action) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT count(*) FROM audit_log WHERE action = ?")) {
            ps.setString(1, action);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String motGiaTri(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
