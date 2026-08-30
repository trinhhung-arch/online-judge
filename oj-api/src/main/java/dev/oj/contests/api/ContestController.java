package dev.oj.contests.api;

import dev.oj.contests.application.usecase.AuthorContestUseCase;
import dev.oj.contests.application.usecase.GetContestUseCase;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.application.usecase.RegisterForContestUseCase;
import dev.oj.contests.application.usecase.RevealStandingsUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Kỳ thi — FR-CON-01, 02, 04, 05, 07.
 *
 * <p>Không một câu {@code if} nào về quyền hay về thời gian trong file này. Cả hai đều nằm ở
 * use-case, nơi consumer, job nền và test đều phải đi qua (bất biến #11).
 */
@RestController
@RequestMapping("/api/v1/contests")
public class ContestController {

    private final GetContestUseCase getContest;
    private final GetStandingsUseCase getStandings;
    private final RegisterForContestUseCase register;
    private final AuthorContestUseCase author;
    private final RevealStandingsUseCase reveal;

    public ContestController(GetContestUseCase getContest, GetStandingsUseCase getStandings,
                             RegisterForContestUseCase register, AuthorContestUseCase author,
                             RevealStandingsUseCase reveal) {
        this.getContest = getContest;
        this.getStandings = getStandings;
        this.register = register;
        this.author = author;
        this.reveal = reveal;
    }

    @GetMapping("/{slug}")
    public ContestResponses.ChiTiet xem(@PathVariable String slug) {
        return ContestResponses.ChiTiet.tu(getContest.theoSlug(slug));
    }

    /** FR-CON-04. Đường REST này cũng là <b>fallback bắt buộc</b> của luồng SSE. */
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
        author.themDe(contestId, body.problemId(), body.label(), body.ordinal(),
                body.points() == null ? 100 : body.points());
    }

    /** FR-CON-05, FR-CON-07 — công bố bảng đầy đủ. Xem {@link RevealStandingsUseCase}. */
    @PostMapping("/{contestId}/reveal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void congBo(@PathVariable long contestId) {
        reveal.thucHien(contestId);
    }

    /** @param penaltyMinutes chỉ ICPC dùng; mặc định 20 khớp {@code DEFAULT} của V7 */
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

    public record ThemDeRequest(long problemId, String label, int ordinal, Integer points) {
    }
}
