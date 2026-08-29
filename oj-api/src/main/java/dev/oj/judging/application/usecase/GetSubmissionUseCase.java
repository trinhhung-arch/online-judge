package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.domain.Submission;
import dev.oj.platform.security.RequiresRole;
import dev.oj.problems.domain.FeedbackPolicy;
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
@RequiresRole  // sàn: phải đăng nhập. Quyền theo sở hữu nằm trong câu query — xem findDetailForRequester
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

    /**
     * ★ Bước 3.11 · FR-SUB-06 · FR-PROB-07 — trang chi tiết, <b>đã lọc</b>.
     *
     * <p>Đây là chỗ duy nhất {@link FeedbackPolicy} được áp cho đường REST. Bài nộp luôn LƯU
     * {@code failed_test_ordinal}; việc người nộp có được thấy nó hay không là quyết định của
     * đề, và nó được đưa ra ở đây — ngay trước khi dữ liệu rời khỏi hệ thống, không phải ở
     * controller và tuyệt đối không phải ở trình duyệt.
     *
     * <p>Log compiler thì <b>không</b> đi qua bộ lọc: ma trận hiển thị
     * ({@code oj-api/CLAUDE.md} mục 2) cho tác giả xem vô điều kiện, vì đó là output từ chính
     * mã của họ. Một đề mức {@code NONE} vẫn phải nói được vì sao bài không biên dịch nổi.
     */
    public VisibleSubmission detailById(long submissionId) {
        CurrentUser user = currentUser.current();
        var detail = submissions.findDetailForRequester(submissionId, user.id(), user.role())
                .orElseThrow(() -> JudgingException.submissionNotFound(submissionId));

        FeedbackPolicy policy = FeedbackPolicy.of(detail.feedbackLevel());
        Integer failedTestOrdinal = detail.submission().outcome() == null
                ? null
                : policy.failedTestOrdinal(detail.submission().outcome().failedTestOrdinal());

        return new VisibleSubmission(
                detail.submission(),
                failedTestOrdinal,
                detail.compileLog(),
                detail.isolateStatus(),
                detail.timeLimitMs(),
                detail.memoryLimitKb());
    }

    /**
     * Những gì người gọi <b>được phép</b> thấy. Mọi trường ở đây đã qua bộ lọc.
     *
     * @param isolateStatus chuỗi chẩn đoán của máy chấm. Nó ở đây <b>chỉ</b> để
     *                      {@code VerdictExplainer} rút ra một mã tín hiệu; nó chứa đường dẫn
     *                      bên trong box và không bao giờ được trả nguyên văn ra response —
     *                      có test khẳng định điều đó cho cả bảy verdict
     */
    public record VisibleSubmission(
            Submission submission,
            Integer failedTestOrdinal,
            String compileLog,
            String isolateStatus,
            int timeLimitMs,
            int memoryLimitKb) {
    }

    // -------------------------------------------------------------------------
    // Mọc thêm ở đây, không mọc ở controller:
    //
    //   M4  Nội dung test MẪU khi feedback_level = SAMPLE_DETAIL (FeedbackPolicy đã có
    //       revealsSampleDetail(); nguồn dữ liệu là bảng sample_testcase_contents).
    // -------------------------------------------------------------------------
}
