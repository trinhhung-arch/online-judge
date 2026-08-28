package dev.oj.judging.application.usecase;

import dev.oj.contract.Sha256;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeRunRepository;
import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.judging.application.port.SourceBlobRepository;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.JudgeRun;
import dev.oj.judging.domain.SourceBlob;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Fake cho toàn bộ port của {@code judging} — không Mockito, không Spring context.
 *
 * <p>Điểm chính là {@link #calls}: mọi fake ghi tên phương thức vào cùng một danh sách, kể cả
 * {@code tx.begin} và {@code tx.commit}. Nhờ vậy test kiểm được thứ <b>quan trọng nhất</b> mà
 * một assertion trên giá trị trả về không bao giờ chạm tới được: <b>thứ tự</b>, và cụ thể là
 * việc lời gọi publish nằm ở phía nào của chữ COMMIT ({@code oj-api/CLAUDE.md} mục 1).
 */
class JudgingFakes {

    static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String SOURCE = "int main(){return 0;}";
    static final String SHA = Sha256.hexOf(SOURCE);

    final List<String> calls = new ArrayList<>();

    final FakeSourceBlobs sourceBlobs = new FakeSourceBlobs();
    final FakeSubmissions submissions = new FakeSubmissions();
    final FakeQueue queue = new FakeQueue();
    final FakeJudgeRuns judgeRuns = new FakeJudgeRuns();
    final FakePublisher publisher = new FakePublisher();
    final FakeLanguages languages = new FakeLanguages();
    final PlatformTransactionManager txManager = new RecordingTxManager();

    static AppProperties properties() {
        return new AppProperties(
                new AppProperties.Submission(65_536, Duration.ofSeconds(10)),
                new AppProperties.Judge(Duration.ofSeconds(120), Duration.ofSeconds(15),
                        2, 20, "mac-m1max-host"),
                new AppProperties.Page(20, 50),
                new AppProperties.Internal("x".repeat(32)),
                new AppProperties.Ai(5, Duration.ofSeconds(30)));
    }

    static CurrentUserProvider userIs(long id, Role role) {
        return () -> new CurrentUserProvider.CurrentUser(id, "nguoi-dung", role);
    }

    /** Bài DONE bắt buộc có outcome (ck_submissions_done) — chính domain đã bắt lỗi này. */
    static Submission submission(long id, SubmissionStatus status, int attempt) {
        boolean done = status == SubmissionStatus.DONE;
        return new Submission(id, 7L, 42L, null, 3, SHA, SOURCE.length(), NOW,
                status, attempt, 5,
                done ? outcome(Verdict.WA) : null,
                done ? NOW : null,
                null, null);
    }

    class FakeSourceBlobs implements SourceBlobRepository {
        SourceBlob saved;

        @Override
        public void saveIfAbsent(SourceBlob blob) {
            calls.add("sourceBlobs.saveIfAbsent");
            saved = blob;
        }
    }

    class FakeSubmissions implements SubmissionRepository {
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

    class FakeQueue implements JudgeQueueRepository {
        Integer enqueuedPriority;
        ClaimedJob nextClaim;
        ReleasedSubmission released = new ReleasedSubmission(101L, 1, 3, 5);
        boolean lockWins = true;
        boolean ieRetryAccepted;
        List<Long> expired = List.of();

        @Override
        public void enqueue(long submissionId, int priority) {
            calls.add("queue.enqueue");
            enqueuedPriority = priority;
        }

        @Override
        public Optional<ClaimedJob> claim(String hostName, int leaseSeconds) {
            calls.add("queue.claim");
            return Optional.ofNullable(nextClaim);
        }

        @Override
        public Optional<ReleasedSubmission> releaseWithOptimisticLock(long id, int attempt) {
            calls.add("queue.releaseWithOptimisticLock");
            return lockWins ? Optional.of(released) : Optional.empty();
        }

        @Override
        public List<Long> reapExpired() {
            calls.add("queue.reapExpired");
            return expired;
        }

        @Override
        public boolean retryIe(long submissionId, int attempt, int maxRetries) {
            calls.add("queue.retryIe");
            return ieRetryAccepted;
        }

        @Override
        public QueueStats queueDepth() {
            calls.add("queue.queueDepth");
            return new QueueStats(0, 0, null);
        }
    }

    class FakeJudgeRuns implements JudgeRunRepository {
        JudgeRun inserted;
        boolean accept = true;

        @Override
        public boolean insertIfAbsent(JudgeRun run) {
            calls.add("judgeRuns.insertIfAbsent");
            inserted = run;
            return accept;
        }
    }

    class FakePublisher implements JudgeJobPublisher {
        boolean explode;
        Long published;

        @Override
        public void publishEnqueued(long submissionId) {
            calls.add("events.publishEnqueued");
            if (explode) {
                throw new IllegalStateException("RabbitMQ chết — bài vẫn phải được nhận");
            }
            published = submissionId;
        }
    }

    class FakeLanguages implements LanguageRepository {
        Language enabled = new Language(3, "cpp20");

        @Override
        public Optional<Language> findEnabledByCode(String code) {
            calls.add("languages.findEnabledByCode");
            return Optional.ofNullable(enabled);
        }
    }

    /** Ghi lại đúng hai mốc: mở transaction và commit. Không có DB nào ở đây. */
    class RecordingTxManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            calls.add("tx.begin");
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            calls.add("tx.commit");
        }

        @Override
        public void rollback(TransactionStatus status) {
            calls.add("tx.rollback");
        }
    }

    static JudgeOutcome outcome(Verdict verdict) {
        return new JudgeOutcome(verdict, verdict.isAccepted() ? 100 : 0, 100,
                verdict.isAccepted() ? null : 7, 230, 4096);
    }

    static BigDecimal hostFactor() {
        return new BigDecimal("1.000");
    }
}
