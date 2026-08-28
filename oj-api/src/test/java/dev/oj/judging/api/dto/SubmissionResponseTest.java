package dev.oj.judging.api.dto;

import dev.oj.contract.Sha256;
import dev.oj.contract.Verdict;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Hai điều mà tầng {@code api} quyết định, và cả hai đều là quyết định về rò rỉ. */
class SubmissionResponseTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    /**
     * ★ FR-PROB-07 — {@code failedTestOrdinal} không được có mặt cho tới khi
     * {@code FeedbackPolicy} tồn tại (M3).
     *
     * <p>Trả con số đó ra bây giờ là chạy bốn tuần với {@code feedback_level} bị bỏ qua hoàn
     * toàn. Với đề đặt mức {@code NONE} — thể thức ICPC — đó đúng là đường rò rỉ mà FR-PROB-07
     * sinh ra để đóng. Test này đỏ khi ai đó thêm trường vào trước bộ lọc.
     */
    @Test
    @DisplayName("★ response KHÔNG mang số thứ tự test sai, và không mang source")
    void khong_truong_nao_lo_thong_tin_chua_duoc_loc() {
        for (Class<?> dto : new Class<?>[]{
                SubmissionDetailResponse.class, SubmissionSummaryResponse.class}) {
            var fields = Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(String::toLowerCase)
                    .toList();

            assertThat(fields)
                    .as("%s — số thứ tự test sai phải qua FeedbackPolicy (M3) trước", dto.getSimpleName())
                    .noneMatch(f -> f.contains("failed") || f.contains("ordinal"));
            assertThat(fields)
                    .as("%s — mã nguồn và hash của nó không có việc gì ở response", dto.getSimpleName())
                    .noneMatch(f -> f.contains("source") || f.contains("sha"));
            assertThat(fields)
                    .as("%s — log compiler là FR-SUB-06, thuộc M3", dto.getSimpleName())
                    .noneMatch(f -> f.contains("compile") || f.contains("log"));
        }
    }

    /**
     * FR-SUB-11 · P7 — làm tròn 10ms. Chữ số hàng mili giây là nhiễu (±5%), và hiển thị nó
     * tạo ra một trò chơi giả tốn lượt chấm thật.
     */
    @Test
    void thoi_gian_chay_duoc_lam_tron_den_10ms() {
        assertThat(SubmissionDetailResponse.roundTo10ms(23)).isEqualTo(20);
        assertThat(SubmissionDetailResponse.roundTo10ms(26)).isEqualTo(30);
        assertThat(SubmissionDetailResponse.roundTo10ms(2034)).isEqualTo(2030);
        assertThat(SubmissionDetailResponse.roundTo10ms(4)).isZero();
        assertThat(SubmissionDetailResponse.roundTo10ms(null)).isNull();
    }

    @Test
    void bai_chua_cham_xong_thi_moi_truong_ket_qua_deu_rong() {
        var response = SubmissionDetailResponse.from(submission(SubmissionStatus.QUEUED, null));

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.verdict()).isNull();
        assertThat(response.score()).isNull();
        assertThat(response.timeMs()).isNull();      // KHÔNG phải 0 — xem SubmissionRowMappers
        assertThat(response.judgedAt()).isNull();
    }

    @Test
    void bai_da_cham_xong_thi_mang_du_verdict_va_so_do() {
        var outcome = new JudgeOutcome(Verdict.WA, 40, 100, 7, 234, 15_360);
        var response = SubmissionDetailResponse.from(submission(SubmissionStatus.DONE, outcome));

        assertThat(response.verdict()).isEqualTo("WA");
        assertThat(response.score()).isEqualTo(40);
        assertThat(response.maxScore()).isEqualTo(100);
        assertThat(response.timeMs()).isEqualTo(230);
        assertThat(response.memoryKb()).isEqualTo(15_360);
    }

    /** Request không được để mã nguồn lọt vào một dòng log (bất biến #9). */
    @Test
    void toString_cua_request_khong_chua_ma_nguon() {
        var request = new SubmitSolutionRequest(1L, "cpp20", "int main(){return 0;}");

        assertThat(request.toString()).doesNotContain("main", "return");
    }

    private static Submission submission(SubmissionStatus status, JudgeOutcome outcome) {
        return new Submission(101L, 7L, 42L, null, 3, Sha256.hexOf("x"), 12, T0,
                status, outcome == null ? 0 : 1, 5,
                outcome, outcome == null ? null : T0.plusSeconds(2), null, null);
    }
}
