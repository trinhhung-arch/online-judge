package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsLock;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

@Component
public class FreezeStandingsScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreezeStandingsScheduler.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final StandingsLock khoa;
    private final Clock clock;
    private final TransactionTemplate tx;

    public FreezeStandingsScheduler(ContestRepository contests, StandingsRepository standings,
                                    StandingsLock khoa, Clock clock,
                                    @Qualifier("appTransactionManager")
                                    PlatformTransactionManager txManager) {
        this.contests = contests;
        this.standings = standings;
        this.khoa = khoa;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 5000)
    public void nhip() {
        List<Contest> canDongBang;
        try {
            canDongBang = contests.canDongBang(clock.instant());
        } catch (RuntimeException e) {
            log.error("Không đọc được danh sách kỳ thi cần đóng băng — thử lại ở nhịp sau", e);
            return;
        }
        for (Contest c : canDongBang) {
            try {
                chup(c);
            } catch (RuntimeException e) {
                log.error("Đóng băng contest {} (freeze_at {}) hỏng — đã rollback, nhịp sau chụp "
                        + "lại; chụp càng trễ càng lộ kết quả sau giờ đóng băng",
                        c.id(), c.freezeAt(), e);
            }
        }
    }

    private void chup(Contest contest) {
        Boolean vuaChup = tx.execute(status -> {
            khoa.khoaKyThi(contest.id());
            if (standings.daChupDongBang(contest.id())) {
                return false;
            }
            standings.chupDongBang(contest.id(), contest.freezeAt());
            return true;
        });
        if (Boolean.TRUE.equals(vuaChup)) {
            log.info("Đã đóng băng bảng xếp hạng contest {} tại {}",
                    contest.id(), contest.freezeAt());
        }
    }
}
