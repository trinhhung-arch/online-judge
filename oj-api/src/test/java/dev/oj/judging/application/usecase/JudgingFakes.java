package dev.oj.judging.application.usecase;

import dev.oj.contract.Sha256;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.port.JudgeJobPublisher;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeRunRepository;
import dev.oj.judging.application.port.LanguageRepository;
import dev.oj.judging.application.port.SourceBlobRepository;
import dev.oj.judging.domain.JudgeOutcome;
import dev.oj.judging.domain.JudgeRun;
import dev.oj.judging.domain.SourceBlob;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.Role;
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
    final FakeSubmissionRepository submissions = new FakeSubmissionRepository(calls);
    final FakeQueue queue = new FakeQueue();
    final FakeJudgeRuns judgeRuns = new FakeJudgeRuns();
    final FakePublisher publisher = new FakePublisher();
    final FakeLanguages languages = new FakeLanguages();
    final PlatformTransactionManager txManager = new RecordingTxManager();

    /**
     * Bus sự kiện giả — <b>ghi lại</b> thay vì nuốt, để test khẳng định được rằng verdict có
     * đẩy ra luồng SSE. Một bus không làm gì cả thì test nào cũng xanh, kể cả ngày ai đó xoá
     * mất dòng publish.
     */
    static final class FakeEventBus implements dev.oj.judging.application.port.SubmissionEventBus {

        final java.util.List<SubmissionEvent> published = new java.util.ArrayList<>();

        @Override
        public void publish(SubmissionEvent event) {
            published.add(event);
        }

        @Override
        public AutoCloseable subscribe(long submissionId, SubmissionEventListener listener) {
            return () -> {
            };
        }
    }

    /** Tắt theo mặc định; {@link #chan} bật lên để kiểm chốt chạy TRƯỚC khi persist. */
    static final class RateLimiterGia
            implements dev.oj.judging.application.port.SubmissionRateLimiter {

        boolean chan;
        int soLanGoi;

        @Override
        public void kiemTraVaGhiNhan(long userId) {
            soLanGoi++;
            if (chan) {
                throw dev.oj.judging.domain.JudgingException.nopQuaNhanh(Duration.ofSeconds(7));
            }
        }
    }

    static AppProperties properties() {
        return dev.oj.platform.config.AppPropertiesGia.macDinh();
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

        final java.util.List<dev.oj.contract.SubtaskResultDto> subtaskResults =
                new java.util.ArrayList<>();

        @Override
        public boolean insertIfAbsent(JudgeRun run) {
            calls.add("judgeRuns.insertIfAbsent");
            inserted = run;
            return accept;
        }

        @Override
        public void insertSubtaskResults(long submissionId, int attempt,
                                         java.util.List<dev.oj.contract.SubtaskResultDto> subtasks) {
            if (!subtasks.isEmpty()) {
                calls.add("judgeRuns.insertSubtaskResults");
            }
            subtaskResults.addAll(subtasks);
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

        @Override
        public java.util.List<LanguageOption> listEnabled() {
            return java.util.List.of(new LanguageOption("cpp20", "C++", "GCC 13 / C++20"));
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
    /**
     * {@link dev.oj.platform.settings.SystemSettings} giả — mặc định <b>mọi công tắc đều bật</b>.
     *
     * <p>Mặc định "bật" chứ không "tắt": {@code SubmitSolutionUseCase} hỏi công tắc
     * {@code submissions.accepting} ở dòng đầu tiên (Bước 6.12), nên một bản giả mặc định tắt
     * sẽ làm mọi ca nộp bài đỏ với lý do không liên quan tới thứ chúng kiểm. Đây cũng là mặc
     * định của hiện thực thật khi không đọc được database — hỏng theo hướng nhận bài.
     */
    static final class CongTacGia implements dev.oj.platform.settings.SystemSettings {

        final java.util.Map<String, Boolean> giaTri = new java.util.LinkedHashMap<>();

        @Override
        public boolean bat(String khoa, boolean macDinh) {
            return giaTri.getOrDefault(khoa, true);
        }

        @Override
        public void dat(String khoa, boolean giaTriMoi, Long nguoiDoi) {
            giaTri.put(khoa, giaTriMoi);
        }
    }

}
