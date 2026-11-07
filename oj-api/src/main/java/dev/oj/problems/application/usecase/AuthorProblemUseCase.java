package dev.oj.problems.application.usecase;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.StatementService;
import dev.oj.problems.application.port.ProblemAuthoringRepository;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemNotFoundException;
import dev.oj.problems.domain.ProblemRules;
import dev.oj.problems.domain.ProblemStatus;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

@RequiresRole(Role.SETTER)
@Service
public class AuthorProblemUseCase {

    private final CurrentUserProvider currentUser;
    private final ProblemAuthoringRepository problems;
    private final AuditLog auditLog;
    private final ContestWindowQuery lichThi;
    private final Clock clock;

    public AuthorProblemUseCase(CurrentUserProvider currentUser, ProblemAuthoringRepository problems,
                                AuditLog auditLog, ContestWindowQuery lichThi, Clock clock) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.auditLog = auditLog;
        this.lichThi = lichThi;
        this.clock = clock;
    }

    public long tao(Command lenh) {
        lenh.kiemTra(true);
        long ownerId = currentUser.current().id();
        long id = problems.taoMoi(new ProblemAuthoringRepository.NewProblem(
                lenh.code().trim(), lenh.title().trim(), lenh.statementMd(),
                StatementService.bam(lenh.statementMd()),
                lenh.timeLimitMs(), lenh.memoryLimitKb(),
                lenh.checkerType(), lenh.checkerEpsilon(),
                lenh.scoringMode(), lenh.feedbackLevel(), ownerId,
                lenh.allowPublicSolutions()));
        auditLog.ghi("PROBLEM_CREATED", "problem", id, Map.of("code", lenh.code().trim()));
        return id;
    }

    public void sua(long problemId, Command lenh) {
        lenh.kiemTra(false);
        camSuaKhiDangThi(problemId);
        var nguoiGoi = currentUser.current();
        boolean daSua = problems.capNhat(problemId, new ProblemAuthoringRepository.ProblemEdit(
                        lenh.title().trim(), lenh.statementMd(),
                        StatementService.bam(lenh.statementMd()),
                        lenh.timeLimitMs(), lenh.memoryLimitKb(),
                        lenh.checkerType(), lenh.checkerEpsilon(),
                        lenh.scoringMode(), lenh.feedbackLevel(), lenh.allowPublicSolutions()),
                nguoiGoi.id(), nguoiGoi.isAdmin());
        if (!daSua) {
            throw ProblemNotFoundException.byId(problemId);
        }
        auditLog.ghi("PROBLEM_UPDATED", "problem", problemId,
                Map.of("feedbackLevel", lenh.feedbackLevel().name()));
    }

    public void xuatBan(long problemId) {
        Problem de = doc(problemId);
        if (de.currentTestdataVersion() <= 0) {
            throw ProblemsException.chuaCoTestdata();
        }
        doiTrangThai(problemId, ProblemStatus.PUBLISHED);
    }

    public void goXuong(long problemId) {
        doc(problemId);
        doiTrangThai(problemId, ProblemStatus.RETIRED);
    }

    public void xoa(long problemId) {
        Problem de = doc(problemId);
        if (problems.coBaiNop(problemId)) {
            throw ProblemsException.daCoBaiNop();
        }
        if (lichThi.deNamTrongKyThiNaoDo(problemId)) {
            throw ProblemsException.dangThuocKyThi();
        }
        var nguoiGoi = currentUser.current();
        if (!problems.xoa(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())) {
            throw ProblemNotFoundException.byId(problemId);
        }
        auditLog.ghi("PROBLEM_DELETED", "problem", problemId,
                Map.of("code", de.code(), "title", de.title()));
    }

    private void camSuaKhiDangThi(long problemId) {
        if (lichThi.deDangTrongContestDangChay(problemId)) {
            throw ProblemsException.dangTrongKyThi();
        }
    }

    public Problem doc(long problemId) {
        var nguoiGoi = currentUser.current();
        return problems.findForAuthorById(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(() -> ProblemNotFoundException.byId(problemId));
    }

    private void doiTrangThai(long problemId, ProblemStatus moi) {
        var nguoiGoi = currentUser.current();
        if (!problems.doiTrangThai(problemId, moi, nguoiGoi.id(), nguoiGoi.isAdmin(),
                clock.instant())) {
            throw ProblemNotFoundException.byId(problemId);
        }
        auditLog.ghi("PROBLEM_STATUS_CHANGED", "problem", problemId,
                Map.of("status", moi.name()));
    }

    public record Command(
            String code, String title, String statementMd,
            int timeLimitMs, int memoryLimitKb,
            CheckerType checkerType, BigDecimal checkerEpsilon,
            ScoringMode scoringMode, FeedbackLevel feedbackLevel,
            boolean allowPublicSolutions) {

        void kiemTra(boolean laTaoMoi) {
            if (laTaoMoi) {
                ProblemRules.kiemTraMaDe(code);
            }
            ProblemRules.kiemTraTieuDe(title);
            ProblemRules.kiemTraDeBai(statementMd);
            ProblemRules.kiemTraGioiHan(timeLimitMs, memoryLimitKb);
            ProblemRules.kiemTraChecker(checkerType, checkerEpsilon);
            if (scoringMode == null || feedbackLevel == null || checkerType == null) {
                throw ProblemsException.khongHopLe("problem.thieu_truong",
                        "Thiếu kiểu checker, cách tính điểm hoặc mức phản hồi.");
            }
        }
    }
}
