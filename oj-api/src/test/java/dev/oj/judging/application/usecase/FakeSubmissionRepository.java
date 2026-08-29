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

/**
 * {@link SubmissionRepository} giả — tách khỏi {@link JudgingFakes} ở M4.
 *
 * <h2>Vì sao tách, và vì sao tách ĐÚNG cái này</h2>
 * {@code JudgingFakes} vượt trần 300 dòng của {@code CLAUDE.md} mục 7. Trong bảy fake ở đó,
 * đây là cái lớn nhất (tám phương thức, chín trường trạng thái) và là cái được dùng nhiều
 * nhất — hơn ba mươi chỗ gọi. Nó đủ phức tạp để đáng có một file riêng dù không có trần nào cả.
 *
 * <h2>{@code calls} nhận qua constructor, không phải qua inner class</h2>
 * Bản cũ là một inner class nên nó nhìn thấy thẳng trường {@code calls} của lớp bao. Chuyển
 * ra ngoài thì mối liên hệ ấy phải viết ra thành tham số — và đó là một cải thiện chứ không
 * phải cái giá: giờ nhìn vào constructor là biết fake này ghi vào đâu.
 *
 * <p><b>Mọi chỗ gọi giữ nguyên.</b> Test vẫn viết {@code fakes.submissions.inserted}; thứ đổi
 * là kiểu của trường đó, và không một dòng test nào nhắc tới kiểu ấy.
 *
 * <h2>Vì sao fake này GHI LẠI thay vì chỉ trả về</h2>
 * Chín trường trạng thái ở đây tồn tại để test khẳng định được <i>đã gọi với tham số gì</i>.
 * Ví dụ {@link #requesterId} và {@link #requesterRole}: chúng là cách kiểm rằng use-case
 * truyền danh tính người gọi xuống câu query — tức là chống IDOR nằm trong query chứ không
 * phải trong một câu {@code if} ở tầng trên ({@code oj-api/CLAUDE.md} mục 2).
 */
class FakeSubmissionRepository implements SubmissionRepository {

    private final List<String> calls;

    FakeSubmissionRepository(List<String> calls) {
        this.calls = calls;
    }

        long nextId = 101L;
        NewSubmission inserted;
        Submission found;
        JudgeOutcome doneOutcome;
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

        /** Mức phản hồi của "đề" trong test — đổi nó để kiểm FeedbackPolicy. */
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
            doneOutcome = outcome;
            return true;
        }

        @Override
        public int markQueued(Collection<Long> submissionIds) {
            calls.add("submissions.markQueued");
            requeued = submissionIds;
            return submissionIds.size();
        }
}
