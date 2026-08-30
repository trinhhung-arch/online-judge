package dev.oj.contests.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Một kỳ thi — FR-CON-01. Java thuần.
 *
 * <h2>Ba câu hỏi về thời gian, và chúng KHÔNG giống nhau</h2>
 * <ul>
 *   <li>{@link #dangChay} — đang trong khung giờ. Quyết định đề có mở không (FR-CON-03), đề có
 *       bị cấm sửa không (FR-PROB-11), AI review có bị tắt không (FR-AI-02).</li>
 *   <li>{@link #dangDongBang} — đã qua {@code freeze_at} nhưng chưa được công bố. Quyết định
 *       người thường thấy bảng chụp hay bảng thật (FR-CON-05).</li>
 *   <li>{@link #daCongBo} — ADMIN đã mở bảng đầy đủ. Chỉ có nghĩa sau khi thi xong.</li>
 * </ul>
 * Gộp chúng lại là nguồn của loại lỗi tệ nhất ở một kỳ thi: bảng xếp hạng lộ ra trước giờ,
 * hoặc đề mở trước giờ.
 *
 * @param freezeAt   {@code null} = không đóng băng
 * @param unfrozenAt {@code null} = chưa công bố. Đặt bởi ADMIN hoặc bởi
 *                   {@code RevealAfterEndUseCase} khi {@code revealAfterEnd} bật
 */
public record Contest(
        long id,
        String slug,
        String title,
        ContestFormat format,
        Instant startsAt,
        Instant endsAt,
        Instant freezeAt,
        Instant unfrozenAt,
        int penaltyMinutes,
        boolean registrationRequired,
        boolean revealAfterEnd,
        long createdBy) {

    public Contest {
        if (format == null || startsAt == null || endsAt == null) {
            throw new NullPointerException("format, startsAt và endsAt bắt buộc");
        }
        // Khớp ck_contest_window và ck_contest_freeze của V7. Bản ở đây cho câu tiếng Việt,
        // bản trong database là chốt thật.
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Giờ kết thúc phải sau giờ bắt đầu");
        }
        if (freezeAt != null && (!freezeAt.isAfter(startsAt) || freezeAt.isAfter(endsAt))) {
            throw new IllegalArgumentException(
                    "Giờ đóng băng phải nằm trong khung giờ thi");
        }
        if (penaltyMinutes < 0) {
            throw new IllegalArgumentException("penaltyMinutes không được âm");
        }
    }

    /**
     * ★ Bao gồm mốc bắt đầu, KHÔNG bao gồm mốc kết thúc.
     *
     * <p>Ranh giới đóng/mở ở đây không phải chuyện thẩm mỹ. Với {@code [start, end)}, một bài
     * nộp đúng vào giây kết thúc bị từ chối — và đó là hành vi đúng: giờ kết thúc là giờ
     * <i>đã hết</i>, không phải giây cuối còn được nộp. Ngược lại thì mọi thí sinh sẽ học được
     * rằng còn thêm một giây, và một giây trong một kỳ thi là một cuộc tranh cãi.
     */
    public boolean dangChay(Instant bayGio) {
        return !bayGio.isBefore(startsAt) && bayGio.isBefore(endsAt);
    }

    public boolean chuaMo(Instant bayGio) {
        return bayGio.isBefore(startsAt);
    }

    public boolean daKetThuc(Instant bayGio) {
        return !bayGio.isBefore(endsAt);
    }

    /**
     * Bảng xếp hạng đang bị đóng băng với người thường — FR-CON-05.
     *
     * <p><b>Đóng băng KHÔNG tự hết khi contest kết thúc.</b> Nó kéo dài tới khi có người công
     * bố ({@code unfrozenAt}). Đó là cả điểm của nghi thức trao giải kiểu ICPC: bảng vẫn kín
     * sau tiếng chuông, và được mở ra trước mặt mọi người.
     */
    public boolean dangDongBang(Instant bayGio) {
        return freezeAt != null && !bayGio.isBefore(freezeAt) && unfrozenAt == null;
    }

    public boolean daCongBo() {
        return unfrozenAt != null;
    }

    /** Penalty của ICPC tính theo phút kể từ giờ bắt đầu — {@code IcpcFormat} dùng. */
    public Duration keTuLucBatDau(Instant luc) {
        return Duration.between(startsAt, luc);
    }
}
