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

/**
 * Lấy mẫu hàng đợi mỗi 10 giây — Bước 6.10 và 6.11. Nguồn của cả Micrometer lẫn
 * {@code queue_metrics}.
 *
 * <h2>★ Vì sao lấy mẫu định kỳ chứ không đo lúc có người hỏi</h2>
 * Ba lý do, và lý do thứ ba là lý do thật:
 *
 * <ol>
 *   <li>Trang trạng thái công khai (FR-ADM-05) là trang người ta bấm F5 khi hệ thống có vẻ
 *       chậm — tức là <b>lưu lượng của nó cao nhất đúng lúc database đang tải nặng nhất</b>.
 *       Đọc từ một biến trong bộ nhớ thì số lần gọi không đổi thành số truy vấn.</li>
 *   <li>Micrometer {@code Gauge} phải đọc được bất cứ lúc nào scrape tới, và một gauge chạy
 *       truy vấn trong hàm đọc là một truy vấn ta không kiểm soát nhịp.</li>
 *   <li><b>{@code queue_metrics} cần một chuỗi thời gian.</b> "Hàng đợi đang dài 40 bài" không
 *       nói được gì; "40 bài và mười phút trước là 5" mới là thông tin. Không lưu mẫu thì
 *       sau sự cố không có gì để nhìn lại — và dashboard vận hành sinh ra chính vì lúc 2 giờ
 *       sáng không ai dựng lại được quá khứ.</li>
 * </ol>
 *
 * <h2>Nhịp 10 giây là con số của V6</h2>
 * Comment trên bảng {@code queue_metrics} viết "ghi mỗi 10 giây bởi job nền". Giữ nguyên: mỗi
 * dòng vài chục byte, 8.640 dòng/ngày trên một bảng nguội.
 */
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

    /**
     * Mẫu gần nhất. Micrometer đọc từ đây, không chạy truy vấn — xem javadoc lớp.
     *
     * <p>Khởi tạo bằng một mẫu rỗng thay vì {@code null}: một gauge đọc {@code null} trước
     * lần lấy mẫu đầu tiên sẽ báo {@code NaN}, và {@code NaN} trên dashboard đọc như một sự cố.
     */
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

    /** Mẫu gần nhất, cho trang trạng thái và health check. Không chạm database. */
    public QueueStatusQuery.TrangThai mauGanNhat() {
        return mau.get();
    }

    /**
     * Nuốt mọi ngoại lệ: Spring <b>huỷ hẳn</b> một tác vụ {@code @Scheduled} ném ra ngoài, và
     * mất bộ lấy mẫu nghĩa là mất luôn dashboard — đúng thứ cần khi có sự cố.
     */
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
