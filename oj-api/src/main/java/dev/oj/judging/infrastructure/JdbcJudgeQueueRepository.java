package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeQueueRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcJudgeQueueRepository implements JudgeQueueRepository {

    private static final String ENQUEUE = """
            INSERT INTO judge_queue (submission_id, priority, attempt)
            VALUES (:submissionId, :priority, 0)
            ON CONFLICT (submission_id) DO NOTHING
            """;

    private static final String CLAIM = """
            WITH picked AS (
                SELECT submission_id
                  FROM judge_queue
                 WHERE claimed_at IS NULL
                 ORDER BY priority, enqueued_at, submission_id
                 LIMIT 1
                   FOR UPDATE SKIP LOCKED
            )
            UPDATE judge_queue q
               SET claimed_at      = now(),
                   lease_until     = now() + make_interval(secs => :leaseSeconds),
                   claimed_by_host = (SELECT id FROM judge_hosts WHERE name = :hostName),
                   attempt         = q.attempt + 1
              FROM picked p
             WHERE q.submission_id = p.submission_id
            RETURNING q.submission_id, q.attempt
            """;

    private static final String CLAIM_PAYLOAD = """
            SELECT s.id AS submission_id, s.problem_id, s.testdata_version,
                   s.source_sha256, b.content AS source_content,
                   l.code AS language_code, l.source_extension, l.compile_command, l.run_command,
                   l.compile_time_limit_ms, l.compile_memory_kb,
                   l.time_multiplier, l.startup_overhead_ms, l.memory_overhead_kb
              FROM submissions  s
              JOIN source_blobs b ON b.sha256 = s.source_sha256
              JOIN languages    l ON l.id     = s.language_id
             WHERE s.id = :submissionId
            """;

    private static final String RELEASE_WITH_OPTIMISTIC_LOCK = """
            DELETE FROM judge_queue q
             USING submissions s
             WHERE q.submission_id = :submissionId
               AND q.attempt       = :attempt
               AND q.claimed_at    IS NOT NULL
               AND s.id            = q.submission_id
            RETURNING q.submission_id, q.attempt, s.language_id, s.testdata_version
            """;

    private static final String RETRY_IE = """
            UPDATE judge_queue
               SET claimed_at      = NULL,
                   lease_until     = NULL,
                   claimed_by_host = NULL,
                   ie_retry_count  = ie_retry_count + 1,
                   enqueued_at     = now()
             WHERE submission_id  = :submissionId
               AND attempt        = :attempt
               AND claimed_at     IS NOT NULL
               AND ie_retry_count < :maxIeRetries
            RETURNING submission_id
            """;

    private static final String REAP_EXPIRED = """
            UPDATE judge_queue
               SET claimed_at      = NULL,
                   lease_until     = NULL,
                   claimed_by_host = NULL,
                   reap_count      = COALESCE(reap_count, 0) + 1
             WHERE claimed_at IS NOT NULL
               AND lease_until  < now()
               AND COALESCE(reap_count, 0) < :maxReaps
            RETURNING submission_id
            """;

    private static final String EXHAUSTED = """
            SELECT submission_id, attempt, claimed_at
              FROM judge_queue
             WHERE claimed_at IS NOT NULL
               AND lease_until  < now()
               AND COALESCE(reap_count, 0) >= :maxReaps
             ORDER BY submission_id
            """;

    private static final String QUEUE_DEPTH = """
            SELECT count(*) FILTER (WHERE claimed_at IS NULL)     AS waiting,
                   count(*) FILTER (WHERE claimed_at IS NOT NULL) AS claimed,
                   min(enqueued_at) FILTER (WHERE claimed_at IS NULL) AS oldest_enqueued_at
              FROM judge_queue
            """;

    private final JdbcClient appJdbc;
    private final JdbcClient judgeJdbc;

    public JdbcJudgeQueueRepository(@Qualifier("appJdbcClient") JdbcClient appJdbc,
                                    @Qualifier("judgeJdbcClient") JdbcClient judgeJdbc) {
        this.appJdbc = appJdbc;
        this.judgeJdbc = judgeJdbc;
    }

    @Override
    public void enqueue(long submissionId, int priority) {
        appJdbc.sql(ENQUEUE)
                .param("submissionId", submissionId)
                .param("priority", priority)
                .update();
    }

    @Override
    public Optional<ClaimedJob> claim(String hostName, int leaseSeconds) {
        Optional<long[]> picked = judgeJdbc.sql(CLAIM)
                .param("hostName", hostName)
                .param("leaseSeconds", leaseSeconds)
                .query((rs, n) -> new long[]{rs.getLong("submission_id"), rs.getInt("attempt")})
                .optional();
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        long submissionId = picked.get()[0];
        int attempt = (int) picked.get()[1];
        return judgeJdbc.sql(CLAIM_PAYLOAD)
                .param("submissionId", submissionId)
                .query(payloadMapper(attempt))
                .optional();
    }

    @Override
    public Optional<ReleasedSubmission> releaseWithOptimisticLock(long submissionId, int attempt) {
        return judgeJdbc.sql(RELEASE_WITH_OPTIMISTIC_LOCK)
                .param("submissionId", submissionId)
                .param("attempt", attempt)
                .query((rs, n) -> new ReleasedSubmission(
                        rs.getLong("submission_id"),
                        rs.getInt("attempt"),
                        rs.getInt("language_id"),
                        rs.getInt("testdata_version")))
                .optional();
    }

    @Override
    public List<Long> reapExpired(int maxReaps) {
        return judgeJdbc.sql(REAP_EXPIRED)
                .param("maxReaps", maxReaps)
                .query(Long.class)
                .list();
    }

    @Override
    public List<ExhaustedJob> exhaustedJobs(int maxReaps) {
        return judgeJdbc.sql(EXHAUSTED).param("maxReaps", maxReaps)
                .query((rs, n) -> new ExhaustedJob(rs.getLong("submission_id"),
                        rs.getInt("attempt"), rs.getTimestamp("claimed_at").toInstant()))
                .list();
    }

    @Override
    public boolean retryIe(long submissionId, int attempt, int maxRetries) {
        return judgeJdbc.sql(RETRY_IE)
                .param("submissionId", submissionId)
                .param("attempt", attempt)
                .param("maxIeRetries", maxRetries)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    @Override
    public QueueStats queueDepth() {
        return judgeJdbc.sql(QUEUE_DEPTH)
                .query((rs, n) -> {
                    var oldest = rs.getTimestamp("oldest_enqueued_at");
                    return new QueueStats(rs.getInt("waiting"), rs.getInt("claimed"),
                            oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    private static RowMapper<ClaimedJob> payloadMapper(int attempt) {
        return (rs, n) -> new ClaimedJob(
                rs.getLong("submission_id"),
                attempt,
                rs.getLong("problem_id"),
                rs.getInt("testdata_version"),
                rs.getString("source_sha256"),
                rs.getString("source_content"),
                new ClaimedJob.LanguageSpec(
                        rs.getString("language_code"),
                        rs.getString("source_extension"),
                        rs.getString("compile_command"),
                        rs.getString("run_command"),
                        rs.getInt("compile_time_limit_ms"),
                        rs.getInt("compile_memory_kb"),
                        rs.getBigDecimal("time_multiplier"),
                        rs.getInt("startup_overhead_ms"),
                        rs.getInt("memory_overhead_kb")));
    }
}
