package dev.oj.judging.infrastructure;

import dev.oj.judging.application.published.JudgingQueries;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class JdbcJudgingQueries implements JudgingQueries {

    private static final String TRONG_CONTEST = """
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score,
                   COALESCE(max_score, 0) AS max_score, created_at
              FROM submissions
             WHERE contest_id = :contestId
               AND status = 'DONE'
               AND id > :sau
             ORDER BY id
             LIMIT :gioiHan
            """;

    private static final String TRONG_CONTEST_LIEN_MACH = """
            WITH lo AS (
                SELECT id, user_id, problem_id, verdict, score, max_score, created_at, status
                  FROM submissions
                 WHERE contest_id = :contestId
                   AND id > :sau
                 ORDER BY id
                 LIMIT :gioiHan
            )
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score,
                   COALESCE(max_score, 0) AS max_score, created_at
              FROM lo
             WHERE status = 'DONE'
               AND id < ALL (SELECT id FROM lo WHERE status <> 'DONE')
             ORDER BY id
            """;

    private static final String CUA_DE = """
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score,
                   COALESCE(max_score, 0) AS max_score, created_at
              FROM submissions
             WHERE problem_id = :problemId
               AND contest_id = :contestId
               AND status = 'DONE'
               AND id > :sau
             ORDER BY id
             LIMIT :gioiHan
            """;

    private static final String CON_BAI_CHO_CHAM = """
            SELECT EXISTS (SELECT 1
                             FROM judge_queue q
                             JOIN submissions s ON s.id = q.submission_id
                            WHERE s.contest_id = :contestId)
            """;

    private static final RowMapper<ScoredSubmission> MAPPER = (rs, i) -> new ScoredSubmission(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("problem_id"),
            rs.getString("verdict"),
            rs.getInt("score"),
            rs.getInt("max_score"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public JdbcJudgingQueries(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ScoredSubmission> baiDaChamTrongContest(long contestId, long sauSubmissionId,
                                                       int gioiHan) {
        return jdbc.sql(TRONG_CONTEST)
                .param("contestId", contestId)
                .param("sau", sauSubmissionId)
                .param("gioiHan", gioiHan)
                .query(MAPPER)
                .list();
    }

    @Override
    public List<ScoredSubmission> baiDaChamLienMach(long contestId, long sauSubmissionId,
                                                    int gioiHan) {
        return jdbc.sql(TRONG_CONTEST_LIEN_MACH)
                .param("contestId", contestId)
                .param("sau", sauSubmissionId)
                .param("gioiHan", gioiHan)
                .query(MAPPER)
                .list();
    }

    @Override
    public boolean conBaiChoChamTrongContest(long contestId) {
        return Boolean.TRUE.equals(jdbc.sql(CON_BAI_CHO_CHAM)
                .param("contestId", contestId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<ScoredSubmission> baiDaChamCuaDe(long contestId, long problemId,
                                                 long sauSubmissionId, int gioiHan) {
        return jdbc.sql(CUA_DE)
                .param("problemId", problemId)
                .param("contestId", contestId)
                .param("sau", sauSubmissionId)
                .param("gioiHan", gioiHan)
                .query(MAPPER)
                .list();
    }
}
