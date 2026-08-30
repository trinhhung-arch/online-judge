package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ Cập nhật bảng xếp hạng <b>theo lô</b> — FR-CON-04, P8. Bước 5.6.
 *
 * <h2>Vì sao theo lô mỗi 2 giây chứ không phải mỗi verdict</h2>
 * {@code oj-api/CLAUDE.md} mục 6 nói thẳng con số. Lý do là số học: 500 người nộp cùng lúc ở
 * phút cuối là 500 lần đọc-tính-ghi lại bảng xếp hạng, mỗi lần chạm cùng những dòng — tức là
 * 500 lần tranh khoá trên một handful hàng, <i>đúng vào lúc</i> hệ thống bận nhất.
 *
 * <p>Gom lại thành một lô: một lượt đọc, một lượt tính, một lượt ghi. Người dùng thấy bảng
 * chậm tối đa hai giây, và hai giây là thứ không ai nhận ra trong một kỳ thi năm tiếng.
 *
 * <h2>★ Idempotent qua watermark — Quy tắc 4</h2>
 * {@code last_applied_submission_id} là ranh giới đã xử lý. Mỗi nhịp chỉ đọc bài nộp có
 * {@code id} lớn hơn nó, và bài nộp được xử lý <b>đúng theo thứ tự id</b>. Nhờ vậy:
 *
 * <ul>
 *   <li>Chạy lại một lô đã chạy không đổi gì — thể thức cũng thuần ({@link ContestFormat}).</li>
 *   <li>Tiến trình chết giữa chừng thì lô đó chưa commit, watermark chưa tiến, và nhịp sau
 *       làm lại từ đúng chỗ ấy.</li>
 * </ul>
 *
 * <h2>Cả lô nằm trong MỘT transaction, và đó là điều bắt buộc</h2>
 * Watermark của kỳ thi là {@code MAX} qua các dòng theo người. Nếu ghi được dòng của người B
 * (id 20) mà hỏng ở dòng người A (id 10), watermark thành 20 và <b>bài id 10 vĩnh viễn không
 * vào bảng</b> — một lỗi im lặng mà chỉ job đối soát drift (FR-CON-09) tìm ra.
 *
 * <p>Một transaction làm cho hai kết cục duy nhất là "cả lô vào" hoặc "cả lô chưa vào".
 *
 * <h2>Nuốt ngoại lệ ở tầng ngoài — cùng lý do với {@code StaleJobReaper}</h2>
 * Spring huỷ hẳn một tác vụ {@code @Scheduled} nếu nó ném ra ngoài. Một kỳ thi có dữ liệu lạ
 * sẽ làm bảng xếp hạng của <b>mọi</b> kỳ thi khác đứng im cho tới lần deploy sau.
 */
@Component
public class StandingsUpdater {

    private static final Logger log = LoggerFactory.getLogger(StandingsUpdater.class);

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final JudgingQueries judging;
    private final StandingsEventBus publisher;
    private final AppProperties properties;
    private final java.time.Clock clock;
    private final TransactionTemplate tx;

    public StandingsUpdater(ContestRepository contests, StandingsRepository standings,
                            JudgingQueries judging, StandingsEventBus publisher,
                            AppProperties properties, java.time.Clock clock,
                            @Qualifier("appTransactionManager") PlatformTransactionManager txManager) {
        this.contests = contests;
        this.standings = standings;
        this.judging = judging;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${oj.contest.standings-interval}")
    public void nhip() {
        try {
            for (Contest c : contests.canCapNhat(clock.instant(),
                    properties.contest().standingsGrace())) {
                capNhat(c);
            }
        } catch (RuntimeException e) {
            log.error("Nhịp StandingsUpdater hỏng — nuốt để bộ lập lịch không huỷ tác vụ", e);
        }
    }

    /**
     * Xử lý <b>một</b> lô cho một kỳ thi.
     *
     * <p>Trả về số bài đã tính, để {@code RebuildStandingsJob} gọi lặp cho tới khi hết và báo
     * tiến độ. Public vì chính lý do đó — không phải để test gọi.
     */
    public int capNhat(Contest contest) {
        long watermark = standings.watermark(contest.id());
        List<ScoredSubmission> lo = judging.baiDaChamTrongContest(
                contest.id(), watermark, properties.contest().standingsBatchSize());
        if (lo.isEmpty()) {
            return 0;
        }

        Set<Long> nguoiBiCham = new LinkedHashSet<>();
        for (ScoredSubmission bai : lo) {
            nguoiBiCham.add(bai.userId());
        }

        Boolean daGhi = tx.execute(status -> {
            ghiLo(contest, lo, nguoiBiCham);
            return Boolean.TRUE;
        });

        if (Boolean.TRUE.equals(daGhi)) {
            // Đẩy tin SAU khi commit. Trước commit thì một client đọc lại ngay có thể thấy
            // bảng cũ — cùng cái bẫy mà AfterCommit giải ở M3.
            publisher.bangDaDoi(contest.id());
        }
        return lo.size();
    }

    private void ghiLo(Contest contest, List<ScoredSubmission> lo, Set<Long> nguoiBiCham) {
        // Đọc TOÀN BỘ kết quả từng đề của những người bị chạm: tổng điểm và penalty là hàm
        // của TẤT CẢ các đề, nên tính lại mà thiếu một đề là ra một con số sai âm thầm.
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
                    bai.laAc(), bai.score(), bai.nopLuc()), contest);
            theoDe.put(bai.problemId(), sau);

            deBiCham.add(khoaDe(bai.userId(), bai.problemId()));
            baiCuoiCuaNguoi.merge(bai.userId(), bai.submissionId(), Math::max);
        }

        // ★ THỨ TỰ BẮT BUỘC: dòng tổng TRƯỚC, dòng theo đề SAU.
        //
        // `contest_problem_standings` có khoá ngoại tổng hợp (contest_id, user_id) trỏ tới
        // `contest_standings`. Ghi ngược lại thì lần nộp ĐẦU TIÊN của một người vỡ ràng buộc
        // — và vỡ với một thông báo về "contest_problem_standings" không hề nhắc tới bảng
        // đang thiếu dòng.
        //
        // Mọi giá trị đã tính xong trong bộ nhớ ở vòng lặp trên, nên thứ tự ghi không ảnh
        // hưởng kết quả — chỉ ảnh hưởng việc nó ghi được hay không.
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

    /**
     * Gói {@code (userId, problemId)} vào một {@code long} để dùng làm khoá tập hợp.
     *
     * <p>{@code problemId} nằm ở 32 bit thấp. Với {@code BIGINT GENERATED ALWAYS} thì hệ thống
     * này sẽ chạm hai tỉ đề rất lâu sau khi mọi thứ khác đã hỏng — nhưng nếu ngày đó tới, phép
     * gói này sai <b>im lặng</b>, nên nó được viết ra ở đây thay vì giấu trong một biểu thức.
     */
    private static long khoaDe(long userId, long problemId) {
        return (userId << 32) | (problemId & 0xFFFFFFFFL);
    }
}
