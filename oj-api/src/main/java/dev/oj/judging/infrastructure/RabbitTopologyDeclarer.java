package dev.oj.judging.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Khai báo exchange/queue/binding <b>ngay khi API sẵn sàng</b>, thay vì đợi bài nộp đầu tiên.
 *
 * <h2>Lỗ hổng mà lớp này bịt — đã xảy ra thật 2026-09-10</h2>
 * {@code RabbitAdmin} khai báo topology khi một <b>kết nối được mở</b>, không phải khi context
 * được dựng. Mà {@code CachingConnectionFactory} nối <b>lười</b>: API không có listener
 * container nào, nó chỉ gửi đi, nên chừng nào chưa ai nộp bài thì chưa có kết nối, và chưa có
 * kết nối thì chưa có hàng đợi nào tồn tại trên broker.
 *
 * <p>Hôm ấy broker mất sạch trạng thái (node name đi theo container ID — nay đã chặn bằng
 * {@code hostname:} trong {@code docker-compose.yml}). API khởi động lại bình thường, nối lại
 * bình thường, {@code /api/v1/status} trả {@code dangNhanBai: true} — nhưng ba hàng đợi không
 * tồn tại, và worker chết trong vòng lặp {@code NOT_FOUND - no queue 'judge.live'} vì nó khai
 * báo <b>thụ động</b>: chỉ API mới tạo hàng đợi. Thứ phá được thế bế tắc lúc ấy là một lượt
 * {@code GET /actuator/health} — health indicator của Rabbit chạm vào broker, kết nối mở ra,
 * và topology hiện ra đầy đủ. Một hệ thống không nên cần ai đó vô tình gọi health mới hoạt
 * động được.
 *
 * <h2>Vì sao chỉ log chứ không ném</h2>
 * Broker chết <b>không</b> được làm API chết theo — đó là trạng thái degraded có chủ đích:
 * {@code SubmitSolution} ghi bài nộp vào Postgres <i>trước</i>, cầu dao trong
 * {@code RabbitJudgeJobPublisher} chặn việc thử lại mỗi bài, và reaper nhặt lại những bài
 * không có ai chuông tới ({@code PublishFailsButReaperRecoversIT}). Ném ở đây là biến một sự
 * cố hạ tầng chịu đựng được thành một API không khởi động nổi.
 *
 * <p>Bắt {@code AmqpException} chứ không phải {@code RuntimeException}: lỗi kết nối và lỗi I/O
 * của broker đều nằm dưới nó, còn một lỗi cấu hình thật sự thì vẫn phải làm API dừng lại và
 * nói ra.
 *
 * <h2>Nó KHÔNG bịt trường hợp nào</h2>
 * Broker mất trạng thái <i>giữa lúc</i> API đang chạy: kết nối rớt, và
 * {@code CachingConnectionFactory} chỉ nối lại khi có việc, nên topology vẫn vắng cho tới bài
 * nộp kế tiếp — bài ấy được cứu bởi reaper, nhưng khoảng trống thì vẫn có. Sau khi node name
 * thôi phụ thuộc container ID, đường duy nhất còn lại dẫn tới cảnh ấy là ai đó xoá volume,
 * nên vá thêm một vòng lặp kiểm tra định kỳ là đắt hơn thứ nó mua.
 */
public class RabbitTopologyDeclarer {

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyDeclarer.class);

    private final ConnectionFactory connectionFactory;

    public RabbitTopologyDeclarer(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void khaiBaoLucKhoiDong() {
        try {
            // KHÔNG đóng lại, và KHÔNG phải sơ suất: CachingConnectionFactory sở hữu vòng đời
            // của kết nối này và cache nó cho cả đời ứng dụng. Giữ nó mở mới là thứ ta muốn —
            // kết nối còn sống thì cơ chế khôi phục của Spring nối lại được sau khi broker
            // khởi động lại, và RabbitAdmin khai báo lại topology ở mỗi kết nối mới.
            connectionFactory.createConnection();
            log.info("Đã khai báo topology RabbitMQ lúc khởi động — hàng đợi sẵn sàng trước "
                    + "bài nộp đầu tiên");
        } catch (AmqpException e) {
            log.warn("Chưa khai báo được topology RabbitMQ lúc khởi động: {}. API VẪN nhận "
                    + "bài — bài nộp vào Postgres trước và reaper nhặt lại; topology sẽ được "
                    + "khai khi kết nối tới broker mở lại được.", e.toString());
        }
    }
}
