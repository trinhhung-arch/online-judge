package dev.oj.judging.application.usecase;

import dev.oj.judging.application.RejudgeJobHandler;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.settings.SystemSettings;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * FR-ADM-01 — ADMIN bấm "chấm lại toàn bộ đề này". Bước 6.3.
 *
 * <h2>★ Nó KHÔNG chấm lại gì cả — nó tạo một job</h2>
 * Một đề có 10.000 bài nộp. Làm việc đó trong một request là vượt 5 giây, vượt xa
 * ({@code CLAUDE.md} mục 4 câu 4), và một lần đóng tab của ADMIN là mất nửa chừng công việc.
 * Quy tắc 5 của {@code frplan.md} nói thẳng: mọi thao tác có thể vượt 5 giây là job nền có
 * tiến độ và chạy tiếp được sau restart.
 *
 * <p>Trả về {@code jobId}. Người dùng theo dõi qua {@code GET /api/v1/jobs/{id}} — cùng một
 * đường mà nạp testdata đã dùng từ M4, không phải một cơ chế theo dõi thứ hai.
 *
 * <h2>Ba chốt, và cả ba đều ở đây chứ không ở controller (bất biến #11)</h2>
 * <ul>
 *   <li>Vai trò ADMIN — {@code @RequiresRole}.</li>
 *   <li>Đề phải tồn tại — hỏi qua {@code GetProblemUseCase} thay vì tin con số client gửi.
 *       Không có chốt này thì {@code POST /admin/problems/999999/rejudge} tạo một job chạy
 *       xong ngay với 0 bài, và ADMIN tưởng mình vừa chấm lại một đề.</li>
 *   <li>Không kỳ thi nào đang chạy — {@code frplan.md} mâu thuẫn 3.2.</li>
 * </ul>
 *
 * <p>{@code ux_jobs_one_active_per_entity} (V9) lo phần còn lại: một cú double click trên
 * trang admin không tạo được job thứ hai <i>cho cùng một đề</i>, và điều đó được ép bởi
 * database chứ không bởi nút bị disable. Trần 30% năng lực thì do
 * {@code RejudgeJob.suatConLai} giữ, và nó đếm toàn hệ thống — nên rejudge hai đề song song
 * vẫn không vượt ngân sách.
 */
@RequiresRole(Role.ADMIN)
@Service
public class StartRejudgeUseCase {

    private final CurrentUserProvider currentUser;
    private final GetProblemUseCase problems;
    private final ContestWindowQuery lichThi;
    private final SystemSettings congTac;
    private final JobRepository jobs;
    private final AuditLog auditLog;

    public StartRejudgeUseCase(CurrentUserProvider currentUser, GetProblemUseCase problems,
                               ContestWindowQuery lichThi, SystemSettings congTac,
                               JobRepository jobs, AuditLog auditLog) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.lichThi = lichThi;
        this.congTac = congTac;
        this.jobs = jobs;
        this.auditLog = auditLog;
    }

    /**
     * @return {@code jobs.id} để theo dõi tiến độ
     * @throws JudgingException {@code CONFLICT} khi có kỳ thi đang chạy hoặc công tắc đã tắt
     * @throws dev.oj.platform.jobs.JobsException khi đã có một job REJUDGE đang sống
     * @throws dev.oj.problems.domain.ProblemNotFoundException khi đề không tồn tại
     */
    public long batDau(long problemId) {
        long adminId = currentUser.current().id();

        // ADMIN thấy được cả đề chưa xuất bản, nên dùng nhánh dành cho người ra đề. Câu này
        // cũng là chốt "đề có thật" — xem javadoc lớp.
        problems.submittableById(problemId);

        if (!congTac.bat(SystemSettings.REJUDGE, true)) {
            throw JudgingException.rejudgeDaTat();
        }
        if (lichThi.coKyThiDangChay()) {
            throw JudgingException.rejudgeTrongKyThi();
        }

        long jobId = jobs.tao(JobType.REJUDGE, Map.of(RejudgeJobHandler.THAM_SO_DE, problemId),
                adminId);

        // FR-ADM-02: một thao tác đổi verdict của hàng nghìn bài phải để lại dấu vết. Ghi SAU
        // khi job đã tạo — ghi trước là ghi một việc có thể chưa xảy ra.
        auditLog.ghi("REJUDGE_STARTED", "problem", problemId, Map.of("jobId", jobId));
        return jobId;
    }
}
