package dev.oj.platform.jobs.application.usecase;

import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;

@RequiresRole(Role.SETTER)
@Service
public class CancelJobUseCase {

    private final CurrentUserProvider currentUser;
    private final JobRepository jobs;
    private final Clock clock;

    public CancelJobUseCase(CurrentUserProvider currentUser, JobRepository jobs, Clock clock) {
        this.currentUser = currentUser;
        this.jobs = jobs;
        this.clock = clock;
    }

    public void thucHien(long jobId) {
        var nguoiGoi = currentUser.current();
        jobs.timChoNguoiGoi(jobId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(JobsException::khongTimThay);
        jobs.huy(jobId, clock.instant());
    }
}
