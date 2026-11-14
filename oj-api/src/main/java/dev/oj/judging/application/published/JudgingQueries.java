package dev.oj.judging.application.published;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JudgingQueries {

    List<ScoredSubmission> baiDaChamTrongContest(long contestId, long sauSubmissionId, int gioiHan);

    List<ScoredSubmission> baiDaChamLienMach(long contestId, long sauSubmissionId, int gioiHan);

    List<ScoredSubmission> baiDaChamCuaDe(long contestId, long problemId,
                                          long sauSubmissionId, int gioiHan);

    List<ScoredSubmission> baiDaChamCuaNguoi(long contestId, long userId,
                                             long sauSubmissionId, int gioiHan);

    Optional<BaiTrongKyThi> kyThiCuaBai(long submissionId);

    boolean conBaiChoChamTrongContest(long contestId);

    record BaiTrongKyThi(long contestId, long userId) {
    }

    record ScoredSubmission(long submissionId, long userId, long problemId,
                            String verdict, int score, int maxScore, Instant nopLuc) {

        public boolean laAc() {
            return "AC".equals(verdict);
        }
    }
}
