package dev.oj.platform.health;

import dev.oj.platform.metrics.OjMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * ★ Bước 6.7 — <b>"Một health check luôn xanh còn tệ hơn không có health check"</b>
 * ({@code nfrplan.md} 7.2).
 *
 * <h2>Ba thành phần Boot đã kiểm, và một thành phần chỉ hệ thống này biết</h2>
 * {@code /actuator/health} đã có sẵn indicator cho Postgres (<b>cả hai pool</b> — Boot dò mọi
 * bean {@code DataSource}, nên pool {@code judge} chết mà pool {@code app} sống thì health
 * xuống DOWN, đúng như phải thế: lúc đó verdict không ghi được), cho Redis và cho RabbitMQ.
 *
 * <p>Cái Boot <b>không</b> biết là: hệ thống này còn <i>chấm được bài</i> không. Ba phụ thuộc
 * kia xanh hết mà không còn máy chấm nào thì mọi bài nộp vẫn vào {@code judge_queue} và nằm
 * đó — không mất, nhưng cũng không xong. Với người dùng đó là hỏng.
 *
 * <h2>DOWN hay OUT_OF_SERVICE?</h2>
 * <b>Không phải DOWN.</b> API vẫn phục vụ được mọi thứ khác: xem đề, xem lịch sử, xem bảng
 * xếp hạng, và <i>vẫn nhận bài nộp</i> — đó chính là dòng "Toàn bộ worker chết" của bảng
 * degraded mode ({@code nfrplan.md} 7.2): <i>"vẫn nhận bài, hiện đang chờ chấm, không báo lỗi
 * cho user"</i>. Trả DOWN ở đây là nói với bộ giám sát rằng cần restart API, và restart API
 * không mang một worker nào trở lại.
 *
 * <p>{@code OUT_OF_SERVICE} nói đúng điều cần nói: dịch vụ còn sống, một năng lực đang thiếu.
 *
 * <h2>Hai con số, và cái thứ hai mới là cái đáng tin trong contest</h2>
 * {@code mayChamSong} đọc {@code judge_hosts.last_seen_at}, cập nhật 15 phút một lần — nó
 * không phát hiện được một worker vừa chết ba phút trước. {@code dangCham} thì thời gian thực:
 * nó đếm lease đang được giữ. Health này dựa vào <b>cả hai</b>: có bài đang được chấm nghĩa là
 * chắc chắn còn máy chấm sống, bất kể {@code last_seen_at} nói gì.
 */
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

    /**
     * Đọc gauge đã đăng ký thay vì chạy truy vấn.
     *
     * <p>Health check bị gọi bởi bộ giám sát theo nhịp cố định, và một health check chạy truy
     * vấn là một tải nền không ai để ý cho tới khi có ba bộ giám sát. {@code QueueMetricsSampler}
     * đã lấy mẫu mỗi 10 giây rồi — đọc lại mẫu đó là đủ, và nó cũng bảo đảm health check và
     * dashboard không bao giờ nói hai con số khác nhau.
     *
     * @return {@code 0} nếu gauge chưa được đăng ký. Không ném: một health check ném ngoại lệ
     *         thành DOWN, và "chưa lấy mẫu lần nào" không phải là hỏng
     */
    private double doc(String ten) {
        Gauge g = metrics.find(ten).gauge();
        return g == null ? 0 : g.value();
    }
}
