package dev.oj.platform.jobs.application.usecase;

import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.web.CursorPage;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.util.List;

@RequiresRole(Role.SETTER)
@Service
public class GetJobUseCase {

    private static final int SO_SU_KIEN = 20;

    private final CurrentUserProvider currentUser;
    private final JobRepository jobs;

    public GetJobUseCase(CurrentUserProvider currentUser, JobRepository jobs) {
        this.currentUser = currentUser;
        this.jobs = jobs;
    }

    public ChiTiet thucHien(long jobId) {
        var nguoiGoi = currentUser.current();
        Job job = jobs.timChoNguoiGoi(jobId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(JobsException::khongTimThay);
        return new ChiTiet(job, jobs.suKienGanDay(jobId, SO_SU_KIEN));
    }

    public CursorPage<Job> cuaToi(String cursor, int gioiHan) {
        var nguoiGoi = currentUser.current();
        List<Job> dong = jobs.ganDay(nguoiGoi.isAdmin() ? null : nguoiGoi.id(),
                docCursor(cursor), gioiHan + 1);
        return CursorPage.of(dong, gioiHan, j -> String.valueOf(j.id()));
    }

    private static Long docCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ChiTiet(Job job, List<JobRepository.JobEvent> suKien) {
    }
}
