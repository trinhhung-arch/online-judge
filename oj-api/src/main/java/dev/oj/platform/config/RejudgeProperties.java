package dev.oj.platform.config;

import java.time.Duration;

/**
 * Điều tiết chấm lại hàng loạt — {@code oj.judge.rejudge.*}, FR-ADM-01, Bước 6.3. Luật nằm ở
 * {@code judging.domain.RejudgeJob}; đây chỉ là ba con số.
 *
 * <p>Từng là {@code AppProperties.Rejudge}; tách ra file riêng khi V16 thêm {@code max-reaps}
 * vào {@link AppProperties.Judge} và {@code AppProperties} vượt trần 300 dòng
 * ({@code CLAUDE.md} mục 7) — cùng cách {@link AuthProperties} và {@link ContestProperties}.
 *
 * @param maxInFlight    trần số dòng rejudge được nằm chờ trong {@code judge_queue} cùng
 *                       lúc. <b>Đây là cách viết "30% năng lực chấm" thành một con số
 *                       kiểm được</b>: mỗi dòng chờ là nhiều nhất một judge slot có thể
 *                       bận vì rejudge, nên 2 trên 6 slot là 33%. Đổi số slot ở
 *                       {@code oj-worker} thì phải đổi số này — {@code HopDongVanHanhTest}
 *                       đọc cả hai file yml và đối chiếu, để hai con số không lệch nhau
 *                       trong im lặng
 * @param liveWaitBrake  bài nộp trực tiếp chờ lâu hơn ngần này thì rejudge về 0. Khớp
 *                       P6 (p95 queue_wait &lt; 5s) của {@code nfrplan.md} Phần 1
 * @param batchSize      bất biến #8 — mọi truy vấn đều có {@code LIMIT}
 */
public record RejudgeProperties(int maxInFlight, Duration liveWaitBrake, int batchSize) {

    public RejudgeProperties {
        if (maxInFlight < 1) {
            throw new IllegalStateException(
                    "oj.judge.rejudge.max-in-flight phải >= 1, nhận " + maxInFlight
                            + ". Đặt 0 để tắt rejudge là sai chỗ: dùng công tắc "
                            + "system_settings['rejudge.enabled'], thứ ADMIN đổi được "
                            + "lúc đang chạy mà không deploy lại");
        }
        if (liveWaitBrake == null || liveWaitBrake.isNegative()) {
            throw new IllegalStateException("oj.judge.rejudge.live-wait-brake không hợp lệ");
        }
        if (batchSize < 1) {
            throw new IllegalStateException("oj.judge.rejudge.batch-size phải >= 1");
        }
    }
}
