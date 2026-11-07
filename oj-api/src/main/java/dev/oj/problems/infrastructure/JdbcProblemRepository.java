package dev.oj.problems.infrastructure;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.platform.web.CursorPage;
import dev.oj.problems.application.port.ProblemRepository;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcProblemRepository implements ProblemRepository {

    private static final String FIND_BY_CODE = """
            SELECT id, code, title, statement_md,
                   time_limit_ms, memory_limit_kb, output_limit_kb,
                   checker_type, checker_epsilon, scoring_mode,
                   feedback_level, status, current_testdata_version,
                   owner_id, allow_public_solutions
              FROM problems
             WHERE lower(code) = lower(:code)
               AND status IN ('PUBLISHED', 'RETIRED')
            """;

    private static final String FIND_BY_ID = """
            SELECT id, code, title, statement_md,
                   time_limit_ms, memory_limit_kb, output_limit_kb,
                   checker_type, checker_epsilon, scoring_mode,
                   feedback_level, status, current_testdata_version,
                   owner_id, allow_public_solutions
              FROM problems
             WHERE id = :id
               AND status IN ('PUBLISHED', 'RETIRED')
            """;

    private final JdbcClient jdbc;

    public JdbcProblemRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Problem> findPublishedByCode(String code) {
        return jdbc.sql(FIND_BY_CODE)
                .param("code", code)
                .query(PROBLEM_MAPPER)
                .optional();
    }

    @Override
    public Optional<Problem> findPublishedById(long id) {
        return jdbc.sql(FIND_BY_ID)
                .param("id", id)
                .query(PROBLEM_MAPPER)
                .optional();
    }

    static final RowMapper<Problem> PROBLEM_MAPPER = JdbcProblemRepository::mapProblem;

    private static Problem mapProblem(ResultSet rs, int rowNum) throws SQLException {
        return new Problem(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("statement_md"),
                rs.getInt("time_limit_ms"),
                rs.getInt("memory_limit_kb"),
                rs.getInt("output_limit_kb"),
                CheckerType.fromCode(rs.getString("checker_type")),
                rs.getBigDecimal("checker_epsilon"),
                ScoringMode.valueOf(rs.getString("scoring_mode")),
                FeedbackLevel.fromCode(rs.getString("feedback_level")),
                ProblemStatus.fromCode(rs.getString("status")),
                rs.getInt("current_testdata_version"),
                rs.getLong("owner_id"),
                rs.getBoolean("allow_public_solutions"));
    }

    private static final String DANH_SACH = """
            SELECT p.id, p.code, p.title, p.time_limit_ms, p.memory_limit_kb,
                   EXISTS (SELECT 1 FROM submissions s
                            WHERE s.problem_id = p.id
                              AND s.user_id = :requesterId
                              AND s.verdict = 'AC') AS da_giai
              FROM problems p
             WHERE p.status = 'PUBLISHED'
               AND (p.id NOT IN (:idBiKhoa) OR p.owner_id = :requesterId)
               AND (CAST(:cursor AS bigint) IS NULL OR p.id < CAST(:cursor AS bigint))
               AND (CAST(:tagSlug AS text) IS NULL OR EXISTS (
                        SELECT 1 FROM problem_tags pt
                          JOIN tags t ON t.id = pt.tag_id
                         WHERE pt.problem_id = p.id AND t.slug = CAST(:tagSlug AS text)))
               AND (CAST(:daGiai AS boolean) IS NULL OR CAST(:daGiai AS boolean) = EXISTS (
                        SELECT 1 FROM submissions s2
                         WHERE s2.problem_id = p.id
                           AND s2.user_id = :requesterId
                           AND s2.verdict = 'AC'))
             ORDER BY p.id DESC
             LIMIT :limit
            """;

    @Override
    public CursorPage<ProblemListItem> danhSachDaXuatBan(ListFilter loc, Long cursor, int size) {
        List<ProblemListItem> duMotDong = jdbc.sql(DANH_SACH)
                .param("cursor", cursor)
                .param("tagSlug", loc.tagSlug())
                .param("requesterId", loc.requesterId() == null ? 0L : loc.requesterId())
                .param("daGiai", loc.daGiaiBoi())
                .param("idBiKhoa", loc.idBiKhoa())
                .param("limit", size + 1)
                .query((rs, i) -> new ProblemListItem(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getInt("time_limit_ms"),
                        rs.getInt("memory_limit_kb"),
                        rs.getBoolean("da_giai")))
                .list();

        boolean conTrangSau = duMotDong.size() > size;
        List<ProblemListItem> items = conTrangSau ? duMotDong.subList(0, size) : duMotDong;
        String next = conTrangSau ? String.valueOf(items.get(items.size() - 1).id()) : null;
        return new CursorPage<>(items, next);
    }
}
