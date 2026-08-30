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

/**
 * Xem một kỳ thi — FR-CON-01, và <b>bộ lọc quan trọng nhất của FR-CON-03</b>.
 *
 * <h2>★ Danh sách đề chỉ hiện ra khi kỳ thi đã bắt đầu</h2>
 * Trước giờ thi, trang kỳ thi hiện tên, thời gian, thể thức — <b>không hiện đề nào</b>. Không
 * phải vì đề bị khoá ở chỗ khác (nó có, {@code GetProblemUseCase} lo), mà vì <i>chính danh
 * sách mã đề</i> đã là thông tin: biết kỳ thi có sáu đề và mã của chúng là biết chỗ để đoán,
 * và với một hệ thống mà mã đề đọc được là {@code A-PLUS-B} thì đoán không khó.
 *
 * <p>Đây là ô "Đề trong contest chưa mở" của ma trận hiển thị, áp ở một tầng cao hơn một bậc.
 */
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

        // ★ Xem javadoc của class. Người tổ chức xem được trước để chuẩn bị.
        boolean hienDe = laAdmin || !contest.chuaMo(clock.instant())
                || contest.createdBy() == (userId == null ? -1L : userId);

        return new ChiTiet(
                contest,
                hienDe ? contests.deCua(contest.id()) : List.of(),
                userId != null && contests.daDangKy(contest.id(), userId));
    }

    /**
     * @param cacDe    rỗng khi kỳ thi chưa mở — <b>không</b> phải khi kỳ thi không có đề nào.
     *                 UI phải phân biệt bằng thời gian, không bằng độ dài danh sách
     * @param daDangKy luôn {@code false} với khách chưa đăng nhập
     */
    public record ChiTiet(Contest contest, List<ContestRepository.DeCuaContest> cacDe,
                          boolean daDangKy) {
    }
}
