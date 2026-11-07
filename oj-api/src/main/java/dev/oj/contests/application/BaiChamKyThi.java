package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.ContestFormat.BaiDaCham;
import dev.oj.judging.application.published.JudgingQueries.ScoredSubmission;

import java.util.HashMap;
import java.util.Map;

final class BaiChamKyThi {

    private BaiChamKyThi() {
    }

    static Map<Long, Integer> diemCuaDe(ContestRepository contests, long contestId) {
        Map<Long, Integer> diem = new HashMap<>();
        contests.deCua(contestId).forEach(de -> diem.put(de.problemId(), de.points()));
        return diem;
    }

    static BaiDaCham tu(ScoredSubmission bai, Map<Long, Integer> diemCuaDe) {
        return new BaiDaCham(bai.submissionId(), bai.userId(), bai.problemId(), bai.verdict(),
                bai.score(), bai.maxScore(), diemCuaDe.getOrDefault(bai.problemId(), 0),
                bai.nopLuc());
    }
}
