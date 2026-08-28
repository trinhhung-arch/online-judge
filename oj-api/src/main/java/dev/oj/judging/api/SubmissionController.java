package dev.oj.judging.api;

import dev.oj.contract.Verdict;
import dev.oj.judging.api.dto.SubmissionAcceptedResponse;
import dev.oj.judging.api.dto.SubmissionDetailResponse;
import dev.oj.judging.api.dto.SubmissionSummaryResponse;
import dev.oj.judging.api.dto.SubmitSolutionRequest;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionFilter;
import dev.oj.judging.application.usecase.GetSubmissionUseCase;
import dev.oj.judging.application.usecase.ListMySubmissionsUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.web.CursorPage;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-SUB-01, 02, 03, 04, 07.
 *
 * <h2>⚠️ Tiền tố {@code /api/v1} viết ĐẦY ĐỦ, không dùng {@code context-path}</h2>
 * Bản năng là đặt {@code server.servlet.context-path: /api/v1} rồi viết
 * {@code @RequestMapping("/submissions")}. <b>Đừng.</b> {@code context-path} bọc <i>toàn bộ</i>
 * ứng dụng, nên {@code /internal/judge/*} cũng bị đẩy thành {@code /api/v1/internal/judge/*} —
 * và lúc đó chúng nằm chung tiền tố với phần công khai, tức là lộ ra Cloudflare Tunnel cùng
 * nhau ({@code oj-api/CLAUDE.md} mục 5).
 *
 * <h2>Controller mỏng đến mức nhàm chán, và đó là yêu cầu</h2>
 * Nó nhận tham số, gọi use-case, đổi domain sang DTO. Nó <b>không</b> kiểm quyền (bất biến
 * #11 — việc đó ở use-case và trong câu query), không bắt ngoại lệ (đã có
 * {@code GlobalExceptionHandler}), không đọc {@code userId} từ tham số request.
 *
 * <p>Điểm cuối cùng đáng nói riêng: <b>không endpoint nào dưới đây nhận {@code userId}</b>.
 * Danh tính lấy từ {@code CurrentUserProvider} bên trong use-case. Một tham số
 * {@code ?userId=} là một endpoint đọc trộm lịch sử người khác, và nó sẽ được thí sinh phát
 * hiện trước bạn.
 */
@RestController
@RequestMapping("/api/v1/submissions")
public class SubmissionController {

    private final SubmitSolutionUseCase submitSolution;
    private final GetSubmissionUseCase getSubmission;
    private final ListMySubmissionsUseCase listMySubmissions;

    public SubmissionController(SubmitSolutionUseCase submitSolution,
                                GetSubmissionUseCase getSubmission,
                                ListMySubmissionsUseCase listMySubmissions) {
        this.submitSolution = submitSolution;
        this.getSubmission = getSubmission;
        this.listMySubmissions = listMySubmissions;
    }

    /**
     * FR-SUB-02 — <b>202, không phải 200</b>, và ngân sách 300ms (P2).
     *
     * <p>Verdict không có trong response và sẽ không bao giờ có. Kết quả đến sau qua SSE (M3)
     * hoặc qua {@link #byId(long)}.
     */
    @PostMapping
    public ResponseEntity<SubmissionAcceptedResponse> submit(
            @Valid @RequestBody SubmitSolutionRequest request) {
        var accepted = submitSolution.submit(new SubmitSolutionUseCase.Command(
                request.problemId(), request.languageCode(), request.source()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(SubmissionAcceptedResponse.from(accepted));
    }

    /**
     * FR-SUB-03, 04. Bài của người khác trả <b>404 với đúng câu chữ như khi không tồn tại</b> —
     * 403 là xác nhận "id này có thật", đủ để dò ra ai đã nộp bài nào.
     */
    @GetMapping("/{id}")
    public SubmissionDetailResponse byId(@PathVariable long id) {
        return SubmissionDetailResponse.from(getSubmission.byId(id));
    }

    /**
     * FR-SUB-07 — lịch sử của <b>chính người đang gọi</b>. Cursor-based, mặc định 20, trần 50.
     *
     * <p>Xin {@code limit=1000} thì nhận 50, <b>không nhận lỗi</b> ({@code oj-api/CLAUDE.md}
     * mục 3): từ chối chỉ khiến client phải đoán trần là bao nhiêu.
     *
     * <p>{@code verdict} khai báo kiểu {@link Verdict} chứ không phải {@code String} để Spring
     * tự đổi và tự trả 400 cho một giá trị rác — viết {@code String} rồi tự gọi
     * {@code Verdict.fromCode} sẽ ném {@code IllegalArgumentException} và thành 500.
     */
    @GetMapping
    public CursorPage<SubmissionSummaryResponse> listMine(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long problemId,
            @RequestParam(required = false) Verdict verdict,
            @RequestParam(required = false) Integer languageId) {

        var page = listMySubmissions.list(cursor, limit,
                new SubmissionFilter(problemId, verdict, languageId));

        return new CursorPage<>(
                page.items().stream().map(SubmissionSummaryResponse::from).toList(),
                page.nextCursor());
    }

    // -------------------------------------------------------------------------
    // Mọc thêm ở đây, KHÔNG mọc trong ba hàm trên:
    //
    //   M3  GET /{id}/stream  -> SubmissionSseController (file riêng, virtual threads).
    //       Trang chi tiết bổ sung compileLog + failedTestOrdinal ĐÃ LỌC qua FeedbackPolicy.
    //   M4  FR-SUB-08 rate limit -> kiểm trong use-case, ở đây chỉ nhận 429 + Retry-After
    //       do GlobalExceptionHandler dựng sẵn từ DomainException.RATE_LIMITED.
    //   M6  FR-SUB-09 ADMIN ẩn bài -> endpoint riêng, có ghi audit_log.
    // -------------------------------------------------------------------------
}
