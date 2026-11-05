package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@PublicAccess("Trang kỳ thi là trang công khai — người ta phải xem được lịch để quyết định có "
        + "đăng ký không. Danh sách đề thì bị giấu tới giờ bắt đầu.")
@Service
public class GetContestUseCase {

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final Clock clock;

    public GetContestUseCase(CurrentUserProvider currentUser, ContestRepository contests,
                             Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.clock = clock;
    }

    public ChiTiet theoSlug(String slug) {
        Contest contest = contests.timTheoSlug(slug == null ? "" : slug.trim())
                .orElseThrow(ContestsException::khongTimThay);

        Long userId = null;
        boolean laAdmin = false;
        try {
            var nguoiGoi = currentUser.current();
            userId = nguoiGoi.id();
            laAdmin = nguoiGoi.isAdmin();
        } catch (AuthorizationException e) {
            userId = null;
        }

        boolean duocSoan = laAdmin || contest.createdBy() == (userId == null ? -1L : userId);

        boolean hienDe = duocSoan || !contest.chuaMo(clock.instant());

        return new ChiTiet(
                contest,
                hienDe ? contests.deCua(contest.id()) : List.of(),
                userId != null && contests.daDangKy(contest.id(), userId),
                duocSoan);
    }

    public record ChiTiet(Contest contest, List<ContestRepository.DeCuaContest> cacDe,
                          boolean daDangKy, boolean duocSoan) {
    }
}
