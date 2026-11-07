package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcSubmissionRepository implements SubmissionRepository {

    private static final String INSERT = """
            INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                     source_sha256, source_bytes, testdata_version)
            VALUES (:userId, :problemId, :contestId, :languageId,
                    :sha256, :byteSize, :testdataVersion)
            RETURNING id
            """;

    private static final String FIND_FOR_REQUESTER = """
            SELECT id, user_id, problem_id, contest_id, language_id,
                   source_sha256, source_bytes, created_at,
                   status, attempt, testdata_version,
                   verdict, score, max_score, failed_test_ordinal,
                   time_ms, memory_kb, judged_at, hidden_at, hidden_by
              FROM submissions
             WHERE id = :id
               AND (user_id = :requesterId OR :requesterRole = 'ADMIN')
               AND (hidden_at IS NULL      OR :requesterRole = 'ADMIN')
            """;

    private static final String FIND_DETAIL_FOR_REQUESTER = """
            SELECT s.id, s.user_id, s.problem_id, s.contest_id, s.language_id,
                   s.source_sha256, s.source_bytes, s.created_at,
                   s.status, s.attempt, s.testdata_version,
                   s.verdict, s.score, s.max_score, s.failed_test_ordinal,
                   s.time_ms, s.memory_kb, s.judged_at, s.hidden_at, s.hidden_by,
                   p.feedback_level, p.time_limit_ms, p.memory_limit_kb,
                   l.time_multiplier, l.startup_overhead_ms, l.memory_overhead_kb,
                   r.compile_log, r.isolate_status
              FROM submissions s
              JOIN problems  p ON p.id = s.problem_id
              JOIN languages l ON l.id = s.language_id
              LEFT JOIN judge_runs r
                     ON r.submission_id = s.id
                    AND r.attempt       = s.attempt
             WHERE s.id = :id
               AND (s.user_id  = :requesterId OR :requesterRole = 'ADMIN')
               AND (s.hidden_at IS NULL       OR :requesterRole = 'ADMIN')
            """;

    private static final String LIST_FOR_USER = """
            SELECT s.id, s.problem_id, p.code AS problem_code, p.title AS problem_title,
                   s.language_id, s.status, s.verdict, s.score,
                   s.time_ms, s.memory_kb, s.created_at
              FROM submissions s
              JOIN problems p ON p.id = s.problem_id
             WHERE s.user_id = :userId
               AND (:cursorId::BIGINT     IS NULL OR s.id          <  :cursorId)
               AND (:problemId::BIGINT    IS NULL OR s.problem_id  =  :problemId)
               AND (:verdict::TEXT        IS NULL OR s.verdict     =  :verdict)
               AND (:languageId::SMALLINT IS NULL OR s.language_id =  :languageId)
               AND s.hidden_at IS NULL
             ORDER BY s.id DESC
             LIMIT :pageSize
            """;

    private static final String LAST_SUBMITTED_AT = """
            SELECT created_at
              FROM submissions
             WHERE user_id = :userId
             ORDER BY id DESC
             LIMIT 1
            """;

    private static final String MARK_JUDGING = """
            UPDATE submissions
               SET status = 'JUDGING', attempt = :attempt
             WHERE id = :id
            """;

    private static final String MARK_DONE = """
            UPDATE submissions
               SET status              = 'DONE',
                   verdict             = :verdict,
                   score               = :score,
                   max_score           = :maxScore,
                   failed_test_ordinal = :failedTestOrdinal,
                   time_ms             = :timeMs,
                   memory_kb           = :memoryKb,
                   judged_at           = :judgedAt
             WHERE id      = :id
               AND attempt = :attempt
               AND status  = 'JUDGING'
            """;

    private static final String AN_BAI = """
            UPDATE submissions SET hidden_at = :luc, hidden_by = :adminId
             WHERE id = :id AND hidden_at IS NULL
            """;

    private static final String HIEN_BAI = """
            UPDATE submissions SET hidden_at = NULL, hidden_by = NULL
             WHERE id = :id AND hidden_at IS NOT NULL
            """;

    private static final String MARK_QUEUED = """
            UPDATE submissions
               SET status = 'QUEUED'
             WHERE id IN (:ids)
            """;

    static OffsetDateTime timestamptz(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private final JdbcClient appJdbc;
    private final JdbcClient judgeJdbc;

    public JdbcSubmissionRepository(@Qualifier("appJdbcClient") JdbcClient appJdbc,
                                    @Qualifier("judgeJdbcClient") JdbcClient judgeJdbc) {
        this.appJdbc = appJdbc;
        this.judgeJdbc = judgeJdbc;
    }

    @Override
    public long insert(NewSubmission s) {
        return appJdbc.sql(INSERT)
                .param("userId", s.userId())
                .param("problemId", s.problemId())
                .param("contestId", s.contestId())
                .param("languageId", s.languageId())
                .param("sha256", s.sourceSha256())
                .param("byteSize", s.sourceBytes())
                .param("testdataVersion", s.testdataVersion())
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Submission> findForRequester(long id, long requesterId, Role role) {
        return appJdbc.sql(FIND_FOR_REQUESTER)
                .param("id", id)
                .param("requesterId", requesterId)
                .param("requesterRole", role.name())
                .query(SubmissionRowMappers.SUBMISSION)
                .optional();
    }

    @Override
    public Optional<SubmissionDetail> findDetailForRequester(long id, long requesterId, Role role) {
        return appJdbc.sql(FIND_DETAIL_FOR_REQUESTER)
                .param("id", id)
                .param("requesterId", requesterId)
                .param("requesterRole", role.name())
                .query(SubmissionRowMappers.DETAIL)
                .optional();
    }

    @Override
    public CursorPage<SubmissionListItem> listForUser(long userId, SubmissionFilter filter,
                                                      Long cursor, int size) {
        List<SubmissionListItem> rows = appJdbc.sql(LIST_FOR_USER)
                .param("userId", userId)
                .param("cursorId", cursor)
                .param("problemId", filter.problemId())
                .param("verdict", filter.verdict() == null ? null : filter.verdict().name())
                .param("languageId", filter.languageId())
                .param("pageSize", size + 1)
                .query(SubmissionRowMappers.LIST_ITEM)
                .list();
        return CursorPage.of(rows, size, item -> String.valueOf(item.id()));
    }

    @Override
    public Optional<Instant> lastSubmittedAt(long userId) {
        return appJdbc.sql(LAST_SUBMITTED_AT)
                .param("userId", userId)
                .query(Instant.class)
                .optional();
    }

    @Override
    public boolean markJudging(long submissionId, int attempt) {
        return judgeJdbc.sql(MARK_JUDGING)
                .param("id", submissionId)
                .param("attempt", attempt)
                .update() == 1;
    }

    @Override
    public boolean markDone(long submissionId, int attempt, JudgeOutcome outcome, Instant judgedAt) {
        return judgeJdbc.sql(MARK_DONE)
                .param("id", submissionId)
                .param("attempt", attempt)
                .param("verdict", outcome.verdict().name())
                .param("score", outcome.score())
                .param("maxScore", outcome.maxScore())
                .param("failedTestOrdinal", outcome.failedTestOrdinal())
                .param("timeMs", outcome.timeMs())
                .param("memoryKb", outcome.memoryKb())
                .param("judgedAt", timestamptz(judgedAt))
                .update() == 1;
    }

    @Override
    public int markQueued(Collection<Long> submissionIds) {
        if (submissionIds.isEmpty()) {
            return 0;
        }
        return judgeJdbc.sql(MARK_QUEUED)
                .param("ids", submissionIds)
                .update();
    }

    @Override
    public boolean datAn(long submissionId, boolean an, long adminId, Instant luc) {
        String sql = an ? AN_BAI : HIEN_BAI;
        return appJdbc.sql(sql)
                .param("id", submissionId)
                .param("adminId", adminId)
                .param("luc", timestamptz(luc))
                .update() > 0;
    }
}
