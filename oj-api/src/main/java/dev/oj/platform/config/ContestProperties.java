package dev.oj.platform.config;

import java.time.Duration;

public record ContestProperties(
        Duration standingsInterval,
        int standingsBatchSize,
        Duration standingsGrace,
        int topSize,
        Duration driftCheckInterval,
        Duration driftCheckWindow) {

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
        if (driftCheckInterval == null || driftCheckInterval.toMinutes() < 1) {
            throw new IllegalStateException(
                    "oj.contest.drift-check-interval = " + driftCheckInterval
                            + ". Mỗi lần soát là một lần tính lại TOÀN BỘ kỳ thi từ "
                            + "submissions; nhịp dưới một phút là đổ tải lên đúng bảng mà "
                            + "đường nộp bài đang dùng");
        }
        if (driftCheckWindow == null || driftCheckWindow.isNegative()) {
            throw new IllegalStateException("oj.contest.drift-check-window không hợp lệ");
        }
        if (topSize < 1 || topSize > 200) {
            throw new IllegalStateException(
                    "oj.contest.top-size = " + topSize + ". FR-CON-04 nói top 50 và "
                            + "'không tải toàn bộ bảng' — một kỳ thi nghìn người mà trả "
                            + "hết là đúng thứ oj-api/CLAUDE.md mục 6 cấm");
        }
    }
}
