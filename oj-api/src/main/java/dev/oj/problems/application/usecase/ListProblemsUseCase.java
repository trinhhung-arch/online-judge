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

@PublicAccess("Danh sách đề đã xuất bản là ô đầu tiên của ma trận hiển thị — khách xem được. "
        + "Đề DRAFT và RETIRED bị lọc trong câu query, không phải ở đây.")
@Service
public class ListProblemsUseCase {

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
