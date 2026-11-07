package dev.oj.problems.application.port;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public interface ProblemAuthoringRepository {

    Optional<Problem> findForAuthor(String code, long requesterId, boolean laAdmin);

    Optional<Problem> findForAuthorById(long id, long requesterId, boolean laAdmin);

    long taoMoi(NewProblem problem);

    boolean capNhat(long id, ProblemEdit sua, long requesterId, boolean laAdmin);

    boolean coBaiNop(long problemId);

    boolean xoa(long id, long requesterId, boolean laAdmin);

    boolean doiTrangThai(long id, ProblemStatus moi, long requesterId, boolean laAdmin,
                         Instant luc);

    record NewProblem(
            String code, String title, String statementMd, String statementHash,
            int timeLimitMs, int memoryLimitKb,
            CheckerType checkerType, BigDecimal checkerEpsilon,
            ScoringMode scoringMode, FeedbackLevel feedbackLevel,
            long ownerId, boolean allowPublicSolutions) {
    }

    record ProblemEdit(
            String title, String statementMd, String statementHash,
            int timeLimitMs, int memoryLimitKb,
            CheckerType checkerType, BigDecimal checkerEpsilon,
            ScoringMode scoringMode, FeedbackLevel feedbackLevel,
            boolean allowPublicSolutions) {
    }
}
