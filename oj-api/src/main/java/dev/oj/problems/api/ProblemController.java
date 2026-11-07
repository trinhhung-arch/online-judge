package dev.oj.problems.api;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.web.CursorPage;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import dev.oj.problems.application.usecase.ListProblemsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {

    private final GetProblemUseCase getProblem;
    private final ListProblemsUseCase listProblems;
    private final AppProperties properties;

    public ProblemController(GetProblemUseCase getProblem, ListProblemsUseCase listProblems,
                             AppProperties properties) {
        this.getProblem = getProblem;
        this.listProblems = listProblems;
        this.properties = properties;
    }

    @GetMapping("/{code}")
    public ProblemResponse byCode(@PathVariable String code) {
        var de = getProblem.byCode(code);
        return ProblemResponse.from(de, getProblem.html(de));
    }

    @GetMapping
    public CursorPage<ProblemSummaryResponse> danhSach(
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean daGiai,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {

        int gioiHan = Math.min(
                size == null ? properties.page().defaultSize() : Math.max(1, size),
                properties.page().maxSize());
        var trang = listProblems.thucHien(tag, daGiai, cursor, gioiHan);
        return new CursorPage<>(
                trang.items().stream().map(ProblemSummaryResponse::from).toList(),
                trang.nextCursor());
    }

}
