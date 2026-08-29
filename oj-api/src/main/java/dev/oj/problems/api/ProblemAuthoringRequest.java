package dev.oj.problems.api;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.domain.FeedbackLevel;

import java.math.BigDecimal;

/**
 * Đầu vào tạo và sửa đề — FR-PROB-01, 07.
 *
 * <h2>Không có {@code status}, không có {@code ownerId}, không có {@code testdataVersion}</h2>
 * Ba trường đó có đường đi riêng, và để chúng ở đây là mở ba lỗ hổng khác nhau: tự xuất bản
 * đề mà bỏ qua phép kiểm "đã có testdata chưa", tự gán đề cho người khác, và tự trỏ đề sang
 * một phiên bản testdata chưa từng được nạp.
 *
 * <p>Đây cùng một bài học với {@code UpdateProfileRequest} ở Bước 4.3: record hẹp thì không có
 * trường nào để gửi thừa.
 *
 * @param checkerEpsilon bắt buộc khi và chỉ khi {@code checkerType} là {@code float} —
 *                       {@code ck_problems_epsilon} của V2 ép cả hai chiều
 */
public record ProblemAuthoringRequest(
        String code,
        String title,
        String statementMd,
        Integer timeLimitMs,
        Integer memoryLimitKb,
        String checkerType,
        BigDecimal checkerEpsilon,
        String scoringMode,
        String feedbackLevel,
        Boolean allowPublicSolutions) {

    /**
     * Mặc định an toàn cho ba trường có thể vắng mặt.
     *
     * <p>{@code feedbackLevel} mặc định {@code TEST_INDEX} — khớp {@code DEFAULT} của V2, và
     * là <b>mức an toàn nhất mà vẫn dùng được để luyện tập</b>. Quên khai báo thì được mức kín
     * hơn, không phải mức hở hơn (FR-PROB-07 là biện pháp chống rò rỉ testdata, không phải
     * một tính năng).
     */
    public AuthorProblemUseCase.Command toCommand() {
        return new AuthorProblemUseCase.Command(
                code, title, statementMd,
                timeLimitMs == null ? 1000 : timeLimitMs,
                memoryLimitKb == null ? 262_144 : memoryLimitKb,
                checkerType == null ? CheckerType.TOKEN : CheckerType.fromCode(checkerType),
                checkerEpsilon,
                scoringMode == null ? ScoringMode.ALL_OR_NOTHING
                        : ScoringMode.valueOf(scoringMode),
                feedbackLevel == null ? FeedbackLevel.TEST_INDEX
                        : FeedbackLevel.valueOf(feedbackLevel),
                Boolean.TRUE.equals(allowPublicSolutions));
    }
}
