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
import static org.mockito.Mockito.doThrow;

/**
 * ★ <b>Đã commit thì còn; chưa commit thì không để lại mảnh vụn nào.</b>
 *
 * <p>Ba câu ghi của đường nộp bài — {@code source_blobs}, {@code submissions},
 * {@code judge_queue} — phải là một khối. Nếu câu thứ ba hỏng mà hai câu đầu ở lại, hệ thống
 * có một bài nộp <b>không bao giờ được chấm</b>: không có hàng trong {@code judge_queue} thì
 * reaper cũng không nhặt được, vì reaper quét hàng đợi chứ không quét bảng nóng.
 *
 * <p>Đó là cách một bài nộp biến mất trong im lặng, và R1 nói điều đó tuyệt đối không được
 * xảy ra.
 */
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

        // Không một dòng nào ở lại — kể cả source_blobs, câu ghi ĐẦU TIÊN của transaction.
        assertThat(countSubmissions()).isZero();
        assertThat(countBlobs()).isZero();
        assertThat(countQueue()).isZero();
    }

    /**
     * Hỏng ở khâu validate thì còn sạch hơn: chưa transaction nào được mở.
     *
     * <p>Người dùng nhận một lỗi <b>rõ ràng</b> (404 kèm câu tiếng người), không phải 500 —
     * và có thể nộp lại ngay.
     */
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
