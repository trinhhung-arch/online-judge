package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.domain.Submission;
import dev.oj.platform.security.RequiresRole;
import dev.oj.problems.domain.FeedbackPolicy;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import org.springframework.stereotype.Service;

@RequiresRole
@Service
public class GetSubmissionUseCase {

    private final CurrentUserProvider currentUser;
    private final SubmissionRepository submissions;

    public GetSubmissionUseCase(CurrentUserProvider currentUser, SubmissionRepository submissions) {
        this.currentUser = currentUser;
        this.submissions = submissions;
    }

    public Submission byId(long submissionId) {
        CurrentUser user = currentUser.current();
        return submissions.findForRequester(submissionId, user.id(), user.role())
                .orElseThrow(() -> JudgingException.submissionNotFound(submissionId));
    }

    public VisibleSubmission detailById(long submissionId) {
        CurrentUser user = currentUser.current();
        var detail = submissions.findDetailForRequester(submissionId, user.id(), user.role())
                .orElseThrow(() -> JudgingException.submissionNotFound(submissionId));

        FeedbackPolicy policy = FeedbackPolicy.of(detail.feedbackLevel());
        Integer failedTestOrdinal = detail.submission().outcome() == null
                ? null
                : policy.failedTestOrdinal(detail.submission().outcome().failedTestOrdinal());

        return new VisibleSubmission(
                detail.submission(),
                failedTestOrdinal,
                detail.compileLog(),
                detail.isolateStatus(),
                detail.timeLimitMs(),
                detail.memoryLimitKb(),
                policy.level().revealsFailedTestOrdinal());
    }

    public record VisibleSubmission(
            Submission submission,
            Integer failedTestOrdinal,
            String compileLog,
            String isolateStatus,
            int timeLimitMs,
            int memoryLimitKb,
            boolean revealsTestProgress) {
    }

}
