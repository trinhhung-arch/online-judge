package dev.oj.contests.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cổng <b>ĐỌC</b> bảng xếp hạng — FR-CON-04. Bước 5.7.
 *
 * <h2>Không bao giờ tải toàn bộ bảng</h2>
 * {@code oj-api/CLAUDE.md} mục 6: <i>"đọc top 50, và vị trí của chính mình. Không bao giờ tải
 * toàn bộ bảng."</i> Một kỳ thi lớn có hàng nghìn thí sinh, và trang bảng xếp hạng là trang
 * được tải lại nhiều nhất trong suốt kỳ thi — đúng lúc hệ thống bận nhất.
 *
 * <p>Vì thế interface này <b>không có</b> phương thức nào trả về mọi dòng. Muốn có, phải thêm
 * — và lúc thêm thì phải giải thích.
 *
 * @see StandingsRepository đường ghi
 */
public interface StandingsReader {

    /**
     * Top {@code n}. Bất biến #8 — {@code n} luôn có trần ở tầng gọi.
     *
     * @param dongBang đọc bản chụp lúc freeze thay vì bảng thật (FR-CON-05)
     */
    List<Dong> top(long contestId, int n, boolean dongBang);

    Optional<Dong> cuaNguoi(long contestId, long userId, boolean dongBang);

    /** Thứ hạng 1-based, hoặc rỗng nếu người này chưa có dòng nào. */
    Optional<Integer> hang(long contestId, long userId, boolean dongBang);

    /**
     * Một dòng bảng xếp hạng.
     *
     * @param soBaiChoSauFreeze số bài nộp sau giờ đóng băng — UI hiện ô "?" kiểu ICPC.
     *                          Luôn 0 khi đọc bảng thật
     */
    record Dong(long userId, String handle, String displayName,
                int tongDiem, int penaltyGiay, int soBaiDat,
                Instant lanGhiDiemCuoi, int soBaiChoSauFreeze) {
    }
}
