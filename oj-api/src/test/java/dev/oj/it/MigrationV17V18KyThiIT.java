package dev.oj.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class MigrationV17V18KyThiIT {

    @Test
    @DisplayName("★ V17, V18 trên kỳ thi có sẵn: xong từ lâu thì chốt bảng; đã có bản chụp thì giữ dấu đã chụp; vừa hết giờ hay chưa chụp thì để NULL")
    void v17_v18_tren_ky_thi_co_san() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();
            flyway(pg).target(MigrationVersion.fromVersion("16")).load().migrate();
            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                st.execute("""
                        INSERT INTO contests (slug, title, format, starts_at, ends_at, freeze_at,
                                              created_by)
                        VALUES ('xong-lau', 'Xong lâu', 'ICPC', now() - interval '2 days',
                                now() - interval '1 day', now() - interval '25 hours', 3),
                               ('vua-het', 'Vừa hết', 'ICPC', now() - interval '3 hours',
                                now() - interval '1 minute', NULL, 3),
                               ('chua-chup', 'Chưa chụp', 'ICPC', now() - interval '1 hour',
                                now() + interval '1 hour', now() - interval '10 minutes', 3);
                        INSERT INTO contest_standings_frozen (contest_id, user_id, total_score,
                                                              penalty_seconds, solved_count,
                                                              captured_at)
                        SELECT id, 1, 1, 60, 1, now() - interval '25 hours'
                          FROM contests WHERE slug = 'xong-lau';
                        """);
            }

            flyway(pg).load().migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                ResultSet rs = st.executeQuery("""
                        SELECT slug, standings_settled_at IS NOT NULL, frozen_captured_at IS NOT NULL
                          FROM contests
                        """);
                Map<String, String> chotVaChup = new TreeMap<>();
                while (rs.next()) {
                    chotVaChup.put(rs.getString(1), rs.getBoolean(2) + "/" + rs.getBoolean(3));
                }
                assertThat(chotVaChup).as("slug → đã chốt bảng / đã chụp đóng băng").containsExactly(
                        entry("chua-chup", "false/false"),
                        entry("vua-het", "false/false"),
                        entry("xong-lau", "true/true"));
            }
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(
            PostgreSQLContainer pg) {
        return Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed");
    }

    private static Connection ket(PostgreSQLContainer pg) throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }
}
