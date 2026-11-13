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

class SubmissionResponseTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    @DisplayName("★ danh sách không mang chi tiết; chi tiết không mang source lẫn isolateStatus")
    void moi_response_chi_mang_dung_thu_no_duoc_phep_mang() {
        var chiTiet = fieldsOf(SubmissionDetailResponse.class);
        var danhSach = fieldsOf(SubmissionSummaryResponse.class);

        for (var fields : java.util.List.of(chiTiet, danhSach)) {
            assertThat(fields)
                    .as("mã nguồn và hash của nó không có việc gì ở response")
                    .noneMatch(f -> f.contains("source") || f.contains("sha"));
            assertThat(fields)
                    .as("isolateStatus chứa đường dẫn bên trong box — chỉ ADMIN, qua judge_runs")
                    .noneMatch(f -> f.contains("isolate"));
            assertThat(fields)
                    .as("nội dung testcase không tồn tại ở bất kỳ đâu trong oj-api")
                    .noneMatch(f -> f.contains("input") || f.contains("expected"));
        }

        assertThat(danhSach)
                .as("50 bài một trang, mỗi trường thừa là 50 lần rò rỉ thay vì một")
                .noneMatch(f -> f.contains("failed") || f.contains("ordinal")
                        || f.contains("compile") || f.contains("log"));

        assertThat(chiTiet).contains("failedtestordinal", "compilelog", "explanation");
    }

    private static java.util.List<String> fieldsOf(Class<?> dto) {
        return Arrays.stream(dto.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    @Test
    void thoi_gian_chay_duoc_lam_tron_den_10ms() {
        var outcome = new JudgeOutcome(Verdict.AC, 100, 100, null, 234, 4096);
        var response = SubmissionDetailResponse.from(visible(SubmissionStatus.DONE, outcome));

        assertThat(response.timeMs()).isEqualTo(230);
        assertThat(response.measurementNote())
                .as("một con số thời gian không kèm chú thích là một con số bị hiểu nhầm")
                .isEqualTo(dev.oj.judging.api.RuntimeFormatter.MEASUREMENT_NOTE);
    }

    @Test
    void bai_chua_cham_xong_thi_moi_truong_ket_qua_deu_rong() {
        var response = SubmissionDetailResponse.from(visible(SubmissionStatus.QUEUED, null));

        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.verdict()).isNull();
        assertThat(response.score()).isNull();
        assertThat(response.timeMs()).isNull();
        assertThat(response.judgedAt()).isNull();
    }

    @Test
    void bai_da_cham_xong_thi_mang_du_verdict_va_so_do() {
        var outcome = new JudgeOutcome(Verdict.WA, 40, 100, 7, 234, 15_360);
        var response = SubmissionDetailResponse.from(visible(SubmissionStatus.DONE, outcome));

        assertThat(response.verdict()).isEqualTo("WA");
        assertThat(response.score()).isEqualTo(40);
        assertThat(response.maxScore()).isEqualTo(100);
        assertThat(response.timeMs()).isEqualTo(230);
        assertThat(response.memoryKb()).isEqualTo(15_360);
    }

    @Test
    void toString_cua_request_khong_chua_ma_nguon() {
        var request = new SubmitSolutionRequest(1L, "cpp20", "int main(){return 0;}");

        assertThat(request.toString()).doesNotContain("main", "return");
    }

    private static dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission
            visible(SubmissionStatus status, JudgeOutcome outcome) {
        return new dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission(
                submission(status, outcome), null, null, null, 2000, 262_144, true);
    }

    private static Submission submission(SubmissionStatus status, JudgeOutcome outcome) {
        return new Submission(101L, 7L, 42L, null, 3, Sha256.hexOf("x"), 12, T0,
                status, outcome == null ? 0 : 1, 5,
                outcome, outcome == null ? null : T0.plusSeconds(2), null, null);
    }
}
