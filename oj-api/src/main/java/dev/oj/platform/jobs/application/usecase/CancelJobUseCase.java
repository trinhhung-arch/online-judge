package dev.oj.platform.jobs.application.usecase;

import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Huỷ một job đang chờ hoặc đang chạy.
 *
 * <h2>Huỷ là một YÊU CẦU, không phải một lệnh dừng tức thì</h2>
 * Use-case này chỉ đặt trạng thái {@code CANCELLED}. Handler đang chạy sẽ thấy nó ở lần gọi
 * {@code ctx.kiemHuy()} kế tiếp rồi tự dừng. Không có cách nào giết một luồng đang giữa chừng
 * mà không để lại dữ liệu dở dang — và với một job đang ghi testcase thì dữ liệu dở dang là
 * thứ tệ hơn hẳn việc phải chờ thêm vài giây.
 *
 * <p>Hệ quả: handler nào không gọi {@code kiemHuy()} thì chạy tới hết. Đó là hợp đồng viết
 * trong {@code JobHandler}, và là lý do nó được viết ra thành chữ.
 */
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
        // Kiểm quyền bằng chính câu query đọc — job của người khác trả về rỗng, và ta ném
        // NOT_FOUND chứ không FORBIDDEN (xem JobRepository.timChoNguoiGoi).
        jobs.timChoNguoiGoi(jobId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(JobsException::khongTimThay);
        jobs.huy(jobId, clock.instant());
    }
}
