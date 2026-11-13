package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEventListener;
import dev.oj.judging.application.usecase.GetSubmissionUseCase.VisibleSubmission;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RequiresRole
@Service
public class WatchSubmissionUseCase {

    private final GetSubmissionUseCase getSubmission;
    private final SubmissionEventBus bus;

    public WatchSubmissionUseCase(GetSubmissionUseCase getSubmission, SubmissionEventBus bus) {
        this.getSubmission = getSubmission;
        this.bus = bus;
    }

    public Watch start(long submissionId, SubmissionEventListener listener) {
        VisibleSubmission authorized = getSubmission.detailById(submissionId);
        if (authorized.submission().status() == SubmissionStatus.DONE) {
            return new Watch(eventOf(authorized.submission()), null);
        }

        AutoCloseable subscription = bus.subscribe(submissionId, loc(listener,
                authorized.submission().attempt(), authorized.revealsTestProgress()));
        try {
            return new Watch(eventOf(getSubmission.byId(submissionId)), subscription);
        } catch (RuntimeException e) {
            closeQuietly(subscription);
            throw e;
        }
    }

    private static SubmissionEventListener loc(SubmissionEventListener ra, int attemptDaThay,
                                               boolean hienTienDoTest) {
        AtomicInteger attemptMoiNhat = new AtomicInteger(attemptDaThay);
        AtomicBoolean daBaoDangCham = new AtomicBoolean();
        return event -> {
            if (event.attempt() < attemptMoiNhat.accumulateAndGet(event.attempt(), Math::max)) {
                return;
            }
            if (hienTienDoTest || event.isTerminal()) {
                ra.onEvent(event);
            } else if (!daBaoDangCham.getAndSet(true)) {
                ra.onEvent(new SubmissionEvent(event.submissionId(), event.attempt(),
                        event.status(), null, null, null, event.at()));
            }
        };
    }

    private static SubmissionEvent eventOf(Submission submission) {
        return new SubmissionEvent(
                submission.id(),
                submission.attempt(),
                submission.status().name(),
                submission.outcome() == null ? null : submission.outcome().verdict().name(),
                null, null,
                java.time.Instant.now());
    }

    private static void closeQuietly(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
        }
    }

    public record Watch(SubmissionEvent current, AutoCloseable subscription)
            implements AutoCloseable {

        public boolean alreadyFinished() {
            return subscription == null;
        }

        @Override
        public void close() {
            if (subscription != null) {
                closeQuietly(subscription);
            }
        }
    }
}
