package dev.oj.judging.infrastructure;

import dev.oj.judging.application.published.JudgingQueries;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hiện thực {@link JudgingQueries}. Pool {@code app} — bảng xếp hạng là việc của phía đọc,
 * không phải đường verdict.
 *
 * <h2>Cả hai câu đều chỉ đọc, và không câu nào không có LIMIT</h2>
 * Bất biến #8. {@code submissions} sẽ có hàng triệu dòng, và một kỳ thi lớn có hàng chục
 * nghìn bài — đủ để một câu không giới hạn làm cạn bộ nhớ của tiến trình API.
 */
@Repository
public class JdbcJudgingQueries implements JudgingQueries {

    private static final String TRONG_CONTEST = """
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score, created_at
              FROM submissions
             WHERE contest_id = :contestId
               AND status = 'DONE'
               AND id > :sau
             ORDER BY id
             LIMIT :gioiHan
            """;

    /**
     * Truy vấn 11 của {@code docs/sql/duong_nong.sql}, viết trọn vẹn.
     *
     * <p>Điều kiện {@code problem_id} đứng trước để {@code ix_submissions_problem_recent}
     * được dùng; {@code contest_id} là bộ lọc thêm trên các dòng đã cắt. Viết ngược lại thì
     * Postgres phải quét theo {@code contest_id} — cột <b>cố ý không có index</b>.
     */
    private static final String CUA_DE = """
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score, created_at
              FROM submissions
             WHERE problem_id = :problemId
               AND contest_id = :contestId
               AND status = 'DONE'
               AND id > :sau
             ORDER BY id
             LIMIT :gioiHan
            """;

    private static final RowMapper<ScoredSubmission> MAPPER = (rs, i) -> new ScoredSubmission(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("problem_id"),
            rs.getString("verdict"),
            rs.getInt("score"),
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
