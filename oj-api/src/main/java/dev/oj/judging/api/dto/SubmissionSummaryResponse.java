package dev.oj.judging.api.dto;

import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;

import java.time.Instant;

public record SubmissionSummaryResponse(
        long submissionId,
        long problemId,
        String problemCode,
        String problemTitle,
        int languageId,
        String status,
        String verdict,
        Integer score,
        Integer timeMs,
        Instant createdAt) {

    public static SubmissionSummaryResponse from(SubmissionListItem item) {
        return new SubmissionSummaryResponse(
                item.id(),
                item.problemId(),
                item.problemCode(),
                item.problemTitle(),
                item.languageId(),
                item.status().name(),
                item.verdict() == null ? null : item.verdict().name(),
                item.score(),
                dev.oj.judging.api.RuntimeFormatter.roundMs(item.timeMs()),
                item.createdAt());
    }
}
