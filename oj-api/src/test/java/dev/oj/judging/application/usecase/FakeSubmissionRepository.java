package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.Submission;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

class FakeSubmissionRepository implements SubmissionRepository {

    private final List<String> calls;

    FakeSubmissionRepository(List<String> calls) {
        this.calls = calls;
    }

        long nextId = 101L;
        NewSubmission inserted;
        Submission found;
        JudgeOutcome doneOutcome;
        boolean markDoneKhop = true;
        Collection<Long> requeued;
        Integer judgingAttempt;
        Long requesterId;
        Role requesterRole;
        Long cursorSeen;
        int sizeSeen;

        @Override
        public long insert(NewSubmission submission) {
            calls.add("submissions.insert");
            inserted = submission;
            return nextId;
        }

        @Override
        public Optional<Submission> findForRequester(long id, long requesterId, Role role) {
            calls.add("submissions.findForRequester");
            this.requesterId = requesterId;
            this.requesterRole = role;
            return Optional.ofNullable(found);
        }

        dev.oj.problems.domain.FeedbackLevel feedbackLevel =
                dev.oj.problems.domain.FeedbackLevel.TEST_INDEX;

        @Override
        public Optional<SubmissionDetail> findDetailForRequester(long id, long requesterId,
                                                                 Role role) {
            calls.add("submissions.findDetailForRequester");
            this.requesterId = requesterId;
            this.requesterRole = role;
            return Optional.ofNullable(found).map(submission -> new SubmissionDetail(
                    submission, feedbackLevel, 2000, 262_144, "log giả", "SG signal=11"));
        }

        @Override
        public CursorPage<SubmissionListItem> listForUser(long userId, SubmissionFilter filter,
                                                          Long cursor, int size) {
            calls.add("submissions.listForUser");
            this.cursorSeen = cursor;
            this.sizeSeen = size;
            return CursorPage.last(List.of());
        }

        @Override
        public Optional<Instant> lastSubmittedAt(long userId) {
            calls.add("submissions.lastSubmittedAt");
            return Optional.empty();
        }

        @Override
        public boolean markJudging(long submissionId, int attempt) {
            calls.add("submissions.markJudging");
            judgingAttempt = attempt;
            return true;
        }

        @Override
        public boolean markDone(long submissionId, int attempt, JudgeOutcome outcome, Instant at) {
            calls.add("submissions.markDone");
            if (!markDoneKhop) {
                return false;
            }
            doneOutcome = outcome;
            return true;
        }

        @Override
        public int markQueued(Collection<Long> submissionIds) {
            calls.add("submissions.markQueued");
            requeued = submissionIds;
            return submissionIds.size();
        }
    final java.util.List<String> daAn = new java.util.ArrayList<>();

    @Override
    public boolean datAn(long submissionId, boolean an, long adminId, java.time.Instant luc) {
        String khoa = submissionId + ":" + an;
        if (daAn.contains(khoa)) {
            return false;
        }
        daAn.add(khoa);
        return true;
    }

}
