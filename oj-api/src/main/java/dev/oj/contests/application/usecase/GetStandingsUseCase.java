package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@PublicAccess("Bảng xếp hạng là thứ khán giả xem — không đòi đăng nhập. Phần 'hạng của tôi' "
        + "chỉ có khi đã đăng nhập, và bộ lọc đóng băng vẫn áp cho mọi người trừ ADMIN.")
@Service
public class GetStandingsUseCase {

    private final CurrentUserProvider currentUser;
    private final ContestRepository contests;
    private final StandingsReader standings;
    private final AppProperties properties;
    private final Clock clock;

    public GetStandingsUseCase(CurrentUserProvider currentUser, ContestRepository contests,
                               StandingsReader standings, AppProperties properties, Clock clock) {
        this.currentUser = currentUser;
        this.contests = contests;
        this.standings = standings;
        this.properties = properties;
        this.clock = clock;
    }

    public BangXepHang thucHien(long contestId) {
        return thucHien(contestId, nguoiGoiHienTai());
    }

    public BangXepHang thucHien(long contestId, CurrentUser nguoiGoi) {
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        boolean laAdmin = nguoiGoi != null && nguoiGoi.isAdmin();
        boolean dongBang = contest.dangDongBang(clock.instant()) && !laAdmin;

        List<StandingsReader.Dong> top =
                standings.top(contestId, properties.contest().topSize(), dongBang);

        StandingsReader.Dong cuaToi = null;
        Integer hangCuaToi = null;
        List<StandingsReader.DongCoHang> quanhToi = List.of();
        if (nguoiGoi != null) {
            quanhToi = standings.quanhNguoi(contestId, nguoiGoi.id(),
                    properties.contest().neighborhoodSize(), dongBang);
            for (StandingsReader.DongCoHang d : quanhToi) {
                if (d.dong().userId() == nguoiGoi.id()) {
                    cuaToi = d.dong();
                    hangCuaToi = d.hang();
                }
            }
        }
        return new BangXepHang(contest, dongBang, top, cuaToi, hangCuaToi, quanhToi);
    }

    public CurrentUser nguoiGoiHienTai() {
        try {
            return currentUser.current();
        } catch (AuthorizationException e) {
            return null;
        }
    }

    public record BangXepHang(Contest contest, boolean dongBang,
                              List<StandingsReader.Dong> top,
                              StandingsReader.Dong cuaToi, Integer hangCuaToi,
                              List<StandingsReader.DongCoHang> quanhToi) {

        public Optional<StandingsReader.Dong> dongCuaToi() {
            return Optional.ofNullable(cuaToi);
        }
    }
}
