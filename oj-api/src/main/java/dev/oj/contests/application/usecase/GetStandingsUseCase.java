package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.PublicAccess;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * ★ Đọc bảng xếp hạng — FR-CON-04 và FR-CON-05. Bước 5.7.
 *
 * <h2>Bộ lọc đóng băng nằm ở ĐÂY, không ở tầng dưới</h2>
 * {@code StandingsReader} nhận một cờ {@code dongBang} và làm đúng thứ được bảo. Quyết định
 * <i>giá trị của cờ ấy</i> là một câu hỏi về quyền, và bất biến #11 nói nó thuộc tầng
 * use-case.
 *
 * <p>Ma trận hiển thị ({@code oj-api/CLAUDE.md} mục 2), dòng "Bảng xếp hạng đã freeze":
 * ❄️ với mọi người, ✅ đầy đủ với ADMIN. Đúng một dòng mã dưới đây, và nó là dòng quan trọng
 * nhất của lớp này.
 *
 * <h2>Top N + đúng một dòng của mình — không bao giờ tải cả bảng</h2>
 * Một kỳ thi nghìn người, trang này được tải lại liên tục suốt kỳ thi. Trả cả bảng là thứ
 * {@code oj-api/CLAUDE.md} mục 6 cấm thẳng, và nó cấm vì lý do rất cụ thể: đó là trang bận
 * nhất, vào lúc bận nhất.
 */
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
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        Long userId = null;
        boolean laAdmin = false;
        try {
            var nguoiGoi = currentUser.current();
            userId = nguoiGoi.id();
            laAdmin = nguoiGoi.isAdmin();
        } catch (AuthorizationException e) {
            userId = null;   // khán giả chưa đăng nhập vẫn xem được bảng
        }

        // ★ Dòng quan trọng nhất của lớp này. Xem ma trận hiển thị.
        boolean dongBang = contest.dangDongBang(clock.instant()) && !laAdmin;

        List<StandingsReader.Dong> top =
                standings.top(contestId, properties.contest().topSize(), dongBang);

        StandingsReader.Dong cuaToi = null;
        Integer hangCuaToi = null;
        if (userId != null) {
            cuaToi = standings.cuaNguoi(contestId, userId, dongBang).orElse(null);
            hangCuaToi = standings.hang(contestId, userId, dongBang).orElse(null);
        }
        return new BangXepHang(contest, dongBang, top, cuaToi, hangCuaToi);
    }

    /**
     * @param dongBang bảng đang hiện bản chụp. UI phải nói rõ điều đó — một bảng xếp hạng
     *                 đóng băng mà trông như bảng thật là một lời nói dối, và thí sinh sẽ
     *                 tính chiến thuật dựa trên nó
     */
    public record BangXepHang(Contest contest, boolean dongBang,
                              List<StandingsReader.Dong> top,
                              StandingsReader.Dong cuaToi, Integer hangCuaToi) {

        public Optional<StandingsReader.Dong> dongCuaToi() {
            return Optional.ofNullable(cuaToi);
        }
    }
}
