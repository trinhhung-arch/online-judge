package dev.oj.platform.config;

import java.time.Duration;

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
