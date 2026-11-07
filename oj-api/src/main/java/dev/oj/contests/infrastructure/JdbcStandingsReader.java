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
            WITH bang AS (
                SELECT n.user_id,
                       COALESCE(f.total_score, 0) AS total_score,
                       COALESCE(f.penalty_seconds, 0) AS penalty_seconds,
                       COALESCE(f.solved_count, 0) AS solved_count,
                       f.last_scoring_at,
                       k.freeze_at
                  FROM (SELECT user_id FROM contest_standings WHERE contest_id = :contestId
                        UNION
                        SELECT user_id FROM contest_standings_frozen
                         WHERE contest_id = :contestId) n
                  CROSS JOIN (SELECT freeze_at FROM contests WHERE id = :contestId) k
                  LEFT JOIN contest_standings_frozen f
                         ON f.contest_id = :contestId AND f.user_id = n.user_id)
            SELECT b.user_id, u.handle, u.display_name, b.total_score, b.penalty_seconds,
                   b.solved_count, b.last_scoring_at,
                   (SELECT count(*) FROM submissions s
                     WHERE s.user_id = b.user_id AND s.contest_id = :contestId
                       AND s.created_at >= b.freeze_at) AS cho_sau_freeze
              FROM bang b
              JOIN users u ON u.id = b.user_id
             ORDER BY b.total_score DESC, b.penalty_seconds ASC, b.last_scoring_at ASC NULLS LAST
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
            WITH bang AS (
                SELECT n.user_id,
                       COALESCE(f.total_score, 0) AS total_score,
                       COALESCE(f.penalty_seconds, 0) AS penalty_seconds,
                       COALESCE(f.solved_count, 0) AS solved_count,
                       f.last_scoring_at,
                       k.freeze_at
                  FROM (SELECT user_id FROM contest_standings WHERE contest_id = :contestId
                        UNION
                        SELECT user_id FROM contest_standings_frozen
                         WHERE contest_id = :contestId) n
                  CROSS JOIN (SELECT freeze_at FROM contests WHERE id = :contestId) k
                  LEFT JOIN contest_standings_frozen f
                         ON f.contest_id = :contestId AND f.user_id = n.user_id)
            SELECT b.user_id, u.handle, u.display_name, b.total_score, b.penalty_seconds,
                   b.solved_count, b.last_scoring_at,
                   (SELECT count(*) FROM submissions s
                     WHERE s.user_id = b.user_id AND s.contest_id = :contestId
                       AND s.created_at >= b.freeze_at) AS cho_sau_freeze
              FROM bang b
              JOIN users u ON u.id = b.user_id
             WHERE b.user_id = :userId
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
            WITH bang AS (
                SELECT n.user_id,
                       COALESCE(f.total_score, 0) AS total_score,
                       COALESCE(f.penalty_seconds, 0) AS penalty_seconds,
                       f.last_scoring_at
                  FROM (SELECT user_id FROM contest_standings WHERE contest_id = :contestId
                        UNION
                        SELECT user_id FROM contest_standings_frozen
                         WHERE contest_id = :contestId) n
                  LEFT JOIN contest_standings_frozen f
                         ON f.contest_id = :contestId AND f.user_id = n.user_id)
            SELECT t.hang FROM (
                SELECT user_id,
                       rank() OVER (ORDER BY total_score DESC, penalty_seconds ASC,
                                             last_scoring_at ASC NULLS LAST) AS hang
                  FROM bang) t
             WHERE t.user_id = :userId
            """;

    private static final String QUANH = """
            WITH xep AS (
                SELECT s.user_id, s.total_score, s.penalty_seconds, s.solved_count,
                       s.last_scoring_at,
                       rank() OVER (ORDER BY s.total_score DESC, s.penalty_seconds ASC,
                                             s.last_scoring_at ASC NULLS LAST) AS hang,
                       row_number() OVER (ORDER BY s.total_score DESC, s.penalty_seconds ASC,
                                                   s.last_scoring_at ASC NULLS LAST,
                                                   s.user_id) AS vi_tri
                  FROM contest_standings s
                 WHERE s.contest_id = :contestId),
                 toi AS (SELECT vi_tri FROM xep WHERE user_id = :userId)
            SELECT x.user_id, u.handle, u.display_name, x.total_score, x.penalty_seconds,
                   x.solved_count, x.last_scoring_at, 0 AS cho_sau_freeze, x.hang
              FROM xep x
              JOIN users u ON u.id = x.user_id
             CROSS JOIN toi
             WHERE x.vi_tri BETWEEN toi.vi_tri - :k AND toi.vi_tri + :k
             ORDER BY x.vi_tri
            """;

    private static final String QUANH_DONG_BANG = """
            WITH bang AS (
                SELECT n.user_id,
                       COALESCE(f.total_score, 0) AS total_score,
                       COALESCE(f.penalty_seconds, 0) AS penalty_seconds,
                       COALESCE(f.solved_count, 0) AS solved_count,
                       f.last_scoring_at,
                       k.freeze_at
                  FROM (SELECT user_id FROM contest_standings WHERE contest_id = :contestId
                        UNION
                        SELECT user_id FROM contest_standings_frozen
                         WHERE contest_id = :contestId) n
                  CROSS JOIN (SELECT freeze_at FROM contests WHERE id = :contestId) k
                  LEFT JOIN contest_standings_frozen f
                         ON f.contest_id = :contestId AND f.user_id = n.user_id),
                 xep AS (
                SELECT b.*,
                       rank() OVER (ORDER BY b.total_score DESC, b.penalty_seconds ASC,
                                             b.last_scoring_at ASC NULLS LAST) AS hang,
                       row_number() OVER (ORDER BY b.total_score DESC, b.penalty_seconds ASC,
                                                   b.last_scoring_at ASC NULLS LAST,
                                                   b.user_id) AS vi_tri
                  FROM bang b),
                 toi AS (SELECT vi_tri FROM xep WHERE user_id = :userId)
            SELECT x.user_id, u.handle, u.display_name, x.total_score, x.penalty_seconds,
                   x.solved_count, x.last_scoring_at,
                   (SELECT count(*) FROM submissions s
                     WHERE s.user_id = x.user_id AND s.contest_id = :contestId
                       AND s.created_at >= x.freeze_at) AS cho_sau_freeze,
                   x.hang
              FROM xep x
              JOIN users u ON u.id = x.user_id
             CROSS JOIN toi
             WHERE x.vi_tri BETWEEN toi.vi_tri - :k AND toi.vi_tri + :k
             ORDER BY x.vi_tri
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

    @Override
    public List<DongCoHang> quanhNguoi(long contestId, long userId, int k, boolean dongBang) {
        return jdbc.sql(dongBang ? QUANH_DONG_BANG : QUANH)
                .param("contestId", contestId).param("userId", userId).param("k", k)
                .query((rs, i) -> new DongCoHang(DONG.mapRow(rs, i), rs.getInt("hang")))
                .list();
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
