package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeProgressDto;
import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import dev.oj.platform.security.InternalAccess;
import org.springframework.stereotype.Service;

@InternalAccess("worker, qua POST /internal/judge/progress.")
@Service
public class RecordJudgeProgressUseCase {

    private final SubmissionEventBus events;

    public RecordJudgeProgressUseCase(SubmissionEventBus events) {
        this.events = events;
    }

    public void record(JudgeProgressDto progress) {
        events.publish(SubmissionEvent.progress(
                progress.submissionId(),
                progress.attempt(),
                progress.toOrdinal(),
                progress.totalTests()));
    }
}
