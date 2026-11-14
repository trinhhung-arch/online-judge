package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeProgressDto;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.InternalAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@InternalAccess("worker, qua POST /internal/judge/progress.")
@Service
public class RecordJudgeProgressUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordJudgeProgressUseCase.class);

    private final JudgeQueueRepository queue;
    private final SubmissionEventBus events;
    private final AppProperties.Judge config;

    public RecordJudgeProgressUseCase(JudgeQueueRepository queue, SubmissionEventBus events,
                                      AppProperties properties) {
        this.queue = queue;
        this.events = events;
        this.config = properties.judge();
    }

    public void record(JudgeProgressDto progress) {
        if (!queue.renewLease(progress.submissionId(), progress.attempt(), config.leaseSeconds())) {
            log.debug("Bỏ qua tiến độ của submission {} attempt {} — lượt này không còn giữ lease",
                    progress.submissionId(), progress.attempt());
            return;
        }
        events.publish(SubmissionEvent.progress(
                progress.submissionId(),
                progress.attempt(),
                progress.toOrdinal(),
                progress.totalTests()));
    }
}
