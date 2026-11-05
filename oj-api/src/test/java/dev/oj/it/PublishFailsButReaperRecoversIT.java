package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.judging.domain.SubmissionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

class PublishFailsButReaperRecoversIT extends PostgresIT {

    @MockitoBean JudgeJobPublisher publisher;

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;

    @BeforeEach
    void publisherAlwaysFails() {
        doThrow(new IllegalStateException("RabbitMQ chết"))
                .when(publisher).publishEnqueued(anyLong());
    }

    @Test
    @DisplayName("★ publish ném lỗi → người dùng vẫn nhận 202, và bài vẫn được chấm")
    void publish_hong_khong_lam_mat_bai_nop() {
        var accepted = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}"));

        assertThat(accepted.status()).isEqualTo(SubmissionStatus.QUEUED);

        assertThat(status(accepted.submissionId())).isEqualTo("QUEUED");
        assertThat(queueDepth()).isEqualTo(1);

        var job = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"));
        assertThat(job).isPresent();
        assertThat(job.orElseThrow().submissionId()).isEqualTo(accepted.submissionId());
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM submissions WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private int queueDepth() {
        return jdbc.sql("SELECT count(*)::int FROM judge_queue").query(Integer.class).single();
    }
}
