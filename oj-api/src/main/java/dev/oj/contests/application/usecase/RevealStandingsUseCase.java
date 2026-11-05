package dev.oj.contests.application.usecase;

import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class RevealStandingsUseCase {

    private final ContestRepository contests;
    private final StandingsEventBus bus;
    private final AuditLog auditLog;
    private final Clock clock;

    public RevealStandingsUseCase(ContestRepository contests, StandingsEventBus bus,
                                  AuditLog auditLog, Clock clock) {
        this.contests = contests;
        this.bus = bus;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public void thucHien(long contestId) {
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);
        if (!contest.daKetThuc(clock.instant())) {
            throw ContestsException.chuaKetThuc();
        }
        if (contest.daCongBo()) {
            return;
        }

        contests.congBo(contestId, clock.instant());
        bus.bangDaDoi(contestId);
        auditLog.ghi("CONTEST_STANDINGS_REVEALED", "contest", contestId,
                Map.of("slug", contest.slug()));
    }
}
