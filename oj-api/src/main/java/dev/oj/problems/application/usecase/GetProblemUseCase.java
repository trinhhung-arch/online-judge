package dev.oj.problems.application.usecase;

import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.PublicAccess;
import dev.oj.problems.application.StatementService;
import dev.oj.problems.application.port.ProblemRepository;

import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemNotFoundException;
import org.springframework.stereotype.Service;

@PublicAccess("Khách xem được đề đã xuất bản — ô đầu tiên của ma trận hiển thị, oj-api/CLAUDE.md mục 2. Đề CHƯA xuất bản bị lọc trong câu query, không phải ở đây.")
@Service
public class GetProblemUseCase {

    private final StatementService statements;

    private final ProblemRepository problems;
    private final ContestWindowQuery lichThi;
    private final CurrentUserProvider currentUser;

    public String html(dev.oj.problems.domain.Problem de) {
        return statements.html(StatementService.bam(de.statementMd()), de.statementMd());
    }

    public GetProblemUseCase(ProblemRepository problems, StatementService statements,
                             ContestWindowQuery lichThi, CurrentUserProvider currentUser) {
        this.statements = statements;
        this.problems = problems;
        this.lichThi = lichThi;
        this.currentUser = currentUser;
    }

    public Problem byCode(String code) {
        if (!Problem.isValidCode(code)) {
            throw ProblemNotFoundException.byCode(code);
        }
        Problem de = problems.findPublishedByCode(code)
                .orElseThrow(() -> ProblemNotFoundException.byCode(code));
        kiemLichThi(de);
        return de;
    }

    public Problem submittableById(long id) {
        Problem problem = problems.findPublishedById(id)
                .orElseThrow(() -> ProblemNotFoundException.byId(id));
        if (!problem.hasTestdata()) {
            throw ProblemNotFoundException.noTestdata(id);
        }
        kiemLichThi(problem);
        return problem;
    }

    private void kiemLichThi(Problem de) {
        Long userId = null;
        boolean laNguoiRaDe = false;
        try {
            var nguoiGoi = currentUser.current();
            userId = nguoiGoi.id();
            laNguoiRaDe = nguoiGoi.isAdmin() || nguoiGoi.id() == de.ownerId();
        } catch (AuthorizationException e) {
            userId = null;
        }
        if (lichThi.deBiKhoaBoiLichThi(de.id(), userId, laNguoiRaDe)) {
            throw ProblemNotFoundException.byId(de.id());
        }
    }

}
