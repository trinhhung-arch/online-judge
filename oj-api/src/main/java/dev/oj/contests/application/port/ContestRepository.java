package dev.oj.contests.application.port;

import dev.oj.contests.domain.Contest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Cổng ra bảng {@code contests}, {@code contest_problems}, {@code contest_registrations} (V7).
 */
public interface ContestRepository {

    Optional<Contest> timTheoId(long contestId);

    Optional<Contest> timTheoSlug(String slug);

    long tao(ContestMoi contest);

    void themDe(long contestId, long problemId, String label, int ordinal, int points);

    List<DeCuaContest> deCua(long contestId);

    boolean daDangKy(long contestId, long userId);

    /** @throws dev.oj.contests.domain.ContestsException {@code CONFLICT} nếu đã đăng ký */
    void dangKy(long contestId, long userId, Instant luc);

    /**
     * Kỳ thi mà bảng xếp hạng còn cần cập nhật.
     *
     * <p>Gồm cả kỳ thi <b>vừa kết thúc</b> trong một khoảng ân hạn: bài nộp ở giây cuối vẫn
     * đang chấm khi chuông reo, và verdict của chúng tới sau. Cắt đúng {@code ends_at} là bỏ
     * rơi những bài ấy — và chúng thường là những bài quyết định thứ hạng.
     */
    /**
     * ★ Danh sách kỳ thi, phân trang cursor — bất biến #8.
     *
     * <h2>Sắp theo {@code id DESC}, không phải {@code starts_at DESC}</h2>
     * Đúng khuôn của {@code oj-api/CLAUDE.md} mục 3, và có một lý do cụ thể: {@code id} là
     * duy nhất, còn {@code starts_at} thì không. Hai kỳ thi mở cùng một thời điểm — chuyện
     * hoàn toàn bình thường khi người tổ chức tạo hàng loạt — sẽ làm một cursor chỉ mang
     * {@code starts_at} bỏ sót hoặc lặp dòng ở ranh giới trang.
     *
     * <p>Muốn sắp theo giờ bắt đầu thì cursor phải là bộ đôi {@code (starts_at, id)}, đúng
     * bài học đã trả giá ở {@code JdbcAuditLogReader}. Chưa cần: kỳ thi được tạo ngay trước
     * khi chạy, nên {@code id DESC} gần như trùng "sắp diễn ra trước".
     *
     * @param sauId    {@code null} cho trang đầu
     * @param gioiHan  lấy {@code gioiHan + 1} dòng — xem {@code CursorPage.of}
     */
    List<Contest> danhSach(Long sauId, int gioiHan);

    List<Contest> canCapNhat(Instant bayGio, java.time.Duration anHan);

    /** Kỳ thi đã qua {@code freeze_at} mà chưa có bản chụp — FR-CON-05. */
    List<Contest> canDongBang(Instant bayGio);

    /** FR-CON-05, FR-CON-07 — đặt {@code unfrozen_at}. */
    void congBo(long contestId, Instant luc);

    /**
     * @param penaltyMinutes chỉ có nghĩa với thể thức ICPC; IOI bỏ qua
     * @param freezeAt       {@code null} = không đóng băng
     */
    record ContestMoi(String slug, String title, String format,
                      Instant startsAt, Instant endsAt, Instant freezeAt,
                      int penaltyMinutes, boolean registrationRequired,
                      boolean revealAfterEnd, long createdBy) {
    }

    /** @param points điểm tối đa của đề trong kỳ thi này — IOI dùng, ICPC bỏ qua */
    /**
     * @param code mã đề, ví dụ {@code A-PLUS-B}. Có mặt vì trang kỳ thi phải link sang trang
     *             đề, mà trang đề nhận {@code ?code=} chứ không nhận id. Thiếu nó thì trong
     *             lúc thi, danh sách đề — vốn LÀ thanh điều hướng của thí sinh — chỉ hiện
     *             được một con số không bấm được.
     */
    record DeCuaContest(long problemId, String code, String label, int ordinal, int points) {
    }
}
