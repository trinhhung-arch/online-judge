package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.application.port.StandingsRepository.DiemDaLuu;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormat;
import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.contests.domain.ContestsException;
import dev.oj.judging.application.published.JudgingQueries;
import dev.oj.judging.application.published.JudgingQueries.ScoredSubmission;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobHandler;
import dev.oj.platform.jobs.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class StandingsDriftCheckJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(StandingsDriftCheckJob.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final JudgingQueries judging;
    private final AppProperties properties;

    private final java.util.concurrent.atomic.AtomicInteger soDongLechGanNhat =
            new java.util.concurrent.atomic.AtomicInteger();

    public StandingsDriftCheckJob(ContestRepository contests, StandingsRepository standings,
                                  JudgingQueries judging, AppProperties properties,
                                  io.micrometer.core.instrument.MeterRegistry metrics) {
        this.contests = contests;
        this.standings = standings;
        this.judging = judging;
        this.properties = properties;
        io.micrometer.core.instrument.Gauge
                .builder(dev.oj.platform.metrics.OjMetrics.STANDINGS_DRIFT, soDongLechGanNhat,
                        java.util.concurrent.atomic.AtomicInteger::get)
                .description("P8 — số dòng bảng xếp hạng lệch ở lần đối soát gần nhất")
                .register(metrics);
    }

    @Override
    public JobType type() {
        return JobType.STANDINGS_DRIFT_CHECK;
    }

    @Override
    public void chay(JobContext ctx) {
        long contestId = soContestId(ctx);
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        ctx.ghiSuKien("INFO", "Tính lại bảng xếp hạng từ submissions để đối soát");
        Map<Long, DiemDaLuu> tinhLai = tinhLaiTuDau(contest, ctx);
        Map<Long, DiemDaLuu> daLuu = standings.tongKetDaLuu(contestId);

        var lech = new TreeMap<String, Object>();
        int soDongKiem = Math.max(tinhLai.size(), daLuu.size());
        int soDongLech = 0;

        for (Long userId : gopKhoa(tinhLai, daLuu)) {
            DiemDaLuu a = tinhLai.get(userId);
            DiemDaLuu b = daLuu.get(userId);
            if (a == null || !a.equals(b)) {
                soDongLech++;
                if (lech.size() < 10) {
                    lech.put(String.valueOf(userId), moTa(a, b));
                }
            }
        }

        soDongLechGanNhat.set(soDongLech);
        standings.ghiDrift(contestId, soDongKiem, soDongLech, Map.of(
                "soDongLech", soDongLech, "mau", lech));

        if (soDongLech > 0) {
            log.error("★ DRIFT: bảng xếp hạng contest {} lệch {}/{} dòng. "
                            + "Dựng lại: POST /api/v1/contests/{}/standings/rebuild (ADMIN).",
                    contestId, soDongLech, soDongKiem, contestId);
        }
        ctx.ghiSuKien(soDongLech > 0 ? "ERROR" : "INFO",
                "Đối soát xong: " + soDongLech + "/" + soDongKiem + " dòng lệch");
        ctx.tienDo(soDongKiem, soDongKiem);
    }

    private Map<Long, DiemDaLuu> tinhLaiTuDau(Contest contest, JobContext ctx) {
        Map<Long, Map<Long, KetQuaDe>> theoNguoi = new LinkedHashMap<>();
        ContestFormat format = contest.format();
        Map<Long, Integer> diemCuaDe = BaiChamKyThi.diemCuaDe(contests, contest.id());
        long sau = 0;
        int daDoc = 0;

        for (;;) {
            ctx.kiemHuy();
            List<ScoredSubmission> lo = judging.baiDaChamTrongContest(
                    contest.id(), sau, properties.contest().standingsBatchSize());
            if (lo.isEmpty()) {
                break;
            }
            for (ScoredSubmission bai : lo) {
                Map<Long, KetQuaDe> theoDe = theoNguoi.computeIfAbsent(
                        bai.userId(), k -> new LinkedHashMap<>());
                KetQuaDe truoc = theoDe.getOrDefault(bai.problemId(),
                        KetQuaDe.trong(bai.problemId()));
                theoDe.put(bai.problemId(), format.apDung(truoc,
                        BaiChamKyThi.tu(bai, diemCuaDe), contest));
                sau = Math.max(sau, bai.submissionId());
            }
            daDoc += lo.size();
            ctx.tienDo(daDoc, null);
        }

        Map<Long, DiemDaLuu> ketQua = new LinkedHashMap<>();
        theoNguoi.forEach((userId, theoDe) -> {
            var cacDe = new ArrayList<>(theoDe.values());
            var tong = format.tongHop(cacDe);
            ketQua.put(userId, new DiemDaLuu(tong.tongDiem(),
                    format.penaltyGiay(cacDe, contest), tong.soBaiDat()));
        });
        return ketQua;
    }

    private static java.util.Set<Long> gopKhoa(Map<Long, DiemDaLuu> a, Map<Long, DiemDaLuu> b) {
        var tatCa = new java.util.LinkedHashSet<>(a.keySet());
        tatCa.addAll(b.keySet());
        return tatCa;
    }

    private static Map<String, Object> moTa(DiemDaLuu tinhLai, DiemDaLuu daLuu) {
        return Map.of(
                "tinhLai", tinhLai == null ? "khong-co" : String.valueOf(tinhLai),
                "daLuu", daLuu == null ? "khong-co" : String.valueOf(daLuu));
    }

    private static long soContestId(JobContext ctx) {
        Object v = ctx.params().get("contestId");
        if (!(v instanceof Number n)) {
            throw ContestsException.khongHopLe("contest.job_thieu_tham_so",
                    "Công việc thiếu mã kỳ thi.");
        }
        return n.longValue();
    }
}
