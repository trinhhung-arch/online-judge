package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.application.published.SubmissionVisibilityListener;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@RequiresRole(Role.ADMIN)
@Service
public class HideSubmissionUseCase {

    private static final Logger log = LoggerFactory.getLogger(HideSubmissionUseCase.class);

    private final CurrentUserProvider currentUser;
    private final SubmissionRepository submissions;
    private final AuditLog auditLog;
    private final Clock clock;
    private final List<SubmissionVisibilityListener> listeners;

    public HideSubmissionUseCase(CurrentUserProvider currentUser, SubmissionRepository submissions,
                                 AuditLog auditLog, Clock clock,
                                 List<SubmissionVisibilityListener> listeners) {
        this.currentUser = currentUser;
        this.submissions = submissions;
        this.auditLog = auditLog;
        this.clock = clock;
        this.listeners = List.copyOf(listeners);
    }

    public void dat(long submissionId, boolean an) {
        long adminId = currentUser.current().id();
        if (!submissions.datAn(submissionId, an, adminId, clock.instant())) {
            throw JudgingException.submissionNotFound(submissionId);
        }
        auditLog.ghi(an ? "SUBMISSION_HIDDEN" : "SUBMISSION_UNHIDDEN",
                "submission", submissionId, Map.of());
        for (SubmissionVisibilityListener l : listeners) {
            try {
                l.daDoiHienThi(submissionId);
            } catch (RuntimeException e) {
                log.error("Bài {} đã {} nhưng {} không tính lại được — đối soát bảng xếp hạng "
                        + "sẽ báo lệch, dựng lại sau giờ thi sẽ sửa", submissionId,
                        an ? "ẩn" : "hiện lại", l.getClass().getSimpleName(), e);
            }
        }
    }
}
