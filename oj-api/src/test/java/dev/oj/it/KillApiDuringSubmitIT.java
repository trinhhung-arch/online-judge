package dev.oj.it;

import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.problems.domain.ProblemNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

class KillApiDuringSubmitIT extends PostgresIT {

    @MockitoSpyBean JudgeQueueRepository queue;

    @Autowired SubmitSolutionUseCase submitSolution;

    @Test
    @DisplayName("★ hỏng ở câu ghi CUỐI → cả ba câu bị rollback, không để lại bài nộp mồ côi")
    void hong_giua_transaction_thi_khong_con_manh_vun_nao() {
        doThrow(new IllegalStateException("mất kết nối DB giữa chừng"))
                .when(queue).enqueue(anyLong(), anyInt());

        assertThatThrownBy(() -> submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countSubmissions()).isZero();
        assertThat(countBlobs()).isZero();
        assertThat(countQueue()).isZero();
    }

    @Test
    @DisplayName("NH-17 · hỏng giữa transaction thì lượt 10 giây đã bị tính: nộp lại ngay nhận 429 — đã chấp nhận, nfrplan 5.2")
    void hong_giua_transaction_thi_luot_da_tinh() {
        doThrow(new IllegalStateException("mất kết nối DB giữa chừng"))
                .when(queue).enqueue(anyLong(), anyInt());
        assertThatThrownBy(() -> submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")))
                .isInstanceOf(IllegalStateException.class);
        doCallRealMethod().when(queue).enqueue(anyLong(), anyInt());

        assertThatThrownBy(() -> submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")))
                .hasFieldOrPropertyWithValue("code", "submission.rate_limited");
        assertThat(countSubmissions()).isZero();
    }

    @Test
    void de_khong_ton_tai_thi_khong_mo_transaction_nao() {
        assertThatExceptionOfType(ProblemNotFoundException.class).isThrownBy(() ->
                submitSolution.submit(new SubmitSolutionUseCase.Command(
                        999_999L, "cpp20", "// EXPECT: AC\nint main(){}")));

        assertThat(countSubmissions()).isZero();
        assertThat(countBlobs()).isZero();
    }

    private int countSubmissions() {
        return jdbc.sql("SELECT count(*)::int FROM submissions").query(Integer.class).single();
    }

    private int countBlobs() {
        return jdbc.sql("SELECT count(*)::int FROM source_blobs").query(Integer.class).single();
    }

    private int countQueue() {
        return jdbc.sql("SELECT count(*)::int FROM judge_queue").query(Integer.class).single();
    }
}
