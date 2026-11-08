package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class StartStandingsRebuildUseCase {

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final JobRepository jobs;
    private final AuditLog auditLog;
    private final Clock clock;

    public StartStandingsRebuildUseCase(CurrentUserProvider currentUser,
                                        ContestRepository contests, JobRepository jobs,
                                        AuditLog auditLog, Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.jobs = jobs;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public long batDau(long contestId) {
        long adminId = currentUser.current().id();
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);
        if (!contest.daKetThuc(clock.instant())) {
            throw ContestsException.chuaKetThuc();
        }

        long jobId = jobs.tao(JobType.LEADERBOARD_REBUILD, Map.of("contestId", contestId),
                adminId);

        auditLog.ghi("STANDINGS_REBUILD_STARTED", "contest", contestId, Map.of("jobId", jobId));
        return jobId;
    }
}
