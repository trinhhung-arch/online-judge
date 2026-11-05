package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

@RequiresRole
@Service
public class RegisterForContestUseCase {

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final AuditLog auditLog;
    private final Clock clock;

    public RegisterForContestUseCase(CurrentUserProvider currentUser, ContestRepository contests,
                                     AuditLog auditLog, Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public void thucHien(long contestId) {
        long userId = currentUser.current().id();
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        if (!contest.chuaMo(clock.instant())) {
            throw ContestsException.dangKyDaDong();
        }
        contests.dangKy(contestId, userId, clock.instant());
        auditLog.ghi("CONTEST_REGISTERED", "contest", contestId,
                Map.of("slug", contest.slug()));
    }
}
