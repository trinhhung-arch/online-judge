package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobHandler;
import dev.oj.platform.jobs.JobType;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RebuildStandingsJob implements JobHandler {

    private final ContestRepository contests;
    private final StandingsUpdater updater;
    private final StandingsEventBus bus;

    public RebuildStandingsJob(ContestRepository contests, StandingsUpdater updater,
                               StandingsEventBus bus) {
        this.contests = contests;
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

        if (!Boolean.TRUE.equals(ctx.viTriDaLuu().get("daXoa"))) {
            ctx.ghiSuKien("INFO", "Xoá bảng xếp hạng cũ và dựng lại từ đầu");
            updater.xoaTrang(contestId);
            ctx.luuViTri(Map.of("daXoa", true));
        }

        int daXuLy = 0;
        int mot;
        do {
            ctx.kiemHuy();
            mot = updater.capNhat(contest);
            daXuLy += mot;
            ctx.tienDo(daXuLy, null);
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
