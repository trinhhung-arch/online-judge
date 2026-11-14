package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeQueueRepository.ExhaustedJob;
import dev.oj.judging.application.port.JudgeQueueRepository.ReapedJob;
import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.InternalAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@InternalAccess("bộ lập lịch @Scheduled trong chính tiến trình này — lời gọi không đến từ mạng, nên không có tầng xác thực nào để đi qua.")
@Service
public class ReapStaleJobsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReapStaleJobsUseCase.class);

    private final JudgeQueueRepository queue;
    private final SubmissionRepository submissions;
    private final RecordJudgeResultUseCase verdicts;
    private final AppProperties.Judge config;
    private final TransactionTemplate judgeTx;
    private final SubmissionEventBus events;

    public ReapStaleJobsUseCase(JudgeQueueRepository queue, SubmissionRepository submissions,
                                RecordJudgeResultUseCase verdicts, AppProperties properties,
                                SubmissionEventBus events,
                                @Qualifier("judgeTransactionManager") PlatformTransactionManager tx) {
        this.queue = queue;
        this.submissions = submissions;
        this.verdicts = verdicts;
        this.config = properties.judge();
        this.judgeTx = new TransactionTemplate(tx);
        this.events = events;
    }

    public int reap() {
        return thaLai() + boCuoc();
    }

    private int thaLai() {
        List<ReapedJob> reclaimed = judgeTx.execute(status -> {
            List<ReapedJob> bai = queue.reapExpired(config.maxReaps());
            if (!bai.isEmpty()) {
                submissions.markQueued(bai.stream().map(ReapedJob::submissionId).toList());
            }
            return bai;
        });
        if (reclaimed == null || reclaimed.isEmpty()) {
            return 0;
        }
        log.warn("Reaper thu hồi {} bài quá hạn lease, đã đưa về QUEUED: {}",
                reclaimed.size(), reclaimed.stream().map(ReapedJob::submissionId).toList());
        reclaimed.forEach(b -> events.publish(SubmissionEvent.queued(b.submissionId(), b.attempt())));
        return reclaimed.size();
    }

    private int boCuoc() {
        int daGhi = 0;
        for (ExhaustedJob bai : queue.exhaustedJobs(config.maxReaps())) {
            try {
                RecordJudgeResultUseCase.Outcome kq = verdicts.boCuoc(bai.submissionId(),
                        bai.attempt(), config.maxReaps() + 1, bai.claimedAt());
                if (kq == RecordJudgeResultUseCase.Outcome.RECORDED) {
                    daGhi++;
                }
            } catch (RuntimeException e) {
                log.error("Bỏ cuộc submission {} attempt {} hỏng — các bài khác vẫn được xử lý, "
                        + "bài này thử lại ở chu kỳ sau", bai.submissionId(), bai.attempt(), e);
            }
        }
        return daGhi;
    }
}
