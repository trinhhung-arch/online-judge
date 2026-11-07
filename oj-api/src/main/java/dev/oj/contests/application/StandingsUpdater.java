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
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class StandingsUpdater {

    private static final Logger log = LoggerFactory.getLogger(StandingsUpdater.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final StandingsLock khoa;
    private final JudgingQueries judging;
    private final StandingsEventBus publisher;
    private final AppProperties properties;
    private final java.time.Clock clock;
    private final TransactionTemplate tx;

    public StandingsUpdater(ContestRepository contests, StandingsRepository standings,
                            StandingsLock khoa, JudgingQueries judging,
                            StandingsEventBus publisher,
                            AppProperties properties, java.time.Clock clock,
                            @Qualifier("appTransactionManager") PlatformTransactionManager txManager) {
        this.contests = contests;
        this.standings = standings;
        this.khoa = khoa;
        this.judging = judging;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${oj.contest.standings-interval}")
    public void nhip() {
        Instant bayGio = clock.instant();
        List<Contest> canCapNhat;
        try {
            canCapNhat = contests.canCapNhat(bayGio);
        } catch (RuntimeException e) {
            log.error("Không đọc được danh sách kỳ thi cần cập nhật — thử lại ở nhịp sau", e);
            return;
        }
        for (Contest c : canCapNhat) {
            try {
                capNhatRoiChotNeuXong(c, bayGio);
            } catch (RuntimeException e) {
                log.error("Cập nhật bảng xếp hạng contest {} hỏng — các kỳ thi khác vẫn chạy "
                        + "tiếp, contest này thử lại ở nhịp sau", c.id(), e);
            }
        }
    }

    private void capNhatRoiChotNeuXong(Contest contest, Instant bayGio) {
        boolean quaAnHan = !bayGio.isBefore(
                contest.endsAt().plus(properties.contest().standingsGrace()));
        boolean conBaiCho = quaAnHan && judging.conBaiChoChamTrongContest(contest.id());
        if (capNhat(contest) == 0 && quaAnHan && !conBaiCho) {
            contests.chotBangXepHang(contest.id(), bayGio);
            log.info("Đã chốt bảng xếp hạng contest {}: hết ân hạn, không còn bài chờ chấm",
                    contest.id());
        }
    }

    public int capNhat(Contest contest) {
        Integer soBai = tx.execute(status -> {
            khoa.khoaKyThi(contest.id());
            long watermark = standings.watermark(contest.id());
            List<ScoredSubmission> lo = judging.baiDaChamLienMach(
                    contest.id(), watermark, properties.contest().standingsBatchSize());
            if (!lo.isEmpty()) {
                ghiLo(contest, lo, nguoiBiCham(lo));
            }
            return lo.size();
        });

        int daTinh = soBai == null ? 0 : soBai;
        if (daTinh > 0) {
            publisher.bangDaDoi(contest.id());
        }
        return daTinh;
    }

    public void xoaTrang(long contestId) {
        tx.executeWithoutResult(status -> {
            khoa.khoaKyThi(contestId);
            standings.xoaBangXepHang(contestId);
        });
    }

    private static Set<Long> nguoiBiCham(List<ScoredSubmission> lo) {
        Set<Long> nguoi = new LinkedHashSet<>();
        for (ScoredSubmission bai : lo) {
            nguoi.add(bai.userId());
        }
        return nguoi;
    }

    private void ghiLo(Contest contest, List<ScoredSubmission> lo, Set<Long> nguoiBiCham) {
        Map<Long, Map<Long, KetQuaDe>> theoNguoi = new LinkedHashMap<>();
        standings.ketQuaTheoNguoi(contest.id(), nguoiBiCham).forEach((userId, danhSach) -> {
            Map<Long, KetQuaDe> theoDe = new LinkedHashMap<>();
            danhSach.forEach(kq -> theoDe.put(kq.problemId(), kq));
            theoNguoi.put(userId, theoDe);
        });

        ContestFormat format = contest.format();
        Map<Long, Long> baiCuoiCuaNguoi = new LinkedHashMap<>();
        Set<Long> deBiCham = new LinkedHashSet<>();

        for (ScoredSubmission bai : lo) {
            Map<Long, KetQuaDe> theoDe = theoNguoi.computeIfAbsent(
                    bai.userId(), k -> new LinkedHashMap<>());
            KetQuaDe truoc = theoDe.getOrDefault(bai.problemId(), KetQuaDe.trong(bai.problemId()));
            KetQuaDe sau = format.apDung(truoc, new ContestFormat.BaiDaCham(
                    bai.submissionId(), bai.userId(), bai.problemId(),
                    bai.verdict(), bai.score(), bai.nopLuc()), contest);
            theoDe.put(bai.problemId(), sau);

            deBiCham.add(khoaDe(bai.userId(), bai.problemId()));
            baiCuoiCuaNguoi.merge(bai.userId(), bai.submissionId(), Math::max);
        }

        for (Map.Entry<Long, Long> e : baiCuoiCuaNguoi.entrySet()) {
            long userId = e.getKey();
            var cacDe = new ArrayList<>(theoNguoi.get(userId).values());
            standings.ghiTongKet(contest.id(), userId,
                    format.tongHop(cacDe), format.penaltyGiay(cacDe, contest), e.getValue());
        }

        for (Long khoa : deBiCham) {
            long userId = khoa >> 32;
            long problemId = khoa & 0xFFFFFFFFL;
            standings.ghiKetQuaDe(contest.id(), userId, theoNguoi.get(userId).get(problemId));
        }
    }

    private static long khoaDe(long userId, long problemId) {
        return (userId << 32) | (problemId & 0xFFFFFFFFL);
    }
}
