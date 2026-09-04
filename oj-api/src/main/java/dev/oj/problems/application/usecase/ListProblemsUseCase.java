package dev.oj.problems.application.usecase;

import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.PublicAccess;
import dev.oj.platform.web.CursorPage;
import dev.oj.problems.application.port.ProblemRepository;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * FR-PROB-09 — danh sách đề đã xuất bản, lọc theo tag và theo "đã giải", phân trang.
 *
 * <h2>Cursor-based, không offset — bất biến #8 và {@code oj-api/CLAUDE.md} mục 3</h2>
 * {@code WHERE id < :cursor ORDER BY id DESC LIMIT :size}. Không có {@code COUNT(*)}, không có
 * "trang 47 / 213". Với vài trăm đề thì offset cũng chạy được, nhưng cùng một khuôn phân trang
 * ở mọi chỗ nghĩa là không ai phải nhớ chỗ nào được phép dùng offset — và
 * {@code submissions} thì tuyệt đối không.
 *
 * <h2>Khách xem được, nhưng bộ lọc "đã giải" thì không</h2>
 * "Đã giải" là một câu hỏi về <i>một người cụ thể</i>. Với khách chưa đăng nhập nó không có
 * câu trả lời, và trả về "chưa giải cho tất cả" là một lời nói dối im lặng — người dùng sẽ
 * tưởng bộ lọc hỏng. Từ chối tường minh với 401 rõ hơn hẳn.
 */
@PublicAccess("Danh sách đề đã xuất bản là ô đầu tiên của ma trận hiển thị — khách xem được. "
        + "Đề DRAFT và RETIRED bị lọc trong câu query, không phải ở đây.")
@Service
public class ListProblemsUseCase {

    /**
     * ★ Không có id đề nào bằng 0 ({@code GENERATED ALWAYS AS IDENTITY} bắt đầu từ 1), nên
     * phần tử canh này không loại bỏ ai. Nó có mặt vì {@code NOT IN ()} với danh sách rỗng là
     * lỗi cú pháp SQL, và danh sách rỗng chính là trường hợp thường gặp nhất — phần lớn thời
     * gian hệ thống không có kỳ thi nào sắp diễn ra.
     */
    private static final long CANH_RONG = 0L;

    private final CurrentUserProvider currentUser;
    private final ProblemRepository problems;
    private final ContestWindowQuery lichThi;

    public ListProblemsUseCase(CurrentUserProvider currentUser, ProblemRepository problems,
                               ContestWindowQuery lichThi) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.lichThi = lichThi;
    }

    public CursorPage<ProblemRepository.ProblemListItem> thucHien(
            String tagSlug, Boolean daGiai, String cursor, int size) {

        Long requesterId = idNguoiGoiHoacNull();
        if (daGiai != null && requesterId == null) {
            throw ProblemsException.khongHopLe("problem.loc_da_giai_can_dang_nhap",
                    "Cần đăng nhập để lọc theo bài đã giải.");
        }
        return problems.danhSachDaXuatBan(
                new ProblemRepository.ListFilter(rong(tagSlug) ? null : tagSlug.trim(),
                        requesterId, daGiai, idBiKhoa(requesterId)),
                cursor(cursor), size);
    }

    /**
     * ★ FR-CON-03 — đề của kỳ thi chưa mở KHÔNG được nằm trong danh sách.
     *
     * <h2>Vì sao "vào không được" chưa đủ, phải là "không thấy"</h2>
     * Trước đây câu query chỉ lọc {@code status = 'PUBLISHED'}, nên đề của kỳ thi tuần sau
     * vẫn hiện ra ở trang Đề bài; bấm vào thì {@link GetProblemUseCase} trả 404. Hai lỗi
     * trong một:
     *
     * <ul>
     *   <li><b>Với thí sinh</b> — một liên kết gãy giữa một danh sách bình thường. Không có
     *       lời giải thích nào, và lời giải thích đúng lại là thứ không được nói ra.</li>
     *   <li><b>Với sự công bằng</b> — danh sách ấy <i>là</i> thành phần kỳ thi. Ai chịu khó
     *       bấm thử từng đề sẽ biết chính xác đề nào 404, tức là đề nào sẽ ra tuần sau. Chốt
     *       404 giữ được NỘI DUNG đề, nhưng không giữ được DANH SÁCH đề.</li>
     * </ul>
     *
     * <h2>Đây là tách theo THỜI GIAN, không phải theo kho riêng</h2>
     * FR-CON-07: <i>"Sau khi contest kết thúc: mở đề ra ngoài"</i>. Nên đề không biến mất
     * vĩnh viễn — nó vắng mặt đúng khoảng thời gian nó phải vắng, rồi tự quay lại khi đồng hồ
     * đi qua {@code ends_at}. Không ai phải bấm nút, và không có kho đề thứ hai để quên.
     *
     * <p>ADMIN thấy tất cả; SETTER thấy đề của chính mình — chốt sau nằm trong câu query
     * ({@code p.owner_id = :requesterId}), đúng mẫu chống IDOR của {@code oj-api/CLAUDE.md}
     * mục 2: lọc theo chủ sở hữu thuộc về câu SQL, không thuộc về một câu {@code if}.
     */
    private List<Long> idBiKhoa(Long requesterId) {
        List<Long> ds = new ArrayList<>();
        ds.add(CANH_RONG);
        if (!laAdmin()) {
            ds.addAll(lichThi.deBiKhoaChoNguoiXem(requesterId));
        }
        return ds;
    }

    private boolean laAdmin() {
        try {
            return currentUser.current().isAdmin();
        } catch (AuthorizationException e) {
            return false;
        }
    }

    /**
     * {@code null} nếu chưa đăng nhập.
     *
     * <p>Đây là <b>một trong rất ít chỗ</b> được phép nuốt {@link AuthorizationException}: cột
     * "đã giải" là một tiện nghi, và một trang danh sách đề không có lý do gì đóng lại với
     * khách vì họ chưa đăng nhập.
     */
    private Long idNguoiGoiHoacNull() {
        try {
            return currentUser.current().id();
        } catch (AuthorizationException e) {
            return null;
        }
    }

    private static Long cursor(String cursor) {
        if (rong(cursor)) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            throw ProblemsException.khongHopLe("problem.cursor_khong_hop_le",
                    "Tham số phân trang không hợp lệ.");
        }
    }

    private static boolean rong(String s) {
        return s == null || s.isBlank();
    }
}
