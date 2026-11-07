package dev.oj.platform.health;

import dev.oj.platform.metrics.OjMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class JudgeCapacityHealthIndicator implements HealthIndicator {

    private final MeterRegistry metrics;

    public JudgeCapacityHealthIndicator(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        int maySong = (int) doc(OjMetrics.WORKERS_LIVE);
        int dangCho = (int) doc(OjMetrics.QUEUE_WAITING);
        int dangCham = (int) doc(OjMetrics.QUEUE_JUDGING);
        long choMs = (long) doc(OjMetrics.QUEUE_WAIT_MS);

        Health.Builder b = (maySong > 0 || dangCham > 0)
                ? Health.up()
                : Health.outOfService().withDetail("lyDo",
                        "Không máy chấm nào báo danh và không bài nào đang được chấm. "
                                + "Bài nộp vẫn được nhận và vẫn nằm trong hàng đợi.");
        return b.withDetail("mayChamSong", maySong)
                .withDetail("dangCho", dangCho)
                .withDetail("dangCham", dangCham)
                .withDetail("choLauNhatMs", choMs)
                .build();
    }

    private double doc(String ten) {
        Gauge g = metrics.find(ten).gauge();
        return g == null ? 0 : g.value();
    }
}
