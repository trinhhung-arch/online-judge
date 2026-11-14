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
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.trace.TraceIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KillWorkerMidJudgeIT extends PostgresIT {

    private static final ClaimRequestDto REQUEST =
            ClaimRequestDto.single("mac-m1max-host", "arm64");

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;
    @Autowired AppProperties properties;

    @Test
    @DisplayName("★ worker chết → reaper thu hồi → chấm lại với attempt=2, kết quả cũ bị loại")
    void worker_chet_giua_chung_thi_bai_duoc_cham_lai() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();

        JudgeJobDto first = claimJudgeJob.claim(REQUEST).orElseThrow();
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("JUDGING");

        expireLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(queueAttempt(id)).isEqualTo(1);

        JudgeJobDto second = claimJudgeJob.claim(REQUEST).orElseThrow();
        assertThat(second.submissionId()).isEqualTo(id);
        assertThat(second.attempt()).isEqualTo(2);

        assertThat(recordJudgeResult.record(result(id, 1, Verdict.WA))).isEqualTo(Outcome.IGNORED);
        assertThat(status(id)).isEqualTo("JUDGING");
        assertThat(countJudgeRuns(id)).isZero();

        assertThat(recordJudgeResult.record(result(id, 2, Verdict.AC))).isEqualTo(Outcome.RECORDED);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(verdict(id)).isEqualTo("AC");
        assertThat(attempt(id)).isEqualTo(2);
        assertThat(queueDepth()).isZero();
    }

    @Test
    @DisplayName("★ bài độc: hết lượt thả lại → IE, hàng đợi trống, một bản ghi chấm, kết quả muộn bị loại")
    void bai_doc_het_luot_tha_lai_thi_ghi_IE() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        int maxReaps = properties.judge().maxReaps();

        for (int lan = 1; lan <= maxReaps; lan++) {
            assertThat(claimJudgeJob.claim(REQUEST).orElseThrow().attempt()).isEqualTo(lan);
            expireLease(id);
            assertThat(reapStaleJobs.reap()).isEqualTo(1);
            assertThat(status(id)).as("lần thả lại thứ %d", lan).isEqualTo("QUEUED");
        }

        int cuoi = claimJudgeJob.claim(REQUEST).orElseThrow().attempt();
        assertThat(cuoi).isEqualTo(maxReaps + 1);
        expireLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        assertThat(status(id)).as("bỏ cuộc, không thả lại nữa").isEqualTo("DONE");
        assertThat(verdict(id)).isEqualTo("IE");
        assertThat(queueDepth()).isZero();
        assertThat(countJudgeRuns(id)).isEqualTo(1);
        assertThat(claimJudgeJob.claim(REQUEST)).as("không còn gì để quay vòng").isEmpty();

        assertThat(recordJudgeResult.record(result(id, cuoi, Verdict.AC))).isEqualTo(Outcome.IGNORED);
        assertThat(verdict(id)).isEqualTo("IE");
        assertThat(countJudgeRuns(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("bài có attempt cao (như sau rejudge) vẫn được thả lại ở lần hết lease đầu tiên")
    void attempt_cao_van_duoc_tha_lai() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        jdbc.sql("UPDATE judge_queue SET attempt = 7 WHERE submission_id = :id")
                .param("id", id).update();

        assertThat(claimJudgeJob.claim(REQUEST).orElseThrow().attempt()).isEqualTo(8);
        expireLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(queueDepth()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ KQ-04 · kết quả tới SAU khi reaper thả bài, TRƯỚC khi có người nhận lại → bị bỏ qua, bài không rời hàng đợi")
    void ket_qua_toi_giua_luc_tha_va_luc_nhan_lai() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        assertThat(claimJudgeJob.claim(REQUEST).orElseThrow().attempt()).isEqualTo(1);
        expireLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        assertThat(recordJudgeResult.record(result(id, 1, Verdict.AC))).isEqualTo(Outcome.IGNORED);
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(queueDepth()).as("bài vẫn chờ người nhận lại").isEqualTo(1);
        assertThat(countJudgeRuns(id)).isZero();

        JudgeJobDto lai = claimJudgeJob.claim(REQUEST).orElseThrow();
        assertThat(lai.submissionId()).isEqualTo(id);
        assertThat(lai.attempt()).isEqualTo(2);
        assertThat(recordJudgeResult.record(result(id, 2, Verdict.AC))).isEqualTo(Outcome.RECORDED);
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(verdict(id)).isEqualTo("AC");
        assertThat(queueDepth()).isZero();
    }

    @Test
    @DisplayName("KQ-14 · IE tới sau khi bài đã bị thả về → bị bỏ qua, không tiêu lượt tự chấm lại")
    void ie_toi_sau_khi_tha_khong_tieu_luot() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        claimJudgeJob.claim(REQUEST).orElseThrow();
        expireLease(id);
        assertThat(reapStaleJobs.reap()).isEqualTo(1);

        assertThat(recordJudgeResult.record(result(id, 1, Verdict.IE))).isEqualTo(Outcome.IGNORED);
        assertThat(ieRetryCount(id)).isZero();
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(queueDepth()).isEqualTo(1);
    }

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

    private int ieRetryCount(long id) {
        return jdbc.sql("SELECT ie_retry_count FROM judge_queue WHERE submission_id = :id")
                .param("id", id).query(Integer.class).single();
    }
}
