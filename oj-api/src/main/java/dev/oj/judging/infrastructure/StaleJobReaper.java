package dev.oj.judging.infrastructure;

import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StaleJobReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleJobReaper.class);

    private final ReapStaleJobsUseCase reapStaleJobs;

    public StaleJobReaper(ReapStaleJobsUseCase reapStaleJobs) {
        this.reapStaleJobs = reapStaleJobs;
    }

    @Scheduled(fixedDelayString = "${oj.judge.reaper-interval}")
    public void reap() {
        try {
            reapStaleJobs.reap();
        } catch (Exception e) {
            log.error("Lượt reap thất bại — sẽ thử lại ở chu kỳ sau", e);
        }
    }
}
