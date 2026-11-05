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

@RestController
@RequestMapping("/api/v1/admin")
public class AdminJudgingController {

    private final StartRejudgeUseCase rejudge;
    private final HideSubmissionUseCase anBai;

    public AdminJudgingController(StartRejudgeUseCase rejudge, HideSubmissionUseCase anBai) {
        this.rejudge = rejudge;
        this.anBai = anBai;
    }

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
