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

/**
 * FR-SUB-09 — ADMIN ẩn một bài nộp. <b>Ẩn, không xoá.</b> Bước 6.13.
 *
 * <h2>Vì sao không có xoá, dù yêu cầu nghe rất giống</h2>
 * Lý do thường được nêu là "giữ lịch sử", và nó không phải lý do mạnh nhất. Lý do mạnh nhất là
 * <b>bảng xếp hạng của những kỳ thi đã kết thúc</b>: {@code contest_standings} được tính từ
 * {@code submissions}, và {@code RebuildStandingsJob} phải dựng lại được y hệt bảng cũ từ
 * Postgres (FR-CON-08). Xoá một bài nộp là làm cho một kỳ thi đã trao giải không dựng lại được
 * nữa — và không ai phát hiện ra cho tới ngày Redis chết.
 *
 * <p>Ẩn thì không đụng tới điều đó: điểm vẫn còn, thứ hạng vẫn còn, chỉ bài không hiện ra trong
 * danh sách. {@code listForUser} đã lọc {@code hidden_at IS NULL} từ M1.
 *
 * <h2>Ba lớp cho cùng một bảo đảm</h2>
 * <pre>
 *   interface  SubmissionRepository không có phương thức delete
 *   V8         REVOKE DELETE ON submissions FROM oj_app
 *   FR-SUB-09  yêu cầu nghiệp vụ, ghi ở frplan
 * </pre>
 * Lớp thứ hai là lớp duy nhất không bỏ qua được bằng một câu SQL viết tay.
 */
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

    /**
     * @param an {@code true} = ẩn, {@code false} = hiện lại
     * @throws JudgingException {@code NOT_FOUND} nếu bài không tồn tại <b>hoặc đã ở đúng trạng
     *         thái ấy</b> — gộp hai trường hợp vì với ADMIN thì cả hai dẫn tới cùng một việc:
     *         không có gì để làm
     */
    public void dat(long submissionId, boolean an) {
        long adminId = currentUser.current().id();
        if (!submissions.datAn(submissionId, an, adminId, clock.instant())) {
            throw JudgingException.submissionNotFound(submissionId);
        }
        auditLog.ghi(an ? "SUBMISSION_HIDDEN" : "SUBMISSION_UNHIDDEN",
                "submission", submissionId, Map.of());
    }
}
