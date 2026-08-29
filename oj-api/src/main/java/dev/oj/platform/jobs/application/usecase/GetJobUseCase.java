package dev.oj.platform.jobs.application.usecase;

import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Tiến độ của một job — Quy tắc 5 của {@code frplan.md}: <i>job nền phải CÓ TIẾN ĐỘ</i>.
 *
 * <p>Không có endpoint này thì "job nền" chỉ là "một thao tác không bao giờ trả lời", và
 * người dùng sẽ bấm lại — tạo ra đúng thứ {@code ux_jobs_one_active_per_type} phải chặn.
 */
@RequiresRole(Role.SETTER)
@Service
public class GetJobUseCase {

    /** Đủ để nhìn ra job đang làm gì, không đủ để biến bảng này thành một trang log. */
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

    /** Danh sách job của chính mình; ADMIN thấy tất cả. */
    public List<Job> cuaToi(int gioiHan) {
        var nguoiGoi = currentUser.current();
        return jobs.ganDay(nguoiGoi.isAdmin() ? null : nguoiGoi.id(), gioiHan);
    }

    public record ChiTiet(Job job, List<JobRepository.JobEvent> suKien) {
    }
}
