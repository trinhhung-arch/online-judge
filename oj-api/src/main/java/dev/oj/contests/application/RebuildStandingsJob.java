package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobHandler;
import dev.oj.platform.jobs.JobType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ★ Dựng lại bảng xếp hạng <b>từ Postgres</b> — FR-CON-08. Bước 5.9.
 *
 * <h2>Vì sao job này phải luôn tồn tại và luôn có test</h2>
 * {@code oj-api/CLAUDE.md} mục 6 nói thẳng: <i>"Job rebuild phải luôn tồn tại và có test. Nếu
 * bạn thêm một trường mới vào leaderboard, job rebuild phải biết dựng lại trường đó."</i>
 *
 * <p>Nó là <b>bằng chứng</b> cho bất biến của mốc này — <i>Redis là cache, Postgres là sự
 * thật</i>. Không có nó thì câu ấy chỉ là một lời nói: không ai chứng minh được rằng bảng xếp
 * hạng dựng lại được từ Postgres cho tới ngày phải dựng lại thật, và ngày đó là ngày tệ nhất
 * để phát hiện ra là không.
 *
 * <h2>Dựng lại KHÔNG có logic riêng, và đó là điểm chính</h2>
 * Nó xoá bảng rồi gọi <b>đúng</b> {@link StandingsUpdater#capNhat} mà đường thường dùng. Viết
 * một đường tính riêng cho rebuild là tạo ra hai hiện thực của cùng một quy tắc, và chúng sẽ
 * lệch nhau — lúc đó "dựng lại" cho ra một bảng <i>khác</i> bảng đang chạy, và không ai biết
 * bảng nào đúng.
 *
 * <p>Hệ quả trực tiếp: thêm một trường vào bảng xếp hạng là job này biết dựng lại nó
 * <b>miễn phí</b>, vì nó không biết trường nào tồn tại.
 *
 * <h2>Tiến độ không có tổng, và đó là câu trả lời trung thực</h2>
 * Biết tổng nghĩa là {@code COUNT(*)} trên {@code submissions} — thứ
 * {@code oj-api/CLAUDE.md} mục 3 cấm thẳng vì nó là một lần quét bảng. Nên job báo số bài đã
 * xử lý với tổng {@code null}, và UI hiện "đang chuẩn bị" thay vì một thanh tiến độ nói dối.
 */
@Component
public class RebuildStandingsJob implements JobHandler {

    private final ContestRepository contests;
    private final StandingsRepository standings;
    private final StandingsUpdater updater;
    private final StandingsEventBus bus;

    public RebuildStandingsJob(ContestRepository contests, StandingsRepository standings,
                               StandingsUpdater updater, StandingsEventBus bus) {
        this.contests = contests;
        this.standings = standings;
        this.updater = updater;
        this.bus = bus;
    }

    @Override
    public JobType type() {
        return JobType.LEADERBOARD_REBUILD;
    }

    @Override
    public void chay(JobContext ctx) {
        long contestId = soContestId(ctx);
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        // Chỉ xoá ở lần chạy ĐẦU. Job bị nhặt lại sau restart mà xoá lần nữa là vứt bỏ phần
        // đã dựng và bắt đầu lại từ đầu — đúng thứ cursor_state sinh ra để tránh.
        if (!Boolean.TRUE.equals(ctx.viTriDaLuu().get("daXoa"))) {
            ctx.ghiSuKien("INFO", "Xoá bảng xếp hạng cũ và dựng lại từ đầu");
            standings.xoaBangXepHang(contestId);
            ctx.luuViTri(Map.of("daXoa", true));
        }

        int daXuLy = 0;
        int mot;
        do {
            ctx.kiemHuy();
            mot = updater.capNhat(contest);
            daXuLy += mot;
            ctx.tienDo(daXuLy, null);   // tổng null — xem javadoc của class
        } while (mot > 0);

        bus.bangDaDoi(contestId);
        ctx.ghiSuKien("INFO", "Đã dựng lại bảng xếp hạng từ " + daXuLy + " bài nộp");
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
