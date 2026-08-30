package dev.oj.platform.config;

import java.time.Duration;

/**
 * Kỳ thi và bảng xếp hạng — M5, FR-CON-04 và P8.
 *
 * @param standingsInterval  nhịp cập nhật bảng xếp hạng. FR-CON-04 chốt <b>≤2 giây</b> sau
 *                           verdict mới, nên đây là con số đã hứa với người dùng chứ không
 *                           phải một tham số vận hành
 * @param standingsBatchSize số bài nộp xử lý mỗi nhịp. Bất biến #8 — không có truy vấn nào
 *                           không giới hạn. Quá nhỏ thì bảng chạy sau verdict; quá lớn thì
 *                           một nhịp giữ transaction lâu và chặn đường ghi khác
 * @param standingsGrace     ân hạn sau {@code ends_at}. Bài nộp ở giây cuối vẫn đang chấm
 *                           khi chuông reo, và verdict tới sau — cắt đúng {@code ends_at}
 *                           là bỏ rơi đúng những bài quyết định thứ hạng
 * @param topSize            số dòng của bảng xếp hạng công khai. FR-CON-04: top 50
 */
public record ContestProperties(
        Duration standingsInterval,
        int standingsBatchSize,
        Duration standingsGrace,
        int topSize) {

public ContestProperties {
        if (standingsInterval == null || standingsInterval.isZero()
                || standingsInterval.toSeconds() > 2) {
            throw new IllegalStateException(
                    "oj.contest.standings-interval = " + standingsInterval
                            + ". FR-CON-04 chốt bảng xếp hạng cập nhật trong 2 giây sau "
                            + "verdict mới — đó là con số đã hứa với người dùng. "
                            + "Nới ra là phải hỏi người");
        }
        if (standingsBatchSize < 1 || standingsBatchSize > 5000) {
            throw new IllegalStateException(
                    "oj.contest.standings-batch-size phải trong khoảng 1..5000");
        }
        if (standingsGrace == null || standingsGrace.isNegative()) {
            throw new IllegalStateException("oj.contest.standings-grace không hợp lệ");
        }
        if (topSize < 1 || topSize > 200) {
            throw new IllegalStateException(
                    "oj.contest.top-size = " + topSize + ". FR-CON-04 nói top 50 và "
                            + "'không tải toàn bộ bảng' — một kỳ thi nghìn người mà trả "
                            + "hết là đúng thứ oj-api/CLAUDE.md mục 6 cấm");
        }
    }
}
