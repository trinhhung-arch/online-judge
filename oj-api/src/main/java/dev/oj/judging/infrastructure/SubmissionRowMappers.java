package dev.oj.judging.infrastructure;

import dev.oj.contract.Verdict;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Ánh xạ dòng {@code submissions} sang domain. Tách khỏi {@code JdbcSubmissionRepository} vì
 * file đó vượt trần 300 dòng ({@code CLAUDE.md} mục 7) — không phải vì hai việc này khác nhau
 * về bản chất.
 *
 * <h2>Vì sao ánh xạ tay chứ không {@code query(Submission.class)}</h2>
 * Bộ chuyển đổi tự động không biết ba điều mà domain coi là bất biến: {@code verdict} và
 * {@code judged_at} phải cùng có hoặc cùng không, {@code status} là {@code TEXT} chứ không
 * phải enum của Postgres, và {@code getInt} trả {@code 0} cho một cột {@code NULL}. Cái thứ ba
 * là loại lỗi tệ nhất: một bài chưa chấm xong sẽ hiện <b>"0ms / 0KB"</b> thay vì ô trống, và
 * không ai nhận ra là sai vì nó trông giống một con số thật.
 */
final class SubmissionRowMappers {

    static final RowMapper<Submission> SUBMISSION = SubmissionRowMappers::mapSubmission;

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
        // Một dòng DONE mà thiếu verdict là dữ liệu hỏng (ck_submissions_done chặn nó ở DB).
        // Dựng outcome = null trong trường hợp đó để constructor của Submission nổ ngay lúc
        // đọc lên, chứ không phải lúc render trang cho người dùng.
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

    /** {@code getInt} trả 0 cho NULL — ba hàm này giữ NULL đúng là NULL. */
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
