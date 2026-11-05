package dev.oj.problems.application.port;

import dev.oj.platform.web.CursorPage;
import dev.oj.problems.domain.Problem;

import java.util.List;
import java.util.Optional;

public interface ProblemRepository {

    Optional<Problem> findPublishedByCode(String code);

    Optional<Problem> findPublishedById(long id);

    CursorPage<ProblemListItem> danhSachDaXuatBan(ListFilter loc, Long cursor, int size);

    record ListFilter(String tagSlug, Long requesterId, Boolean daGiaiBoi, List<Long> idBiKhoa) {
    }

    record ProblemListItem(
            long id, String code, String title,
            int timeLimitMs, int memoryLimitKb, boolean daGiai) {
    }
}
