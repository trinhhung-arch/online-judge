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

/**
 * ★ Đối soát bảng xếp hạng đã denormalize — FR-CON-09. Bước 5.10.
 *
 * <h2>Vì sao một hệ thống đúng vẫn cần job này</h2>
 * {@code contest_standings} là dữ liệu <b>suy ra</b>: nó được tính từ {@code submissions} bởi
 * {@code StandingsUpdater}. Mọi dữ liệu suy ra đều có thể lệch khỏi nguồn, và ở đây có ít nhất
 * ba đường:
 *
 * <ul>
 *   <li>Một bài nộp bị rejudge, verdict đổi, nhưng {@code id} thì không — nên watermark đã
 *       vượt qua nó và bảng giữ kết quả cũ.</li>
 *   <li>Một lô bị bỏ sót vì watermark nhảy (chỉ xảy ra nếu thứ tự id bị phá, nhưng "chỉ xảy
 *       ra nếu" là thứ job này tồn tại để kiểm chứng).</li>
 *   <li>Một lỗi trong chính thể thức, sửa sau khi kỳ thi đã chạy.</li>
 * </ul>
 *
 * <p>Không có job này thì lệch <b>không bao giờ được phát hiện</b> — bảng xếp hạng vẫn hiện
 * ra, vẫn trông hợp lý, và không ai kiểm lại một thứ hạng.
 *
 * <h2>Nó CHỈ đo, không tự sửa</h2>
 * Phát hiện lệch thì ghi vào {@code standings_drift_checks} và log ở mức ERROR. Sửa là việc
 * của {@link RebuildStandingsJob}, và người bấm là người tổ chức.
 *
 * <p>Tự sửa nghe hấp dẫn nhưng sai: nếu job này viết đè bảng xếp hạng giữa kỳ thi thì thứ
 * hạng đổi mà không ai biết vì sao — và nếu chính nó tính sai thì nó vừa phá bảng đúng. Một
 * công cụ đo phải là công cụ đo.
 */
@Component
public class StandingsDriftCheckJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(StandingsDriftCheckJob.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final JudgingQueries judging;
    private final AppProperties properties;

    public StandingsDriftCheckJob(ContestRepository contests, StandingsRepository standings,
                                  JudgingQueries judging, AppProperties properties) {
        this.contests = contests;
        this.standings = standings;
        this.judging = judging;
        this.properties = properties;
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
                // Chỉ ghi mười dòng đầu: cột `detail` là JSONB và một kỳ thi lệch toàn bộ sẽ
                // ghi vài megabyte vào một bảng dùng để CẢNH BÁO.
                if (lech.size() < 10) {
                    lech.put(String.valueOf(userId), moTa(a, b));
                }
            }
        }

        standings.ghiDrift(contestId, soDongKiem, soDongLech, Map.of(
                "soDongLech", soDongLech, "mau", lech));

        if (soDongLech > 0) {
            // ERROR chứ không WARN: bảng xếp hạng lệch là một sự cố về tính công bằng, và nó
            // phải đi vào cùng đường cảnh báo với "mất bài nộp".
            log.error("★ DRIFT: bảng xếp hạng contest {} lệch {}/{} dòng. "
                            + "Chạy RebuildStandingsJob để dựng lại.",
                    contestId, soDongLech, soDongKiem);
        }
        ctx.ghiSuKien(soDongLech > 0 ? "ERROR" : "INFO",
                "Đối soát xong: " + soDongLech + "/" + soDongKiem + " dòng lệch");
        ctx.tienDo(soDongKiem, soDongKiem);
    }

    /**
     * Tính lại từ {@code submissions}, <b>không đụng vào bảng xếp hạng</b>.
     *
     * <p>Dùng chính {@link ContestFormat} mà đường thường dùng — nếu tính bằng một hiện thực
     * khác thì job này đang so hai thứ khác nhau và luôn báo lệch.
     */
    private Map<Long, DiemDaLuu> tinhLaiTuDau(Contest contest, JobContext ctx) {
        Map<Long, Map<Long, KetQuaDe>> theoNguoi = new LinkedHashMap<>();
        ContestFormat format = contest.format();
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
                theoDe.put(bai.problemId(), format.apDung(truoc, new ContestFormat.BaiDaCham(
                        bai.submissionId(), bai.userId(), bai.problemId(),
                        bai.laAc(), bai.score(), bai.nopLuc()), contest));
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
