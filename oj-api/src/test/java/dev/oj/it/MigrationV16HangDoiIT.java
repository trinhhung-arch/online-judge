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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ V16 trên một host <b>đang chấm dở</b> — {@code CLAUDE.md} mục 6: migration chạy được trên
 * DB rỗng <i>và</i> DB đã có dữ liệu. Nửa "DB rỗng" cả bộ IT đã chứng minh.
 *
 * <p>Lúc deploy, {@code judge_queue} có bài đang chờ và bài đang được chấm. Dòng cũ phải nhận
 * {@code reap_count = 0} — không thì bài đang chấm dở bị tính như đã thả lại, và chạm trần sớm
 * hơn lời hứa. Và câu thu hồi của jar <b>đang chạy</b> — không biết cột mới — phải còn chạy:
 * lùi jar không cần lùi schema.
 *
 * <p>Lớp riêng chứ không thêm vào {@link MigrationTrenDuLieuCoSanIT}: lớp ấy đã chạm trần 300
 * dòng ({@code CLAUDE.md} mục 7). Cùng cách dựng: container riêng, không Spring context.
 */
class MigrationV16HangDoiIT {

    @Test
    @DisplayName("★ V16 chạy trên hàng đợi đang có bài: dòng cũ nhận 0, câu thu hồi của jar cũ vẫn chạy")
    void v16_chay_duoc_khi_hang_doi_dang_co_bai() throws SQLException {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();
            flyway(pg).target(MigrationVersion.fromVersion("15")).load().migrate();
            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                // Người dùng 1, đề 1, ngôn ngữ 1 đến từ seed dev và seed tham chiếu.
                st.execute("""
                        INSERT INTO source_blobs (sha256, content, byte_size)
                        VALUES (repeat('a', 64), 'int main(){}', 12);
                        INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                                 source_bytes, testdata_version)
                        VALUES (1, 1, 1, repeat('a', 64), 12, 1),
                               (1, 1, 1, repeat('a', 64), 12, 1);
                        INSERT INTO judge_queue (submission_id, priority, attempt)
                        SELECT min(id), 0, 0 FROM submissions;
                        INSERT INTO judge_queue (submission_id, priority, attempt, claimed_at, lease_until)
                        SELECT max(id), 0, 1, now() - interval '3 minutes', now() - interval '1 minute'
                          FROM submissions;
                        """);
            }

            flyway(pg).load().migrate();

            try (Connection con = ket(pg); Statement st = con.createStatement()) {
                ResultSet rs = st.executeQuery("SELECT count(*), min(reap_count), max(reap_count) "
                        + "FROM judge_queue");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.getObject(2)).as("dòng cũ nhận DEFAULT 0, không phải NULL").isEqualTo(0);
                assertThat(rs.getObject(3)).isEqualTo(0);

                // Đúng câu REAP_EXPIRED của jar trước V16 — không biết reap_count tồn tại.
                ResultSet thaLai = st.executeQuery("""
                        UPDATE judge_queue
                           SET claimed_at = NULL, lease_until = NULL, claimed_by_host = NULL
                         WHERE claimed_at IS NOT NULL AND lease_until < now()
                        RETURNING submission_id
                        """);
                assertThat(thaLai.next()).as("★ jar cũ vẫn thu hồi được sau V16").isTrue();
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
