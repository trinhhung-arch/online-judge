package dev.oj.problems.api;

import dev.oj.problems.application.port.ProblemRepository;

public record ProblemSummaryResponse(
        String code,
        String title,
        int timeLimitMs,
        int memoryLimitKb,
        boolean daGiai) {

    public static ProblemSummaryResponse from(ProblemRepository.ProblemListItem item) {
        return new ProblemSummaryResponse(item.code(), item.title(),
                item.timeLimitMs(), item.memoryLimitKb(), item.daGiai());
    }
}
