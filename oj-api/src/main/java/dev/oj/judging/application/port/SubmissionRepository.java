package dev.oj.judging.application.port;

import dev.oj.contract.Verdict;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface SubmissionRepository {

    long insert(NewSubmission submission);

    Optional<Submission> findForRequester(long id, long requesterId, Role role);

    Optional<SubmissionDetail> findDetailForRequester(long id, long requesterId, Role role);

    record SubmissionDetail(
            Submission submission,
            dev.oj.problems.domain.FeedbackLevel feedbackLevel,
            int timeLimitMs,
            int memoryLimitKb,
            String compileLog,
            String isolateStatus) {
    }

    CursorPage<SubmissionListItem> listForUser(long userId, SubmissionFilter filter,
                                               Long cursor, int size);

    Optional<Instant> lastSubmittedAt(long userId);

    boolean markJudging(long submissionId, int attempt);

    boolean markDone(long submissionId, int attempt, JudgeOutcome outcome, Instant judgedAt);

    int markQueued(Collection<Long> submissionIds);

    boolean datAn(long submissionId, boolean an, long adminId, Instant luc);

    record NewSubmission(
            long userId,
            long problemId,
            Long contestId,
            int languageId,
            String sourceSha256,
            int sourceBytes,
            int testdataVersion) {
    }

    record SubmissionListItem(
            long id,
            long problemId,
            String problemCode,
            String problemTitle,
            int languageId,
            SubmissionStatus status,
            Verdict verdict,
            Integer score,
            Integer timeMs,
            Integer memoryKb,
            Instant createdAt) {
    }

    record SubmissionFilter(Long problemId, Verdict verdict, Integer languageId) {

        private static final SubmissionFilter NONE = new SubmissionFilter(null, null, null);

        public static SubmissionFilter none() {
            return NONE;
        }
    }
}
