package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class FreezeStandingsScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreezeStandingsScheduler.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final Clock clock;

    public FreezeStandingsScheduler(ContestRepository contests, StandingsRepository standings,
                                    Clock clock) {
        this.contests = contests;
        this.standings = standings;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 5000)
    public void nhip() {
        try {
            for (Contest c : contests.canDongBang(clock.instant())) {
                chup(c);
            }
        } catch (RuntimeException e) {
            log.error("Nhịp đóng băng hỏng — nuốt để bộ lập lịch không huỷ tác vụ", e);
        }
    }

    private void chup(Contest contest) {
        if (standings.daChupDongBang(contest.id())) {
            return;
        }
        standings.chupDongBang(contest.id(), contest.freezeAt());
        log.info("Đã đóng băng bảng xếp hạng contest {} tại {}",
                contest.id(), contest.freezeAt());
    }
}
