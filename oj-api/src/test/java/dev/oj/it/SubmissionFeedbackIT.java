package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.api.dto.SubmissionDetailResponse;
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

    @Test
    @DisplayName("★ BM-3 · bài RE ở đề NONE: không nói tín hiệu nào; TEST_INDEX vẫn nói SIGSEGV — CÙNG một bài nộp")
    void tin_hieu_re_theo_feedback_level() {
        long id = cham(Verdict.RE, "SG exit=0 signal=11 cpu=3ms");

        setFeedbackLevel("NONE");
        String an = SubmissionDetailResponse.from(getSubmission.detailById(id)).explanation();
        assertThat(an).as("tín hiệu là thứ chương trình tự chọn được — ở NONE nó là một kênh "
                        + "nhắn nội dung test ẩn ra ngoài")
                .doesNotContain("SIGSEGV", "tín hiệu", "signal", "11")
                .contains("kết thúc bất thường");

        setFeedbackLevel("TEST_INDEX");
        assertThat(SubmissionDetailResponse.from(getSubmission.detailById(id)).explanation())
                .contains("SIGSEGV");
    }

    @Test
    @DisplayName("BM-3 · bài IE ở đề NONE vẫn có mã sự cố — chỉ tín hiệu của RE bị lọc, không phải mọi chẩn đoán")
    void ma_su_co_ie_khong_bi_loc() {
        long id = cham(Verdict.IE, "XX isolate: cgroup hỏng");
        setFeedbackLevel("NONE");

        assertThat(SubmissionDetailResponse.from(getSubmission.detailById(id)).explanation())
                .containsPattern("IE-[0-9A-F]{8}")
                .doesNotContain("IE-UNKNOWN");
    }

    @Test
    @DisplayName("★ BM-2 · bộ nhớ trong phản hồi đã làm tròn lên MB; số đo thô vẫn nằm nguyên trong DB")
    void bo_nho_trong_phan_hoi_da_lam_tron() {
        long id = cham(Verdict.AC, "OK");

        var phanHoi = SubmissionDetailResponse.from(getSubmission.detailById(id));

        assertThat(phanHoi.memoryKb()).isEqualTo(5_120);
        assertThat(phanHoi.explanation()).contains("5 MB").doesNotContain("4097", "4.0 MB");
        assertThat(jdbc.sql("SELECT memory_kb FROM submissions WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(4_097);
    }

    private long cham(Verdict verdict, String isolateStatus) {
        var accepted = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: " + verdict + "\nint main(){}"));
        var job = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow();
        if (verdict == Verdict.IE) {
            jdbc.sql("UPDATE judge_queue SET ie_retry_count = 99 WHERE submission_id = :id")
                    .param("id", accepted.submissionId()).update();
        }
        boolean ac = verdict == Verdict.AC;
        recordResult.record(new JudgeResultDto(
                accepted.submissionId(), job.attempt(), verdict, ac ? 100 : 0, 100,
                ac ? null : 3, 3, 20, 4_097, null, isolateStatus,
                "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of()));
        return accepted.submissionId();
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
