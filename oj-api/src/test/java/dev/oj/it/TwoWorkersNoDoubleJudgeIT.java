package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TwoWorkersNoDoubleJudgeIT extends PostgresIT {

    private static final int SUBMISSIONS = 20;
    private static final int WORKERS = 2;

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;

    @Test
    @DisplayName("★ 2 worker + 20 bài → mỗi bài được chấm ĐÚNG MỘT LẦN")
    void hai_worker_khong_cham_trung_bai_nao() throws Exception {
        for (int i = 0; i < SUBMISSIONS; i++) {
            quenLuotNopVuaRoi(USER_ID);
            submitSolution.submit(new SubmitSolutionUseCase.Command(
                    PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){ return " + i + "; }"));
        }
        assertThat(queueDepth()).isEqualTo(SUBMISSIONS);

        List<Long> judged = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new java.util.ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            Thread t = new Thread(() -> workerLoop(start, judged), "worker-" + w);
            t.start();
            workers.add(t);
        }
        start.countDown();
        for (Thread t : workers) {
            t.join(TimeUnit.MINUTES.toMillis(1));
        }

        assertThat(judged).hasSize(SUBMISSIONS).doesNotHaveDuplicates();
        assertThat(countJudgeRuns()).isEqualTo(SUBMISSIONS);
        assertThat(queueDepth()).isZero();
        assertThat(countByStatus("DONE")).isEqualTo(SUBMISSIONS);
    }

    private void workerLoop(CountDownLatch start, List<Long> judged) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        ClaimRequestDto request = ClaimRequestDto.single("mac-m1max-host", "arm64");
        while (true) {
            Optional<JudgeJobDto> job = claimJudgeJob.claim(request);
            if (job.isEmpty()) {
                return;
            }
            var outcome = recordJudgeResult.record(accepted(job.get()));
            if (outcome == RecordJudgeResultUseCase.Outcome.RECORDED) {
                judged.add(job.get().submissionId());
            }
        }
    }

    private static JudgeResultDto accepted(JudgeJobDto job) {
        return new JudgeResultDto(job.submissionId(), job.attempt(), Verdict.AC,
                100, 100, null, job.testcases().size(), 23, 8192, null, null,
                "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    private int queueDepth() {
        return jdbc.sql("SELECT count(*)::int FROM judge_queue").query(Integer.class).single();
    }

    private int countJudgeRuns() {
        return jdbc.sql("SELECT count(*)::int FROM judge_runs").query(Integer.class).single();
    }

    private int countByStatus(String status) {
        return jdbc.sql("SELECT count(*)::int FROM submissions WHERE status = :s")
                .param("s", status).query(Integer.class).single();
    }
}
