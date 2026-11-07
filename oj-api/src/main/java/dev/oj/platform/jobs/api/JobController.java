package dev.oj.platform.jobs.api;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.jobs.application.usecase.CancelJobUseCase;
import dev.oj.platform.jobs.application.usecase.GetJobUseCase;
import dev.oj.platform.web.CursorPage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    public CursorPage<JobResponse> cuaToi(@RequestParam(required = false) String cursor,
                                          @RequestParam(required = false) Integer size) {
        int gioiHan = CursorPage.clampSize(size,
                properties.page().defaultSize(), properties.page().maxSize());
        CursorPage<dev.oj.platform.jobs.Job> trang = getJob.cuaToi(cursor, gioiHan);
        return new CursorPage<>(trang.items().stream().map(JobResponse::tu).toList(),
                trang.nextCursor());
    }

    @PostMapping("/{jobId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void huy(@PathVariable long jobId) {
        cancelJob.thucHien(jobId);
    }
}
