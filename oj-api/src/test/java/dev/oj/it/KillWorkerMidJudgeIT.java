package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase.Outcome;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.trace.TraceIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>R1 — 0 bài mất.</b> Worker chết giữa lúc chấm thì bài phải tự quay lại hàng đợi và
 * được chấm lại, còn kết quả về muộn của worker đã chết phải bị loại.
 *
 * <p>Đây là kịch bản mà reaper sinh ra để cứu, và là một trong năm loại sự cố nó xử lý bằng
 * cùng một cơ chế ({@code nfrplan.md} 5.1).
 */
class KillWorkerMidJudgeIT extends PostgresIT {

    private static final ClaimRequestDto REQUEST =
            ClaimRequestDto.single("mac-m1max-host", "arm64");

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;

    @Test
    @DisplayName("★ worker chết → reaper thu hồi → chấm lại với attempt=2, kết quả cũ bị loại")
    void worker_chet_giua_chung_thi_bai_duoc_cham_lai() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();

        // ---- worker A nhận việc rồi "chết": không bao giờ trả kết quả ----
        JudgeJobDto first = claimJudgeJob.claim(REQUEST).orElseThrow();
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("JUDGING");

        // ---- 120 giây trôi qua ----
        expireLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        // Bài về hàng đợi, và attempt KHÔNG tăng ở bước này — lần claim kế tiếp mới tăng.
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(queueAttempt(id)).isEqualTo(1);

        // ---- worker B nhận lại, attempt = 2 ----
        JudgeJobDto second = claimJudgeJob.claim(REQUEST).orElseThrow();
        assertThat(second.submissionId()).isEqualTo(id);
        assertThat(second.attempt()).isEqualTo(2);

        // ---- worker A hồi sinh và trả kết quả CŨ (attempt=1) -> phải bị loại im lặng ----
        assertThat(recordJudgeResult.record(result(id, 1, Verdict.WA))).isEqualTo(Outcome.IGNORED);
        assertThat(status(id)).isEqualTo("JUDGING");     // chưa có verdict nào được ghi
        assertThat(countJudgeRuns(id)).isZero();

        // ---- worker B trả kết quả đúng ----
        assertThat(recordJudgeResult.record(result(id, 2, Verdict.AC))).isEqualTo(Outcome.RECORDED);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(verdict(id)).isEqualTo("AC");
        assertThat(attempt(id)).isEqualTo(2);
        assertThat(queueDepth()).isZero();
    }

    /**
     * DoD M1: {@code traceId} xuyên API → hàng đợi → worker → kết quả. Ở đây kiểm chặng cuối —
     * mã truy vết của request ghi verdict phải nằm lại trong {@code judge_runs}.
     */
    @Test
    void traceId_duoc_ghi_lai_cung_ban_ghi_cham() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        claimJudgeJob.claim(REQUEST).orElseThrow();

        TraceIdFilter.set("trace-cua-toi-123");
        try {
            recordJudgeResult.record(result(id, 1, Verdict.AC));
        } finally {
            TraceIdFilter.clear();
        }

        String traceId = jdbc.sql("SELECT trace_id FROM judge_runs WHERE submission_id = :id")
                .param("id", id).query(String.class).single();
        assertThat(traceId).isEqualTo("trace-cua-toi-123");
    }

    private void expireLease(long id) {
        jdbc.sql("""
                UPDATE judge_queue SET lease_until = now() - interval '1 second'
                 WHERE submission_id = :id
                """).param("id", id).update();
    }

    private static JudgeResultDto result(long id, int attempt, Verdict verdict) {
        return new JudgeResultDto(id, attempt, verdict,
                verdict.isAccepted() ? 100 : 0, 100, verdict.isAccepted() ? null : 2,
                3, 23, 8192, null, null,
                "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM submissions WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private String verdict(long id) {
        return jdbc.sql("SELECT verdict FROM submissions WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private int attempt(long id) {
        return jdbc.sql("SELECT attempt FROM submissions WHERE id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private int queueAttempt(long id) {
        return jdbc.sql("SELECT attempt FROM judge_queue WHERE submission_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private int countJudgeRuns(long id) {
        return jdbc.sql("SELECT count(*)::int FROM judge_runs WHERE submission_id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private int queueDepth() {
        return jdbc.sql("SELECT count(*)::int FROM judge_queue").query(Integer.class).single();
    }
}
