package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class HideSubmissionUseCase {

    private final CurrentUserProvider currentUser;
    private final SubmissionRepository submissions;
    private final AuditLog auditLog;
    private final Clock clock;

    public HideSubmissionUseCase(CurrentUserProvider currentUser, SubmissionRepository submissions,
                                 AuditLog auditLog, Clock clock) {
        this.currentUser = currentUser;
        this.submissions = submissions;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public void dat(long submissionId, boolean an) {
        long adminId = currentUser.current().id();
        if (!submissions.datAn(submissionId, an, adminId, clock.instant())) {
            throw JudgingException.submissionNotFound(submissionId);
        }
        auditLog.ghi(an ? "SUBMISSION_HIDDEN" : "SUBMISSION_UNHIDDEN",
                "submission", submissionId, Map.of());
    }
}
