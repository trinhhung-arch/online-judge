package dev.oj.problems.application.usecase;

import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.PublicAccess;
import dev.oj.platform.web.CursorPage;
import dev.oj.problems.application.port.ProblemRepository;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.stereotype.Service;

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

    private final CurrentUserProvider currentUser;
    private final ProblemRepository problems;

    public ListProblemsUseCase(CurrentUserProvider currentUser, ProblemRepository problems) {
        this.currentUser = currentUser;
        this.problems = problems;
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
                        requesterId, daGiai),
                cursor(cursor), size);
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
