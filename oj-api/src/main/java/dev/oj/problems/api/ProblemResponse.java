package dev.oj.problems.api;

import dev.oj.problems.domain.Problem;

public record ProblemResponse(
        long problemId,
        String code,
        String title,
        String statement,
        String statementHtml,
        int timeLimitMs,
        int memoryLimitKb,
        String checkerType,
        String scoringMode,
        String feedbackLevel,
        boolean acceptsSubmissions) {

    public static ProblemResponse from(Problem problem, String statementHtml) {
        return new ProblemResponse(
                problem.id(),
                problem.code(),
                problem.title(),
                problem.statementMd(),
                statementHtml,
                problem.timeLimitMs(),
                problem.memoryLimitKb(),
                problem.checkerType().code(),
                problem.scoringMode().name(),
                problem.feedbackLevel().name(),
                problem.acceptsSubmissions());
    }

}
