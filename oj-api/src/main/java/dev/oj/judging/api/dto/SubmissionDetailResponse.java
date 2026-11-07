package dev.oj.judging.api.dto;

import dev.oj.contract.Verdict;
import dev.oj.judging.api.RuntimeFormatter;
import dev.oj.judging.api.VerdictExplainer;
import dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;

import java.time.Instant;

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
                timeMs == null ? null : RuntimeFormatter.MEASUREMENT_NOTE,
                visible.failedTestOrdinal(),
                visible.compileLog(),
                s.attempt(),
                s.createdAt(),
                s.judgedAt());
    }

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
