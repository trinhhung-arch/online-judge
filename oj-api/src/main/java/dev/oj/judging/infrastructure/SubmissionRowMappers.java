package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.JudgeSpec;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

final class SubmissionRowMappers {

    static final RowMapper<Submission> SUBMISSION = SubmissionRowMappers::mapSubmission;

    static final RowMapper<SubmissionRepository.SubmissionDetail> DETAIL = (rs, rowNum) ->
            new SubmissionRepository.SubmissionDetail(
                    mapSubmission(rs, rowNum),
                    FeedbackLevel.fromCode(rs.getString("feedback_level")),
                    JudgeSpec.timeLimitOnReferenceHost(
                            rs.getInt("time_limit_ms"),
                            rs.getBigDecimal("time_multiplier"),
                            rs.getInt("startup_overhead_ms")),
                    JudgeSpec.memoryLimitOnReferenceHost(
                            rs.getInt("memory_limit_kb"),
                            rs.getInt("memory_overhead_kb")),
                    rs.getString("compile_log"),
                    rs.getString("isolate_status"));

    static final RowMapper<SubmissionListItem> LIST_ITEM = (rs, rowNum) -> new SubmissionListItem(
            rs.getLong("id"),
            rs.getLong("problem_id"),
            rs.getString("problem_code"),
            rs.getString("problem_title"),
            rs.getInt("language_id"),
            SubmissionStatus.fromCode(rs.getString("status")),
            verdict(rs),
            integer(rs, "score"),
            integer(rs, "time_ms"),
            integer(rs, "memory_kb"),
            instant(rs, "created_at"));

    private SubmissionRowMappers() {
    }

    private static Submission mapSubmission(ResultSet rs, int rowNum) throws SQLException {
        Verdict verdict = verdict(rs);
        JudgeOutcome outcome = verdict == null ? null : new JudgeOutcome(
                verdict,
                rs.getInt("score"),
                rs.getInt("max_score"),
                integer(rs, "failed_test_ordinal"),
                integer(rs, "time_ms"),
                integer(rs, "memory_kb"));
        return new Submission(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("problem_id"),
                aLong(rs, "contest_id"),
                rs.getInt("language_id"),
                rs.getString("source_sha256"),
                rs.getInt("source_bytes"),
                instant(rs, "created_at"),
                SubmissionStatus.fromCode(rs.getString("status")),
                rs.getInt("attempt"),
                integer(rs, "testdata_version"),
                outcome,
                outcome == null ? null : instant(rs, "judged_at"),
                instant(rs, "hidden_at"),
                aLong(rs, "hidden_by"));
    }

    private static Verdict verdict(ResultSet rs) throws SQLException {
        String code = rs.getString("verdict");
        return code == null ? null : Verdict.fromCode(code);
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long aLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
