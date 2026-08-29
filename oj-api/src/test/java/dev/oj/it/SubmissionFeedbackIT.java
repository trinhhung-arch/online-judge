package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.GetSubmissionUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ FR-PROB-07 · FR-SUB-06 — bộ lọc {@code feedback_level} trên Postgres THẬT.
 *
 * <h2>Vì sao ca này cần một cơ sở dữ liệu thật</h2>
 * Vì bộ lọc chỉ đúng khi <b>ba thứ khớp nhau</b>: cột {@code problems.feedback_level} đọc
 * được, câu query của trang chi tiết join đúng bảng đó, và {@code FeedbackPolicy} được gọi.
 * Test đơn vị với repository giả kiểm được mảnh thứ ba; hai mảnh đầu chỉ hiện ra ở đây.
 *
 * <p>Đây cũng là ca đóng lại một khoảng hở có thật: trước Bước 3.11,
 * {@code revealsFailedTestOrdinal()} <b>không được gọi ở bất kỳ đâu</b> trong {@code src/main}
 * — bộ lọc tồn tại như một hàm, và trang chi tiết đơn giản là không trả con số nào.
 */
class SubmissionFeedbackIT extends PostgresIT {

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordResult;
    @Autowired GetSubmissionUseCase getSubmission;

    @Test
    @DisplayName("★ NONE giấu số thứ tự test sai; TEST_INDEX cho xem — CÙNG một bài nộp")
    void feedback_level_quyet_dinh_thi_sinh_thay_gi() {
        long id = judgeAsWrongAnswer();

        setFeedbackLevel("NONE");
        var an = getSubmission.detailById(id);
        assertThat(an.failedTestOrdinal())
                .as("thể thức ICPC: biết mình sai là đủ, biết sai ở đâu là một kênh dò bộ test")
                .isNull();

        setFeedbackLevel("TEST_INDEX");
        var hien = getSubmission.detailById(id);
        assertThat(hien.failedTestOrdinal()).isEqualTo(7);

        // Cùng một dòng trong `submissions`, hai câu trả lời khác nhau — nên con số vẫn được
        // LƯU đầy đủ, chỉ khác ở thứ được phép rời khỏi hệ thống.
        assertThat(rawFailedOrdinal(id)).isEqualTo(7);
    }

    /**
     * ★ FR-SUB-06 — log compiler <b>không</b> đi qua bộ lọc.
     *
     * <p>Ma trận hiển thị ({@code oj-api/CLAUDE.md} mục 2) cho tác giả xem vô điều kiện: đó là
     * output từ chính mã của họ, không phải dữ liệu của đề. Một đề mức {@code NONE} vẫn phải
     * nói được vì sao bài không biên dịch nổi, nếu không thì {@code CE} là một bức tường.
     */
    @Test
    @DisplayName("★ log compiler hiện cả ở mức NONE — nó là output của chính bài nộp")
    void log_compiler_khong_bi_feedback_level_chan() {
        long id = judgeAsWrongAnswer();
        setFeedbackLevel("NONE");

        assertThat(getSubmission.detailById(id).compileLog()).contains("cảnh báo giả");
    }

    /**
     * Chuỗi chẩn đoán của {@code isolate} chứa đường dẫn bên trong box. Nó tới được tầng
     * {@code api} để rút một mã tín hiệu cho câu giải thích, và <b>dừng ở đó</b>.
     */
    @Test
    @DisplayName("★ SEC3 — isolateStatus tới được use-case nhưng không có trường nào để đi tiếp")
    void isolate_status_khong_co_duong_ra_response() {
        long id = judgeAsWrongAnswer();

        assertThat(getSubmission.detailById(id).isolateStatus()).isNotBlank();
        assertThat(dev.oj.judging.api.dto.SubmissionDetailResponse.class.getRecordComponents())
                .noneMatch(field -> field.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains("isolate"));
    }

    /** Nộp → claim → ghi verdict WA ở test 7, đúng đường thật. */
    private long judgeAsWrongAnswer() {
        var accepted = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: WA\nint main(){}"));
        var job = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow();

        recordResult.record(new JudgeResultDto(
                accepted.submissionId(), job.attempt(), Verdict.WA, 0, 100,
                7, 20, 234, 4096,
                "Main.cpp:3:5: warning: cảnh báo giả", "SG exit=0 signal=11 cpu=3ms",
                "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of()));
        return accepted.submissionId();
    }

    private void setFeedbackLevel(String level) {
        jdbc.sql("UPDATE problems SET feedback_level = :level WHERE id = :id")
                .param("level", level).param("id", PROBLEM_ID).update();
    }

    private Integer rawFailedOrdinal(long id) {
        return jdbc.sql("SELECT failed_test_ordinal FROM submissions WHERE id = :id")
                .param("id", id).query(Integer.class).single();
    }
}
