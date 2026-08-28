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

/**
 * ★ <b>Đây là lý do reaper tồn tại.</b>
 *
 * <p>RabbitMQ chết, hoặc mạng đứt đúng giây sau khi commit — lời gọi publish ném lỗi. Bài nộp
 * đã nằm trong DB rồi, và người dùng <b>không được</b> nhận lỗi vì một sự cố ở tầng thông báo.
 *
 * <p>Đó chính là điều làm cho việc đổi transport ở M6 rẻ: hàng đợi là {@code judge_queue},
 * RabbitMQ chỉ là đường dẫn. Nếu test này đỏ, nghĩa là ở đâu đó queue đã bị biến từ đường dẫn
 * thành kho chứa — và lúc đó R1 không còn được Postgres bảo đảm nữa.
 */
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

        // 1. Người dùng không thấy gì bất thường.
        assertThat(accepted.status()).isEqualTo(SubmissionStatus.QUEUED);

        // 2. Ba câu ghi đã COMMIT — publish nằm ngoài transaction nên lỗi của nó không rollback.
        assertThat(status(accepted.submissionId())).isEqualTo("QUEUED");
        assertThat(queueDepth()).isEqualTo(1);

        // 3. Và bài vẫn claim được bình thường: worker PULL từ judge_queue, không từ RabbitMQ.
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
