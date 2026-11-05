package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeRunRepository;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.JudgeRun;
import dev.oj.platform.metrics.OjMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJudgeRunRepository implements JudgeRunRepository {

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

    private final MeterRegistry metrics;

    public JdbcJudgeRunRepository(@Qualifier("judgeJdbcClient") JdbcClient jdbc,
                                  MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    private void dem(JudgeOutcome outcome) {
        metrics.counter(OjMetrics.JUDGE_FINISHED,
                OjMetrics.TAG_VERDICT, outcome.verdict().name()).increment();
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
                .param("startedAt", JdbcSubmissionRepository.timestamptz(run.startedAt()))
                .param("finishedAt", JdbcSubmissionRepository.timestamptz(run.finishedAt()))
                .update();
        if (rows == 1) {
            dem(outcome);
        }
        return rows == 1;
    }

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
