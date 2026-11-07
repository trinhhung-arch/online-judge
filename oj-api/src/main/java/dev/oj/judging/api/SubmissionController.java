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

    @PostMapping
    public ResponseEntity<SubmissionAcceptedResponse> submit(
            @Valid @RequestBody SubmitSolutionRequest request) {
        var accepted = submitSolution.submit(new SubmitSolutionUseCase.Command(
                request.problemId(), request.languageCode(), request.source()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(SubmissionAcceptedResponse.from(accepted));
    }

    @GetMapping("/{id}")
    public SubmissionDetailResponse byId(@PathVariable long id) {
        return SubmissionDetailResponse.from(getSubmission.detailById(id));
    }

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

}
