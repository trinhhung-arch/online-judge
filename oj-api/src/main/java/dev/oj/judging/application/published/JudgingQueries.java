package dev.oj.judging.application.published;

import java.time.Instant;
import java.util.List;

public interface JudgingQueries {

    List<ScoredSubmission> baiDaChamTrongContest(long contestId, long sauSubmissionId, int gioiHan);

    List<ScoredSubmission> baiDaChamLienMach(long contestId, long sauSubmissionId, int gioiHan);

    List<ScoredSubmission> baiDaChamCuaDe(long contestId, long problemId,
                                          long sauSubmissionId, int gioiHan);

    record ScoredSubmission(long submissionId, long userId, long problemId,
                            String verdict, int score, Instant nopLuc) {

        public boolean laAc() {
            return "AC".equals(verdict);
        }
    }
}
