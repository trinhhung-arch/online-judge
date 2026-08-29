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
     * ★ Ranh giới rò rỉ, và nó ĐÃ DỊCH ở M3 — đó chính là điều test này ghi lại.
     *
     * <p>Ở M1, {@code failedTestOrdinal} vắng mặt ở <b>cả hai</b> response, vì
     * {@code FeedbackPolicy} chưa tồn tại và trả con số đó ra là chạy bốn tuần với
     * {@code feedback_level} bị bỏ qua hoàn toàn.
     *
     * <p>Ở M3 bộ lọc đã có, nên trang chi tiết được phép mang nó — <b>bản đã lọc</b>. Danh
     * sách thì không: một endpoint trả 50 bài một lúc không có việc gì với chi tiết của từng
     * bài, và mỗi trường thừa ở đó là 50 lần rò rỉ thay vì một.
     */
    @Test
    @DisplayName("★ danh sách không mang chi tiết; chi tiết không mang source lẫn isolateStatus")
    void moi_response_chi_mang_dung_thu_no_duoc_phep_mang() {
        var chiTiet = fieldsOf(SubmissionDetailResponse.class);
        var danhSach = fieldsOf(SubmissionSummaryResponse.class);

        // Cấm ở CẢ HAI, không có ngoại lệ nào và sẽ không bao giờ có (bất biến #1, #9).
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

        // Chỉ cấm ở DANH SÁCH.
        assertThat(danhSach)
                .as("50 bài một trang, mỗi trường thừa là 50 lần rò rỉ thay vì một")
                .noneMatch(f -> f.contains("failed") || f.contains("ordinal")
                        || f.contains("compile") || f.contains("log"));

        // Và trang chi tiết PHẢI có chúng — nếu không thì FR-SUB-06 và FR-PROB-07 chỉ nằm
        // trên giấy, đúng như tình trạng trước bước 3.11.
        assertThat(chiTiet).contains("failedtestordinal", "compilelog", "explanation");
    }

    private static java.util.List<String> fieldsOf(Class<?> dto) {
        return Arrays.stream(dto.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .toList();
    }

    /**
     * FR-SUB-11 · P7 — cả hai response phải dùng <b>cùng một</b> bộ làm tròn.
     *
     * <p>Trước bước 3.12 có hai bản: một hàm riêng trong {@code SubmissionDetailResponse} và
     * {@code RuntimeFormatter} vừa viết mà chưa ai gọi. Hai bản làm tròn cho cùng một con số
     * là loại lỗi chỉ lộ ra khi ai đó sửa một bản.
     */
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
        assertThat(response.timeMs()).isNull();      // KHÔNG phải 0 — xem SubmissionRowMappers
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

    /** Request không được để mã nguồn lọt vào một dòng log (bất biến #9). */
    @Test
    void toString_cua_request_khong_chua_ma_nguon() {
        var request = new SubmitSolutionRequest(1L, "cpp20", "int main(){return 0;}");

        assertThat(request.toString()).doesNotContain("main", "return");
    }

    /**
     * {@code failedTestOrdinal} truyền vào là bản ĐÃ LỌC — đúng như
     * {@code GetSubmissionUseCase.detailById} trả ra. Ở đây để {@code null} với mọi ca, nên
     * test này không vô tình khẳng định gì về bộ lọc; việc đó là của
     * {@code FeedbackPolicyTest} và {@code SubmissionFeedbackIT}.
     */
    private static dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission
            visible(SubmissionStatus status, JudgeOutcome outcome) {
        return new dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission(
                submission(status, outcome), null, null, null, 2000, 262_144);
    }

    private static Submission submission(SubmissionStatus status, JudgeOutcome outcome) {
        return new Submission(101L, 7L, 42L, null, 3, Sha256.hexOf("x"), 12, T0,
                status, outcome == null ? 0 : 1, 5,
                outcome, outcome == null ? null : T0.plusSeconds(2), null, null);
    }
}
