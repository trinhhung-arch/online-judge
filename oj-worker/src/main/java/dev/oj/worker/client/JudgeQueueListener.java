package dev.oj.worker.client;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ★ Bước 6.4 — nghe hai hàng đợi {@code judge.live} / {@code judge.rejudge} và <b>rung chuông</b>.
 *
 * <h2>Nằm ở {@code worker.client} vì luật ArchUnit 7</h2>
 * <i>"Chỉ {@code worker.client} biết địa chỉ của API."</i> Luật ấy được viết cho HTTP, nhưng
 * điều nó bảo vệ là: <b>bề mặt phụ thuộc của worker phải đọc được bằng một package</b>. Một
 * kết nối AMQP là một đường ra khỏi tiến trình y hệt một lời gọi HTTP, nên nó thuộc về đây —
 * và {@code WorkerArchitectureTest} đã được mở rộng để ép điều đó.
 *
 * <h2>Message KHÔNG được đọc, và đó là chủ ý</h2>
 * Thân message có {@code submissionId}, nhưng listener này bỏ qua nó: worker vẫn gọi
 * {@code /internal/judge/claim} và nhận bài mà <b>Postgres</b> chọn. Nếu listener chấm đúng
 * bài trong message thì hai chuyện hỏng cùng lúc — thứ tự ưu tiên
 * ({@code priority, enqueued_at}) không còn hiệu lực, và một message cũ (reaper đã tăng
 * {@code attempt}) làm worker chấm theo một ảnh chụp đã sai.
 *
 * <p>Vì thế con số trong message chỉ dùng để ghi log. Nó vẫn có ích: khi gỡ lỗi, biết tiếng
 * chuông nào tới lúc nào là biết độ trễ nằm ở chặng nào.
 *
 * <h2>{@code prefetch=1} · ack tay · DLQ sau 3 lần</h2>
 * <ul>
 *   <li><b>{@code prefetch=1}</b> — mặc định của Spring AMQP là 250. Với 250, một worker vừa
 *       khởi động hút sạch hàng đợi vào bộ đệm của nó, và những worker khác thấy hàng đợi rỗng
 *       trong khi máy của chúng rảnh.</li>
 *   <li><b>ack tay</b> — ack sau khi đã rung chuông. Worker chết giữa lúc nhận và rung thì
 *       message được giao lại cho worker khác.</li>
 *   <li><b>DLQ sau 3 lần</b> — {@code x-delivery-limit} trên quorum queue, khai báo phía API.
 *       Một tiếng chuông không xử lý nổi ba lần liên tiếp nghĩa là worker này đang hỏng; đẩy
 *       nó sang {@code judge.dead} để nó thôi quay vòng, và để dashboard thấy.</li>
 * </ul>
 *
 * <p><b>Ghi rõ một khác biệt so với Bước 6.4 như tài liệu viết:</b> tài liệu nói <i>"ack sau
 * khi kết quả đã vào DB"</i>. Ở thiết kế chuông cửa, ack xảy ra sau khi <i>rung chuông</i>, vì
 * message không mang việc — nó chỉ đánh thức. Bảo đảm mà mệnh đề kia nhắm tới (worker chết thì
 * không mất việc) vẫn còn nguyên và <b>mạnh hơn</b>: nó do {@code judge_queue} cộng lease 120s
 * cộng reaper cung cấp, chứ không do broker. Mất sạch cả ba hàng đợi cũng không mất một bài
 * nộp nào — đó chính là điều làm cho bước này chỉ chạm hai file.
 */
@ConditionalOnProperty(name = "oj.worker.rabbit.enabled", havingValue = "true")
@Component
public class JudgeQueueListener {

    private static final Logger log = LoggerFactory.getLogger(JudgeQueueListener.class);

    private final JudgeDoorbell chuong;

    public JudgeQueueListener(JudgeDoorbell chuong) {
        this.chuong = chuong;
    }

    @RabbitListener(
            queues = {"${oj.worker.rabbit.live-queue}", "${oj.worker.rabbit.rejudge-queue}"},
            ackMode = "MANUAL")
    public void nhanChuong(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            chuong.reo();
            channel.basicAck(deliveryTag, false);
            if (log.isTraceEnabled()) {
                log.trace("Chuông từ {}: {}", message.getMessageProperties().getConsumerQueue(),
                        new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            // requeue = true: giao lại cho worker khác. x-delivery-limit đếm số lần giao, nên
            // vòng lặp này có đáy — sau 3 lần message sang judge.dead thay vì quay mãi.
            channel.basicNack(deliveryTag, false, true);
            log.warn("Không xử lý được một tiếng chuông, trả về hàng đợi: {}", e.toString());
        }
    }
}
