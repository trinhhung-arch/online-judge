package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeQueueRepository.ExhaustedJob;
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

    public ReapStaleJobsUseCase(JudgeQueueRepository queue, SubmissionRepository submissions,
                                RecordJudgeResultUseCase verdicts, AppProperties properties,
                                @Qualifier("judgeTransactionManager") PlatformTransactionManager tx) {
        this.queue = queue;
        this.submissions = submissions;
        this.verdicts = verdicts;
        this.config = properties.judge();
        this.judgeTx = new TransactionTemplate(tx);
    }

    public int reap() {
        return thaLai() + boCuoc();
    }

    private int thaLai() {
        List<Long> reclaimed = judgeTx.execute(status -> {
            List<Long> ids = queue.reapExpired(config.maxReaps());
            if (!ids.isEmpty()) {
                submissions.markQueued(ids);
            }
            return ids;
        });
        if (reclaimed == null || reclaimed.isEmpty()) {
            return 0;
        }
        log.warn("Reaper thu hồi {} bài quá hạn lease, đã đưa về QUEUED: {}",
                reclaimed.size(), reclaimed);
        return reclaimed.size();
    }

    private int boCuoc() {
        int daGhi = 0;
        for (ExhaustedJob bai : queue.exhaustedJobs(config.maxReaps())) {
            RecordJudgeResultUseCase.Outcome kq = verdicts.boCuoc(bai.submissionId(),
                    bai.attempt(), config.maxReaps() + 1, bai.claimedAt());
            if (kq == RecordJudgeResultUseCase.Outcome.RECORDED) {
                daGhi++;
            }
        }
        return daGhi;
    }
}
