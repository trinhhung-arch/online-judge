package dev.oj.judging.infrastructure;

import dev.oj.judging.application.published.QueueStatusQuery;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.metrics.OjMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class QueueMetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(QueueMetricsSampler.class);

    private static final String GHI_MAU = """
            INSERT INTO queue_metrics (sampled_at, queued_count, judging_count,
                                       oldest_wait_ms, live_workers, est_wait_ms)
            VALUES (:luc, :dangCho, :dangCham, :choLauNhatMs, :maySong, :choUocTinhMs)
            ON CONFLICT (sampled_at) DO NOTHING
            """;

    private final QueueStatusQuery hangDoi;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AppProperties properties;

    private final AtomicReference<QueueStatusQuery.TrangThai> mau =
            new AtomicReference<>(new QueueStatusQuery.TrangThai(0, 0, 0, null, 0));

    public QueueMetricsSampler(QueueStatusQuery hangDoi, @Qualifier("appJdbcClient") JdbcClient jdbc,
                               Clock clock, AppProperties properties, MeterRegistry registry) {
        this.hangDoi = hangDoi;
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        dangKyGauge(registry);
    }

    private void dangKyGauge(MeterRegistry registry) {
        Gauge.builder(OjMetrics.QUEUE_WAITING, mau, m -> m.get().dangCho())
                .description("Bài đang chờ worker nhận").register(registry);
        Gauge.builder(OjMetrics.QUEUE_JUDGING, mau, m -> m.get().dangCham())
                .description("Bài đang được chấm").register(registry);
        Gauge.builder(OjMetrics.QUEUE_REJUDGE_WAITING, mau, m -> m.get().rejudgeDangCho())
                .description("Bài chấm lại đang chờ (ưu tiên 10)").register(registry);
        Gauge.builder(OjMetrics.QUEUE_WAIT_MS, mau, m -> m.get().choLauNhatMs(clock.instant()))
                .description("P6 — bài live chờ lâu nhất, ms").baseUnit("ms").register(registry);
        Gauge.builder(OjMetrics.WORKERS_LIVE, mau, m -> m.get().mayChamSong())
                .description("Máy chấm còn báo danh trong cửa sổ host-liveness").register(registry);
    }

    public QueueStatusQuery.TrangThai mauGanNhat() {
        return mau.get();
    }

    @Scheduled(fixedDelayString = "${oj.judge.metrics-interval}")
    public void layMau() {
        try {
            QueueStatusQuery.TrangThai t = hangDoi.doc();
            mau.set(t);
            ghiVaoBang(t);
        } catch (RuntimeException e) {
            log.warn("Không lấy được mẫu hàng đợi: {}", e.toString());
        }
    }

    private void ghiVaoBang(QueueStatusQuery.TrangThai t) {
        java.time.Instant bayGio = clock.instant();
        jdbc.sql(GHI_MAU)
                .param("luc", OffsetDateTime.ofInstant(bayGio, ZoneOffset.UTC))
                .param("dangCho", t.dangCho())
                .param("dangCham", t.dangCham())
                .param("choLauNhatMs", (int) Math.min(Integer.MAX_VALUE, t.choLauNhatMs(bayGio)))
                .param("maySong", t.mayChamSong())
                .param("choUocTinhMs", (int) Math.min(Integer.MAX_VALUE,
                        t.choUocTinhMs(properties.judge().throughputEstimate())))
                .update();
    }
}
