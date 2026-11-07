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

        assertThat(rawFailedOrdinal(id)).isEqualTo(7);
    }

    @Test
    @DisplayName("★ log compiler hiện cả ở mức NONE — nó là output của chính bài nộp")
    void log_compiler_khong_bi_feedback_level_chan() {
        long id = judgeAsWrongAnswer();
        setFeedbackLevel("NONE");

        assertThat(getSubmission.detailById(id).compileLog()).contains("cảnh báo giả");
    }

    @Test
    @DisplayName("★ SEC3 — isolateStatus tới được use-case nhưng không có trường nào để đi tiếp")
    void isolate_status_khong_co_duong_ra_response() {
        long id = judgeAsWrongAnswer();

        assertThat(getSubmission.detailById(id).isolateStatus()).isNotBlank();
        assertThat(dev.oj.judging.api.dto.SubmissionDetailResponse.class.getRecordComponents())
                .noneMatch(field -> field.getName().toLowerCase(java.util.Locale.ROOT)
                        .contains("isolate"));
    }

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
