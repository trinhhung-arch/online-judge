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
