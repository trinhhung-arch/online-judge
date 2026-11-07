package dev.oj.contests.application;

import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.jobs.JobParams;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class StandingsDriftCheckScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(StandingsDriftCheckScheduler.class);

    private final StandingsRepository standings;
    private final JobRepository jobs;
    private final AppProperties properties;
    private final Clock clock;

    public StandingsDriftCheckScheduler(StandingsRepository standings, JobRepository jobs,
                                        AppProperties properties, Clock clock) {
        this.standings = standings;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${oj.contest.drift-check-interval}")
    public void nhip() {
        List<Long> canSoat;
        try {
            Instant bayGio = clock.instant();
            var cauHinh = properties.contest();
            canSoat = standings.canSoatLech(
                    bayGio,
                    bayGio.minus(cauHinh.driftCheckWindow()),
                    bayGio.minus(cauHinh.driftCheckInterval()));
        } catch (RuntimeException e) {
            log.error("Không đọc được danh sách kỳ thi cần soát lệch — thử lại ở nhịp sau", e);
            return;
        }
        for (long contestId : canSoat) {
            try {
                taoViec(contestId);
            } catch (RuntimeException e) {
                log.error("Tạo việc soát lệch cho contest {} hỏng — các kỳ thi khác vẫn được "
                        + "soát, contest này thử lại ở nhịp sau", contestId, e);
            }
        }
    }

    private void taoViec(long contestId) {
        try {
            long jobId = jobs.tao(JobType.STANDINGS_DRIFT_CHECK,
                    Map.of(JobParams.CONTEST_ID, contestId), null);
            log.debug("Đã tạo việc soát lệch #{} cho contest {}", jobId, contestId);
        } catch (JobsException e) {
            log.debug("Contest {} đã có việc soát đang chạy, bỏ qua nhịp này", contestId);
        }
    }
}
