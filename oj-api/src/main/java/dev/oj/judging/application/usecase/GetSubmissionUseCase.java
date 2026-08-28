package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.domain.Submission;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import org.springframework.stereotype.Service;

/**
 * Xem một bài nộp — FR-SUB-03, FR-SUB-04, phục vụ {@code GET /api/v1/submissions/{id}}.
 *
 * <p>Ngắn, và mỗi dòng vắng mặt đều có lý do.
 *
 * <h2>Không có câu {@code if} nào kiểm quyền ở đây</h2>
 * Điều kiện chủ sở hữu là <b>tham số của câu query</b>
 * ({@link SubmissionRepository#findForRequester}). Đó không phải phong cách mà là yêu cầu:
 * một câu {@code if} sau khi đã load vẫn là lỗ hổng IDOR nếu câu query lấy về quá nhiều, và
 * nó sẽ lấy về quá nhiều vào ngày có người thêm một đường đọc thứ hai
 * ({@code oj-api/CLAUDE.md} mục 2).
 *
 * <p>Kết quả rỗng thành 404 với <b>đúng câu chữ như khi bài không tồn tại</b>. Trả 403 là
 * xác nhận "id này có thật" — đủ để dò ra ai đã nộp bài nào, và trong contest thì đó là
 * thông tin không được lộ.
 */
@Service
public class GetSubmissionUseCase {

    private final CurrentUserProvider currentUser;
    private final SubmissionRepository submissions;

    public GetSubmissionUseCase(CurrentUserProvider currentUser, SubmissionRepository submissions) {
        this.currentUser = currentUser;
        this.submissions = submissions;
    }

    /**
     * @throws JudgingException {@code NOT_FOUND} nếu bài không tồn tại <b>hoặc</b> người gọi
     *         không được phép thấy nó
     */
    public Submission byId(long submissionId) {
        CurrentUser user = currentUser.current();
        return submissions.findForRequester(submissionId, user.id(), user.role())
                .orElseThrow(() -> JudgingException.submissionNotFound(submissionId));
    }

    // -------------------------------------------------------------------------
    // Mọc thêm ở đây, không mọc ở controller:
    //
    //   M3  FeedbackPolicy — LỌC failedTestOrdinal theo problems.feedback_level trước khi
    //       trả ra (FR-PROB-07). Bài nộp luôn LƯU con số đó; việc người nộp có được thấy nó
    //       hay không là quyết định của đề. Đây là chỗ duy nhất được quyết.
    //   M3  compileLog của attempt gần nhất (FR-SUB-06) — đọc thêm judge_runs, và đó là
    //       output từ mã của chính người nộp nên được phép trả về cho tác giả.
    // -------------------------------------------------------------------------
}
