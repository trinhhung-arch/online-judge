package dev.oj.problems.api;

import dev.oj.problems.domain.Problem;

import java.math.BigDecimal;

public record ProblemAuthoringResponse(
        long problemId,
        String code,
        String title,
        String statementMd,
        String statementHtml,
        int timeLimitMs,
        int memoryLimitKb,
        String checkerType,
        BigDecimal checkerEpsilon,
        String scoringMode,
        String feedbackLevel,
        boolean allowPublicSolutions,
        String status) {

    public static ProblemAuthoringResponse from(Problem de, String statementHtml) {
        return new ProblemAuthoringResponse(
                de.id(),
                de.code(),
                de.title(),
                de.statementMd(),
                statementHtml,
                de.timeLimitMs(),
                de.memoryLimitKb(),
                de.checkerType().code(),
                de.checkerEpsilon(),
                de.scoringMode().name(),
                de.feedbackLevel().name(),
                de.allowPublicSolutions(),
                de.status().name());
    }
}
