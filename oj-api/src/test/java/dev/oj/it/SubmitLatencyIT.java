package dev.oj.it;

import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.judging.domain.SubmissionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubmitLatencyIT extends PostgresIT {

    private static final Logger log = LoggerFactory.getLogger(SubmitLatencyIT.class);

    private static final int WARMUP = 20;
    private static final int SAMPLES = 100;
    private static final Duration P2_BUDGET = Duration.ofMillis(300);

    @Autowired SubmitSolutionUseCase submitSolution;

    @Test
    @DisplayName("★ p95 của đường nộp bài nằm trong ngân sách P2")
    void nop_bai_nhanh_hon_ngan_sach_P2() {
        for (int i = 0; i < WARMUP; i++) {
            submit(i);
        }

        List<Long> nanos = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            submit(WARMUP + i);
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);

        Duration p50 = Duration.ofNanos(nanos.get(SAMPLES / 2));
        Duration p95 = Duration.ofNanos(nanos.get((int) (SAMPLES * 0.95)));
        Duration max = Duration.ofNanos(nanos.get(SAMPLES - 1));
        log.info("P2 (phần use-case, ngân sách 50ms trong tổng 300ms): "
                        + "p50={}ms p95={}ms max={}ms trên {} mẫu",
                p50.toMillis(), p95.toMillis(), max.toMillis(), SAMPLES);

        assertThat(p95).as("p95 đường nộp bài vượt ngân sách P2 — có ai vừa thêm một lời gọi "
                + "I/O vào SubmitSolutionUseCase?").isLessThan(P2_BUDGET);
    }

    @Test
    void nop_bai_KHONG_cho_verdict() {
        var accepted = submit(1_000);

        assertThat(accepted.status()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(verdict(accepted.submissionId())).isNull();
        assertThat(countJudgeRuns()).isZero();
    }

    private SubmitSolutionUseCase.SubmissionAccepted submit(int seed) {
        quenLuotNopVuaRoi(USER_ID);
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){ return " + seed + "; }"));
    }

    private String verdict(long id) {
        return jdbc.sql("SELECT verdict FROM submissions WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private int countJudgeRuns() {
        return jdbc.sql("SELECT count(*)::int FROM judge_runs").query(Integer.class).single();
    }
}
