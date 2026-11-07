package dev.oj.contests.api;

import dev.oj.contests.application.usecase.AuthorContestUseCase;
import dev.oj.problems.api.ProblemAuthoringRequest;
import dev.oj.contests.application.usecase.GetContestUseCase;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.application.usecase.ListContestsUseCase;
import dev.oj.contests.application.usecase.RegisterForContestUseCase;
import dev.oj.contests.application.usecase.RevealStandingsUseCase;
import dev.oj.platform.web.CursorPage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contests")
public class ContestController {

    private final GetContestUseCase getContest;
    private final ListContestsUseCase listContests;
    private final GetStandingsUseCase getStandings;
    private final RegisterForContestUseCase register;
    private final AuthorContestUseCase author;
    private final RevealStandingsUseCase reveal;
    private final java.time.Clock clock;

    public ContestController(GetContestUseCase getContest, ListContestsUseCase listContests,
                             GetStandingsUseCase getStandings,
                             RegisterForContestUseCase register, AuthorContestUseCase author,
                             RevealStandingsUseCase reveal, java.time.Clock clock) {
        this.getContest = getContest;
        this.listContests = listContests;
        this.getStandings = getStandings;
        this.register = register;
        this.author = author;
        this.reveal = reveal;
        this.clock = clock;
    }

    @GetMapping
    public CursorPage<ContestResponses.TomTat> danhSach(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {

        var trang = listContests.thucHien(cursor, size);
        return new CursorPage<>(
                trang.items().stream().map(ContestResponses.TomTat::tu).toList(),
                trang.nextCursor());
    }

    @GetMapping("/{slug}")
    public ContestResponses.ChiTiet xem(@PathVariable String slug) {
        return ContestResponses.ChiTiet.tu(getContest.theoSlug(slug), clock.instant());
    }

    @GetMapping("/{contestId}/standings")
    public ContestResponses.BangXepHang bangXepHang(@PathVariable long contestId) {
        return ContestResponses.BangXepHang.tu(getStandings.thucHien(contestId));
    }

    @PostMapping("/{contestId}/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dangKy(@PathVariable long contestId) {
        register.thucHien(contestId);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> tao(@RequestBody TaoContestRequest body) {
        long id = author.tao(body.toLenh());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("contestId", id));
    }

    @PostMapping("/{contestId}/problems")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void themDe(@PathVariable long contestId, @RequestBody ThemDeRequest body) {
        author.themDe(contestId, body.problemId(), body.label(),
                body.points() == null ? 100 : body.points());
    }

    @PostMapping("/{contestId}/problems/new")
    public ResponseEntity<Map<String, Object>> soanDeRieng(
            @PathVariable long contestId, @RequestBody SoanDeRequest body) {
        long problemId = author.soanDeRieng(contestId, body.de().toCommand(),
                body.label(), body.points() == null ? 100 : body.points());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("problemId", problemId));
    }

    @DeleteMapping("/{contestId}/problems/{problemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void goDe(@PathVariable long contestId, @PathVariable long problemId) {
        author.goDeKhoiKyThi(contestId, problemId);
    }

    @PostMapping("/{contestId}/reveal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void congBo(@PathVariable long contestId) {
        reveal.thucHien(contestId);
    }

    public record TaoContestRequest(String slug, String title, String format,
                                    Instant startsAt, Instant endsAt, Instant freezeAt,
                                    Integer penaltyMinutes, Boolean registrationRequired,
                                    Boolean revealAfterEnd) {

        AuthorContestUseCase.Lenh toLenh() {
            return new AuthorContestUseCase.Lenh(slug, title, format, startsAt, endsAt, freezeAt,
                    penaltyMinutes == null ? 20 : penaltyMinutes,
                    registrationRequired == null || registrationRequired,
                    revealAfterEnd == null || revealAfterEnd);
        }
    }

    public record ThemDeRequest(long problemId, String label, Integer points) {
    }

    public record SoanDeRequest(ProblemAuthoringRequest de, String label, Integer points) {
    }
}
