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

/**
 * Tiến độ của một job — Quy tắc 5 của {@code frplan.md}: <i>job nền phải CÓ TIẾN ĐỘ</i>.
 *
 * <p>Không có endpoint này thì "job nền" chỉ là "một thao tác không bao giờ trả lời", và
 * người dùng sẽ bấm lại — tạo ra đúng thứ {@code ux_jobs_one_active_per_entity} phải chặn.
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
    /**
     * Việc gần đây của người gọi — ADMIN thấy của mọi người.
     *
     * <p>Phân trang cursor như mọi danh sách khác (bất biến #8). Bản đầu chỉ nhận
     * {@code gioiHan}: một trần thì chặn được câu query nặng, nhưng không phải là phân trang —
     * việc thứ 21 trở đi không có đường nào tới được.
     */
    public CursorPage<Job> cuaToi(String cursor, int gioiHan) {
        var nguoiGoi = currentUser.current();
        List<Job> dong = jobs.ganDay(nguoiGoi.isAdmin() ? null : nguoiGoi.id(),
                docCursor(cursor), gioiHan + 1);
        return CursorPage.of(dong, gioiHan, j -> String.valueOf(j.id()));
    }

    /** Cursor rác trả về trang đầu — cùng lập luận đã ghi ở {@code ListContestsUseCase}. */
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
