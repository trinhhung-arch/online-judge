package dev.oj.judging.application.usecase;

import dev.oj.judging.application.RejudgeJobHandler;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.settings.SystemSettings;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import org.springframework.stereotype.Service;

import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class StartRejudgeUseCase {

    private final CurrentUserProvider currentUser;
    private final GetProblemUseCase problems;
    private final ContestWindowQuery lichThi;
    private final SystemSettings congTac;
    private final JobRepository jobs;
    private final AuditLog auditLog;

    public StartRejudgeUseCase(CurrentUserProvider currentUser, GetProblemUseCase problems,
                               ContestWindowQuery lichThi, SystemSettings congTac,
                               JobRepository jobs, AuditLog auditLog) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.lichThi = lichThi;
        this.congTac = congTac;
        this.jobs = jobs;
        this.auditLog = auditLog;
    }

    public long batDau(long problemId) {
        long adminId = currentUser.current().id();

        problems.submittableById(problemId);

        if (!congTac.bat(SystemSettings.REJUDGE, true)) {
            throw JudgingException.rejudgeDaTat();
        }
        if (lichThi.coKyThiDangChay()) {
            throw JudgingException.rejudgeTrongKyThi();
        }

        long jobId = jobs.tao(JobType.REJUDGE, Map.of(RejudgeJobHandler.THAM_SO_DE, problemId),
                adminId);

        auditLog.ghi("REJUDGE_STARTED", "problem", problemId, Map.of("jobId", jobId));
        return jobId;
    }
}
