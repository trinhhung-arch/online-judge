package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeRunRepository;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.JudgeRun;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Truy vấn 3b. Pool {@code judge} — luôn chạy trong transaction ghi verdict.
 *
 * <p>Chỉ có INSERT. Không sửa, không xoá, và đó không phải quy ước mà là quyền:
 * {@code REVOKE UPDATE, DELETE ON judge_runs FROM oj_app} (V9).
 */
@Repository
public class JdbcJudgeRunRepository implements JudgeRunRepository {

    /**
     * <b>{@code host_id} được phân giải bằng sub-select ngay trong câu INSERT.</b>
     *
     * <p>Worker gửi <i>tên</i> máy và không biết {@code judge_hosts.id} tồn tại (bất biến #3),
     * nên việc tra id là của API. Hai cách làm sai và lý do:
     * <ul>
     *   <li><b>SELECT trước rồi INSERT sau</b> — thêm một lượt round-trip vào transaction ngắn
     *       nhất và nóng nhất của hệ thống.</li>
     *   <li><b>{@code UPDATE judge_hosts SET last_seen_at = now()} rồi lấy id</b> — sáu slot
     *       của cùng một máy sẽ tranh khoá trên đúng một dòng, và mọi verdict từ máy đó bị
     *       tuần tự hoá. Cập nhật {@code last_seen_at} là việc của một heartbeat riêng ở M6,
     *       ngoài đường nóng.</li>
     * </ul>
     *
     * <p>Máy chưa đăng ký thì sub-select trả {@code NULL} — cột cho phép NULL, nên một worker
     * mới vẫn chấm được ngay mà không cần ai sửa config phía API (S2).
     */
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO judge_runs (submission_id, attempt, host_id, host_factor, language_id,
                                    testdata_version, verdict, score, max_score,
                                    failed_test_ordinal, tests_run, time_ms, memory_kb,
                                    compile_log, isolate_status, trace_id, started_at, finished_at)
            VALUES (:submissionId, :attempt,
                    (SELECT id FROM judge_hosts WHERE name = :hostName),
                    :hostFactor, :languageId, :testdataVersion, :verdict, :score, :maxScore,
                    :failedTestOrdinal, :testsRun, :timeMs, :memoryKb,
                    :compileLog, :isolateStatus, :traceId, :startedAt, :finishedAt)
            ON CONFLICT (submission_id, attempt) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public JdbcJudgeRunRepository(@Qualifier("judgeJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(JudgeRun run) {
        JudgeOutcome outcome = run.outcome();
        int rows = jdbc.sql(INSERT_IF_ABSENT)
                .param("submissionId", run.submissionId())
                .param("attempt", run.attempt())
                .param("hostName", run.hostName())
                .param("hostFactor", run.hostFactor())
                .param("languageId", run.languageId())
                .param("testdataVersion", run.testdataVersion())
                .param("verdict", outcome.verdict().name())
                .param("score", outcome.score())
                .param("maxScore", outcome.maxScore())
                .param("failedTestOrdinal", outcome.failedTestOrdinal())
                .param("testsRun", run.testsRun())
                .param("timeMs", outcome.timeMs())
                .param("memoryKb", outcome.memoryKb())
                .param("compileLog", run.compileLog())
                .param("isolateStatus", run.isolateStatus())
                .param("traceId", run.traceId())
                // Instant -> OffsetDateTime: driver Postgres không bind được Instant.
                // Xem JdbcSubmissionRepository.timestamptz.
                .param("startedAt", JdbcSubmissionRepository.timestamptz(run.startedAt()))
                .param("finishedAt", JdbcSubmissionRepository.timestamptz(run.finishedAt()))
                .update();
        return rows == 1;
    }

    /**
     * {@code verdict = NULL} trong hợp đồng nghĩa là nhóm <b>bị bỏ qua</b>; V4 lưu nó thành
     * chuỗi {@code 'SKIPPED'} vì cột {@code NOT NULL}.
     *
     * <p>Hai trạng thái này phải giữ khác nhau tới tận đây: "bỏ qua" nói với thí sinh rằng
     * nhóm ấy chưa được thử vì phụ thuộc chưa đạt — sửa nhóm 1 thì nhóm 3 có thể ăn điểm.
     * Gộp cả hai thành 0 điểm là xoá mất chính thông tin hữu ích nhất của FR-PROB-06.
     */
    private static final String INSERT_SUBTASK = """
            INSERT INTO judge_run_subtasks (submission_id, attempt, subtask_ordinal,
                                            verdict, score, max_score,
                                            failed_test_ordinal, time_ms, memory_kb)
            VALUES (:submissionId, :attempt, :subtaskOrdinal,
                    :verdict, :score, :maxScore,
                    :failedTestOrdinal, :timeMs, :memoryKb)
            ON CONFLICT (submission_id, attempt, subtask_ordinal) DO NOTHING
            """;

    @Override
    public void insertSubtaskResults(long submissionId, int attempt,
                                     java.util.List<dev.oj.contract.SubtaskResultDto> subtasks) {
        for (dev.oj.contract.SubtaskResultDto subtask : subtasks) {
            jdbc.sql(INSERT_SUBTASK)
                    .param("submissionId", submissionId)
                    .param("attempt", attempt)
                    .param("subtaskOrdinal", subtask.subtaskOrdinal())
                    .param("verdict", subtask.isSkipped() ? "SKIPPED" : subtask.verdict().name())
                    .param("score", subtask.score())
                    .param("maxScore", subtask.maxScore())
                    .param("failedTestOrdinal", subtask.failedTestOrdinal())
                    .param("timeMs", subtask.timeMs())
                    .param("memoryKb", subtask.memoryKb())
                    .update();
        }
    }
}
