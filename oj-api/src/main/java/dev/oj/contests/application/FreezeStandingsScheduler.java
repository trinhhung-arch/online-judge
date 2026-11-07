package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsLock;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormat;
import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.judging.application.published.JudgingQueries;
import dev.oj.judging.application.published.JudgingQueries.ScoredSubmission;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FreezeStandingsScheduler {

    private static final Logger log = LoggerFactory.getLogger(FreezeStandingsScheduler.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final StandingsLock khoa;
    private final JudgingQueries judging;
    private final AppProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public FreezeStandingsScheduler(ContestRepository contests, StandingsRepository standings,
                                    StandingsLock khoa, JudgingQueries judging,
                                    AppProperties properties, Clock clock,
                                    @Qualifier("appTransactionManager")
                                    PlatformTransactionManager txManager) {
        this.contests = contests;
        this.standings = standings;
        this.khoa = khoa;
        this.judging = judging;
        this.properties = properties;
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
                        + "lại", c.id(), c.freezeAt(), e);
            }
        }
    }

    private void chup(Contest contest) {
        Boolean vuaChup = tx.execute(status -> {
            khoa.khoaKyThi(contest.id());
            if (standings.daChupDongBang(contest.id())) {
                return false;
            }
            ghiBanChup(contest);
            standings.chotBanChup(contest.id(), contest.freezeAt(), clock.instant());
            return true;
        });
        if (Boolean.TRUE.equals(vuaChup)) {
            log.info("Đã đóng băng bảng xếp hạng contest {} tại {}",
                    contest.id(), contest.freezeAt());
        }
    }

    private void ghiBanChup(Contest contest) {
        ContestFormat format = contest.format();
        tinhTuBaiNopTruocDongBang(contest).forEach((userId, theoDe) -> {
            var cacDe = new ArrayList<>(theoDe.values());
            standings.ghiTongKetDongBang(contest.id(), userId, format.tongHop(cacDe),
                    format.penaltyGiay(cacDe, contest));
            cacDe.forEach(kq -> standings.ghiKetQuaDeDongBang(contest.id(), userId, kq));
        });
    }

    private Map<Long, Map<Long, KetQuaDe>> tinhTuBaiNopTruocDongBang(Contest contest) {
        Map<Long, Map<Long, KetQuaDe>> theoNguoi = new LinkedHashMap<>();
        int coLo = properties.contest().standingsBatchSize();
        long sau = 0;
        List<ScoredSubmission> lo;
        while (!(lo = judging.baiDaChamTrongContest(contest.id(), sau, coLo)).isEmpty()) {
            for (ScoredSubmission bai : lo) {
                sau = Math.max(sau, bai.submissionId());
                if (!bai.nopLuc().isBefore(contest.freezeAt())) {
                    continue;
                }
                Map<Long, KetQuaDe> theoDe = theoNguoi.computeIfAbsent(
                        bai.userId(), k -> new LinkedHashMap<>());
                KetQuaDe truoc = theoDe.getOrDefault(bai.problemId(),
                        KetQuaDe.trong(bai.problemId()));
                theoDe.put(bai.problemId(), contest.format().apDung(truoc,
                        new ContestFormat.BaiDaCham(bai.submissionId(), bai.userId(),
                                bai.problemId(), bai.verdict(), bai.score(), bai.nopLuc()),
                        contest));
            }
        }
        return theoNguoi;
    }
}
