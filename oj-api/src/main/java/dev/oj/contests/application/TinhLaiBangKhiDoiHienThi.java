package dev.oj.contests.application;

import dev.oj.judging.application.published.JudgingQueries;
import dev.oj.judging.application.published.SubmissionVisibilityListener;
import org.springframework.stereotype.Component;

@Component
public class TinhLaiBangKhiDoiHienThi implements SubmissionVisibilityListener {

    private final JudgingQueries judging;
    private final StandingsUpdater updater;

    public TinhLaiBangKhiDoiHienThi(JudgingQueries judging, StandingsUpdater updater) {
        this.judging = judging;
        this.updater = updater;
    }

    @Override
    public void daDoiHienThi(long submissionId) {
        judging.kyThiCuaBai(submissionId)
                .ifPresent(bai -> updater.tinhLaiNguoi(bai.contestId(), bai.userId()));
    }
}
