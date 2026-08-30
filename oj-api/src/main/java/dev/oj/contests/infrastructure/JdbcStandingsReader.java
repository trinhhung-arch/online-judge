package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Đường <b>ĐỌC</b> bảng xếp hạng — FR-CON-04, Bước 5.7. Pool {@code app}.
 *
 * <p>Đây là {@code PostgresStandingsReader} mà {@code build-order.md} Bước 5.7 gọi tên: đường
 * <b>dự phòng</b> khi Redis chết. Đường chính là {@code RedisStandingsCache}; cả hai phải cho
 * ra <i>cùng một thứ hạng</i>, và bảng này là thứ định nghĩa "đúng" nghĩa là gì.
 *
 * <h2>★ Vì sao bản đóng băng là HAI câu riêng chứ không phải một câu có tên bảng động</h2>
 * Cách gọn là ghép tên bảng vào chuỗi SQL theo cờ {@code dongBang}. Đừng: đó là nối chuỗi
 * trong tầng {@code infrastructure} (bất biến #5), và nó là <i>đúng cái hình dạng</i> mà một
 * lỗ hổng SQL injection có — kể cả khi giá trị ghép vào hôm nay là một boolean an toàn.
 *
 * <h2>Thứ tự xếp hạng viết ra ở SÁU chỗ, và cả sáu phải giống nhau</h2>
 * {@code total_score DESC, penalty_seconds ASC, last_scoring_at ASC NULLS LAST} — khớp đúng
 * {@code ix_contest_standings_rank} của V7. Lệch một chỗ là hai người xem cùng một bảng thấy
 * hai thứ hạng khác nhau, tuỳ họ mở trang top hay trang "quanh mình".
 *
 * <p>{@code NULLS LAST} không có trong định nghĩa index nhưng bắt buộc phải có ở đây:
 * {@code last_scoring_at} là {@code NULL} với người chưa ghi điểm nào. Mặc định của Postgres
 * cho {@code ASC} đúng là {@code NULLS LAST}, nhưng viết ra để nó là một quyết định chứ không
 * phải một mặc định người sau phải tra cứu.
 */
@Repository
public class JdbcStandingsReader implements StandingsReader {

    private static final String TOP = """
            SELECT s.user_id, u.handle, u.display_name, s.total_score, s.penalty_seconds,
                   s.solved_count, s.last_scoring_at, 0 AS cho_sau_freeze
              FROM contest_standings s
              JOIN users u ON u.id = s.user_id
             WHERE s.contest_id = :contestId
             ORDER BY s.total_score DESC, s.penalty_seconds ASC, s.last_scoring_at ASC NULLS LAST
             LIMIT :n
            """;

    private static final String TOP_DONG_BANG = """
            SELECT s.user_id, u.handle, u.display_name, s.total_score, s.penalty_seconds,
                   s.solved_count, s.last_scoring_at,
                   COALESCE((SELECT sum(p.pending_after_freeze)
                               FROM contest_problem_standings_frozen p
                              WHERE p.contest_id = s.contest_id AND p.user_id = s.user_id), 0)
                       AS cho_sau_freeze
              FROM contest_standings_frozen s
              JOIN users u ON u.id = s.user_id
             WHERE s.contest_id = :contestId
             ORDER BY s.total_score DESC, s.penalty_seconds ASC, s.last_scoring_at ASC NULLS LAST
             LIMIT :n
            """;

    private static final String CUA_NGUOI = """
            SELECT s.user_id, u.handle, u.display_name, s.total_score, s.penalty_seconds,
                   s.solved_count, s.last_scoring_at, 0 AS cho_sau_freeze
              FROM contest_standings s
              JOIN users u ON u.id = s.user_id
             WHERE s.contest_id = :contestId AND s.user_id = :userId
            """;

    private static final String CUA_NGUOI_DONG_BANG = """
            SELECT s.user_id, u.handle, u.display_name, s.total_score, s.penalty_seconds,
                   s.solved_count, s.last_scoring_at,
                   COALESCE((SELECT sum(p.pending_after_freeze)
                               FROM contest_problem_standings_frozen p
                              WHERE p.contest_id = s.contest_id AND p.user_id = s.user_id), 0)
                       AS cho_sau_freeze
              FROM contest_standings_frozen s
              JOIN users u ON u.id = s.user_id
             WHERE s.contest_id = :contestId AND s.user_id = :userId
            """;

    private static final String HANG = """
            SELECT t.hang FROM (
                SELECT user_id,
                       rank() OVER (ORDER BY total_score DESC, penalty_seconds ASC,
                                             last_scoring_at ASC NULLS LAST) AS hang
                  FROM contest_standings
                 WHERE contest_id = :contestId) t
             WHERE t.user_id = :userId
            """;

    private static final String HANG_DONG_BANG = """
            SELECT t.hang FROM (
                SELECT user_id,
                       rank() OVER (ORDER BY total_score DESC, penalty_seconds ASC,
                                             last_scoring_at ASC NULLS LAST) AS hang
                  FROM contest_standings_frozen
                 WHERE contest_id = :contestId) t
             WHERE t.user_id = :userId
            """;

    private final JdbcClient jdbc;

    public JdbcStandingsReader(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Dong> top(long contestId, int n, boolean dongBang) {
        return jdbc.sql(dongBang ? TOP_DONG_BANG : TOP)
                .param("contestId", contestId).param("n", n)
                .query(DONG).list();
    }

    @Override
    public Optional<Dong> cuaNguoi(long contestId, long userId, boolean dongBang) {
        return jdbc.sql(dongBang ? CUA_NGUOI_DONG_BANG : CUA_NGUOI)
                .param("contestId", contestId).param("userId", userId)
                .query(DONG).optional();
    }

    @Override
    public Optional<Integer> hang(long contestId, long userId, boolean dongBang) {
        return jdbc.sql(dongBang ? HANG_DONG_BANG : HANG)
                .param("contestId", contestId).param("userId", userId)
                .query(Integer.class).optional();
    }

    private static final RowMapper<Dong> DONG = (rs, i) -> new Dong(
            rs.getLong("user_id"),
            rs.getString("handle"),
            rs.getString("display_name"),
            rs.getInt("total_score"),
            rs.getInt("penalty_seconds"),
            rs.getInt("solved_count"),
            thoiDiem(rs, "last_scoring_at"),
            rs.getInt("cho_sau_freeze"));

    private static Instant thoiDiem(ResultSet rs, String cot) throws SQLException {
        OffsetDateTime value = rs.getObject(cot, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
