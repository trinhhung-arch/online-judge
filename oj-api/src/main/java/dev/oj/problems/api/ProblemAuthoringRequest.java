package dev.oj.problems.api;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.domain.FeedbackLevel;

import java.math.BigDecimal;

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
