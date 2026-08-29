package dev.oj.judging.api.dto;

import dev.oj.contract.Verdict;
import dev.oj.judging.api.RuntimeFormatter;
import dev.oj.judging.api.VerdictExplainer;
import dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;

import java.time.Instant;

/**
 * {@code GET /api/v1/submissions/{id}} — FR-SUB-03, FR-SUB-04, FR-SUB-06, FR-PROB-07.
 *
 * <h2>★ {@code failedTestOrdinal} ĐÃ QUA BỘ LỌC trước khi tới đây</h2>
 * Ở M1 trường này cố ý vắng mặt, vì bộ lọc theo {@code problems.feedback_level} chưa tồn tại
 * — thà thiếu một tính năng còn hơn có bốn tuần chạy với {@code feedback_level} bị bỏ qua.
 *
 * <p>Ở M3 thứ tự đúng đã hoàn tất: <b>bộ lọc trước, dữ liệu sau</b>.
 * {@code GetSubmissionUseCase.detailById} áp {@code FeedbackPolicy} rồi mới trả ra, nên
 * {@code null} ở đây có hai nghĩa và cả hai đều đúng: không có test nào sai, hoặc đề không
 * công bố. Class này <b>không được</b> tự quyết định gì về nó — nó chỉ in ra thứ đã nhận.
 *
 * <h2>Ba thứ vẫn không có, và sẽ không bao giờ có</h2>
 * Nội dung testcase (không tồn tại ở bất kỳ đâu trong {@code oj-api}), {@code sourceSha256},
 * và {@code isolateStatus} — chuỗi cuối chứa đường dẫn bên trong box, nó chỉ được dùng để
 * <i>rút ra</i> một mã tín hiệu cho câu giải thích rồi bị bỏ đi.
 *
 * @param explanation     câu giải thích verdict (U3) — {@code null} khi bài chưa chấm xong
 * @param measurementNote đi kèm mọi con số thời gian, và <b>chỉ</b> khi có số để kèm
 * @param timeMs          đã làm tròn 10ms — {@link RuntimeFormatter}
 * @param attempt         lần chấm thứ mấy. Có mặt để UI phân biệt "WA" với "WA · đang chấm
 *                        lại": một bài {@code JUDGING} mà vẫn có verdict cũ là bài đang rejudge
 */
public record SubmissionDetailResponse(
        long submissionId,
        long problemId,
        int languageId,
        String status,
        String verdict,
        String explanation,
        Integer score,
        Integer maxScore,
        Integer timeMs,
        Integer memoryKb,
        String measurementNote,
        Integer failedTestOrdinal,
        String compileLog,
        int attempt,
        Instant createdAt,
        Instant judgedAt) {

    public static SubmissionDetailResponse from(VisibleSubmission visible) {
        Submission s = visible.submission();
        JudgeOutcome outcome = s.outcome();
        boolean daCham = outcome != null;
        Integer timeMs = daCham ? RuntimeFormatter.roundMs(outcome.timeMs()) : null;

        return new SubmissionDetailResponse(
                s.id(),
                s.problemId(),
                s.languageId(),
                s.status().name(),
                daCham ? outcome.verdict().name() : null,
                daCham ? explain(outcome.verdict(), visible) : null,
                daCham ? outcome.score() : null,
                daCham ? outcome.maxScore() : null,
                timeMs,
                daCham ? outcome.memoryKb() : null,
                // Chỉ kèm chú thích khi thật sự có con số: một bài CE không có phép đo nào,
                // và "sai số ±5%" đứng cạnh một ô trống chỉ làm người đọc bối rối.
                timeMs == null ? null : RuntimeFormatter.MEASUREMENT_NOTE,
                visible.failedTestOrdinal(),
                visible.compileLog(),
                s.attempt(),
                s.createdAt(),
                s.judgedAt());
    }

    /**
     * <b>{@code failedTestOrdinal} truyền vào đây là bản ĐÃ LỌC.</b>
     *
     * <p>Nếu một ngày ai đó đổi thành {@code outcome.failedTestOrdinal()} cho "tiện" — con số
     * thô, luôn có giá trị — thì câu giải thích sẽ nói "sai ở test 7" cho một đề đặt mức
     * {@code NONE}, và bộ lọc bị đi vòng qua đúng ở chặng cuối cùng.
     */
    private static String explain(Verdict verdict, VisibleSubmission visible) {
        JudgeOutcome outcome = visible.submission().outcome();
        return VerdictExplainer.explain(verdict, new VerdictExplainer.Facts(
                outcome.timeMs(),
                visible.timeLimitMs(),
                outcome.memoryKb(),
                visible.memoryLimitKb(),
                visible.failedTestOrdinal(),
                null,
                visible.isolateStatus()));
    }
}
