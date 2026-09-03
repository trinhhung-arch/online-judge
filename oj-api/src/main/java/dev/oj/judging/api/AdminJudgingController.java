package dev.oj.judging.api;

import dev.oj.judging.application.usecase.HideSubmissionUseCase;
import dev.oj.judging.application.usecase.StartRejudgeUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hai thao tác quản trị của {@code judging}: chấm lại hàng loạt (FR-ADM-01, Bước 6.3) và
 * ẩn bài nộp (FR-SUB-09, Bước 6.13).
 *
 * <p>Không có {@code @RequiresRole} ở file này — nó nằm trên hai use-case (bất biến #11).
 *
 * <h2>Ba động từ, và không cái nào là {@code DELETE}</h2>
 * {@code POST .../rejudge} · {@code POST .../hide} · {@code POST .../unhide}. Đặt tên đúng
 * với việc thật sự xảy ra là cách rẻ nhất để người dùng API sau này không hiểu nhầm — cùng
 * lập luận đã dùng cho {@code POST .../anonymize} thay vì {@code DELETE /users/{id}}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminJudgingController {

    private final StartRejudgeUseCase rejudge;
    private final HideSubmissionUseCase anBai;

    public AdminJudgingController(StartRejudgeUseCase rejudge, HideSubmissionUseCase anBai) {
        this.rejudge = rejudge;
        this.anBai = anBai;
    }

    /**
     * FR-ADM-01. Trả {@code 202 Accepted} + {@code jobId}, <b>không</b> {@code 200}: việc chưa
     * xong, và nó sẽ mất nhiều phút. Cùng mã trạng thái với {@code POST /submissions}, và vì
     * cùng một lý do — {@code accept != process}.
     */
    @PostMapping("/problems/{problemId}/rejudge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> chamLai(@PathVariable long problemId) {
        return Map.of("jobId", rejudge.batDau(problemId));
    }

    @PostMapping("/submissions/{submissionId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void an(@PathVariable long submissionId) {
        anBai.dat(submissionId, true);
    }

    @PostMapping("/submissions/{submissionId}/unhide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hien(@PathVariable long submissionId) {
        anBai.dat(submissionId, false);
    }
}
