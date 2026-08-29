package dev.oj.platform.jobs.api;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.jobs.application.usecase.CancelJobUseCase;
import dev.oj.platform.jobs.application.usecase.GetJobUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Theo dõi job nền — Quy tắc 5 của {@code frplan.md}.
 *
 * <h2>Vì sao polling REST chứ không phải SSE</h2>
 * {@code oj-api/CLAUDE.md} mục 4 nói rõ: <b>chỉ hai trang có SSE</b> — chi tiết bài nộp và
 * bảng xếp hạng contest — và <i>"không thêm chỗ thứ ba mà không hỏi"</i>. Một job nạp
 * testdata chạy vài phút với tiến độ đổi vài giây một lần; polling 2 giây là đủ, và nó không
 * tiêu một kết nối thường trực nào trong ngân sách 1000 kết nối SSE.
 *
 * <p>Đây cũng là một quyết định về sự đơn giản: fallback REST là bắt buộc với SSE, nên chọn
 * SSE ở đây là viết hai đường cho một trang mà một đường đã đủ.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final GetJobUseCase getJob;
    private final CancelJobUseCase cancelJob;
    private final AppProperties properties;

    public JobController(GetJobUseCase getJob, CancelJobUseCase cancelJob,
                         AppProperties properties) {
        this.getJob = getJob;
        this.cancelJob = cancelJob;
        this.properties = properties;
    }

    @GetMapping("/{jobId}")
    public JobResponse xem(@PathVariable long jobId) {
        return JobResponse.tu(getJob.thucHien(jobId));
    }

    /**
     * Bất biến #8 — danh sách nào cũng có {@code LIMIT}. Xin 1000 thì trả {@code max-size},
     * không trả lỗi ({@code oj-api/CLAUDE.md} mục 3).
     */
    @GetMapping
    public List<JobResponse> cuaToi(@RequestParam(required = false) Integer size) {
        int gioiHan = Math.min(
                size == null ? properties.page().defaultSize() : Math.max(1, size),
                properties.page().maxSize());
        return getJob.cuaToi(gioiHan).stream().map(JobResponse::tu).toList();
    }

    @PostMapping("/{jobId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void huy(@PathVariable long jobId) {
        cancelJob.thucHien(jobId);
    }
}
