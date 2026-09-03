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

    /**
     * Đọc bảng cho người của <b>request đang chạy</b>. Chỉ đúng khi gọi trên thread của request.
     *
     * @see #thucHien(long, CurrentUser) khi lời gọi KHÔNG nằm trên thread ấy
     */
    public BangXepHang thucHien(long contestId) {
        return thucHien(contestId, nguoiGoiHienTai());
    }

    /**
     * ★ Người gọi truyền vào TƯỜNG MINH — dành cho lời gọi không ở trên thread của request.
     *
     * <h2>Vì sao bản này phải tồn tại</h2>
     * {@link #nguoiGoiHienTai()} đọc một {@code ThreadLocal} do {@code JwtAuthFilter} đặt rồi
     * xoá trong {@code finally}. Một luồng SSE thì sống lâu hơn cái request đã mở nó: các
     * khung tiếp theo được đẩy từ thread điều phối của Redis, nơi {@code ThreadLocal} ấy rỗng.
     *
     * <p>Gọi bản không tham số ở đó thì mọi khung sau khung đầu đều là ẩn danh —
     * {@code cuaToi} thành {@code null}, và dòng của chính thí sinh <b>biến mất</b> khỏi bảng
     * ngay ở lần cập nhật đầu tiên (FR-CON-04 đòi "top N + vùng quanh mình"). Nên chỗ gọi
     * phải phân giải danh tính trước, trên đúng thread, rồi giữ lại vật thể ấy.
     *
     * <h2>Danh tính được chốt tại lúc mở luồng</h2>
     * Một admin bị hạ vai trò giữa kỳ thi vẫn thấy bảng chưa đóng băng cho tới khi luồng đứt.
     * Đó là đánh đổi có chủ ý: hướng sai của cách này là <i>hiện thừa cho một người vừa mới
     * còn là admin</i>, còn hướng sai của cách kia là <i>giấu mất dòng của mọi thí sinh</i>.
     *
     * @param nguoiGoi {@code null} nghĩa là khán giả chưa đăng nhập — vẫn xem được bảng
     */
    public BangXepHang thucHien(long contestId, CurrentUser nguoiGoi) {
        Contest contest = contests.timTheoId(contestId)
                .orElseThrow(ContestsException::khongTimThay);

        // ★ Dòng quan trọng nhất của lớp này. Xem ma trận hiển thị. `laAdmin` được suy Ở ĐÂY
        // từ danh tính, chứ không nhận một cờ boolean từ chỗ gọi — bất biến #11.
        boolean laAdmin = nguoiGoi != null && nguoiGoi.isAdmin();
        boolean dongBang = contest.dangDongBang(clock.instant()) && !laAdmin;

        List<StandingsReader.Dong> top =
                standings.top(contestId, properties.contest().topSize(), dongBang);

        StandingsReader.Dong cuaToi = null;
        Integer hangCuaToi = null;
        if (nguoiGoi != null) {
            cuaToi = standings.cuaNguoi(contestId, nguoiGoi.id(), dongBang).orElse(null);
            hangCuaToi = standings.hang(contestId, nguoiGoi.id(), dongBang).orElse(null);
        }
        return new BangXepHang(contest, dongBang, top, cuaToi, hangCuaToi);
    }

    /**
     * Ai đang gọi, hoặc {@code null} nếu chưa đăng nhập.
     *
     * <p><b>Phải gọi trên thread của request.</b> Ở bất cứ thread nào khác nó trả {@code null}
     * một cách im lặng — xem javadoc của {@link #thucHien(long, CurrentUser)}.
     */
    public CurrentUser nguoiGoiHienTai() {
        try {
            return currentUser.current();
        } catch (AuthorizationException e) {
            return null;   // khán giả chưa đăng nhập vẫn xem được bảng
        }
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
