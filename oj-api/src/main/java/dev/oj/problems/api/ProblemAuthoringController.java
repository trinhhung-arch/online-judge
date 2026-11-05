package dev.oj.problems.api;

import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/problems")
public class ProblemAuthoringController {

    private final AuthorProblemUseCase authorProblem;
    private final GetProblemUseCase getProblem;

    public ProblemAuthoringController(AuthorProblemUseCase authorProblem,
                                      GetProblemUseCase getProblem) {
        this.authorProblem = authorProblem;
        this.getProblem = getProblem;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> tao(@RequestBody ProblemAuthoringRequest body) {
        long id = authorProblem.tao(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("problemId", id));
    }

    @PutMapping("/{problemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sua(@PathVariable long problemId, @RequestBody ProblemAuthoringRequest body) {
        authorProblem.sua(problemId, body.toCommand());
    }

    @GetMapping("/{problemId}/edit")
    public ProblemAuthoringResponse doc(@PathVariable long problemId) {
        var de = authorProblem.doc(problemId);
        return ProblemAuthoringResponse.from(de, getProblem.html(de));
    }

    @DeleteMapping("/{problemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void xoa(@PathVariable long problemId) {
        authorProblem.xoa(problemId);
    }

    @PostMapping("/{problemId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void xuatBan(@PathVariable long problemId) {
        authorProblem.xuatBan(problemId);
    }

    @PostMapping("/{problemId}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void goXuong(@PathVariable long problemId) {
        authorProblem.goXuong(problemId);
    }
}
