package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SubmissionEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * ★ Bước 3.8 — fan-out SSE qua Redis pub/sub.
 *
 * <h2>Một kênh cho mỗi bài nộp</h2>
 * {@code oj:submission:{id}}. Redis tự lo việc chỉ giao thông điệp cho instance nào đang
 * nghe kênh đó, nên một API có 1000 kết nối SSE không phải lọc 1000 lần cho mỗi verdict của
 * người khác. Kênh là thứ rẻ nhất trong Redis; lọc trong Java thì không.
 *
 * <h2>★ Mọi lỗi ở đây đều bị nuốt, và đó là quyết định chứ không phải cẩu thả</h2>
 * {@link #publish} được gọi <b>sau khi verdict đã ghi xong</b>. Ném lỗi ở đây không cứu được
 * gì — dữ liệu đã nằm trong Postgres, đã đúng, đã bền. Nó chỉ có thể <i>phá</i>: một
 * {@code RuntimeException} lọt lên sẽ làm hỏng đường ghi kết quả, bài quay về hàng đợi, và
 * worker chấm lại một bài đã có verdict.
 *
 * <p>Nói cách khác: Redis chết thì trang bài nộp chậm cập nhật cho tới nhịp polling kế tiếp
 * (Bước 3.10 — fallback REST bắt buộc, chính vì ngày này). Redis chết mà làm mất một bài nộp
 * thì đó là điều duy nhất hệ thống này không được phép làm.
 *
 * <h2>{@code RedisMessageListenerContainer} là bean của Spring Boot, không phải của ta</h2>
 * Boot 4.1 <b>đã</b> tự cấu hình sẵn một container. Tự khai báo thêm một cái nữa — kể cả để
 * gắn executor luồng ảo — chỉ tạo ra {@code NoUniqueBeanDefinitionException} lúc khởi động.
 * Đây là bài học đã trả giá bằng một lần cả bộ integration test đỏ.
 *
 * <p>Hệ quả: executor là mặc định của Boot. Khi bật {@code spring.threads.virtual.enabled}
 * (dự kiến ở M4, cùng lúc với load test 1000 kết nối SSE), container này tự dùng luồng ảo
 * mà không phải sửa dòng nào ở đây. Trước đó, mức tải hiện tại — vài thông điệp mỗi giây —
 * không cần tới nó.
 *
 * <p>Container chỉ mở kết nối tới Redis khi có listener đầu tiên, tức là khi có người thật
 * mở luồng SSE. Nhờ đó Redis chết lúc khởi động <b>không</b> ngăn API nhận bài nộp.
 */
@Component
public class RedisSubmissionEventBus implements SubmissionEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisSubmissionEventBus.class);

    private static final String CHANNEL_PREFIX = "oj:submission:";

    /**
     * Thông điệp để nguyên một dòng, <b>không nối chuỗi</b>.
     *
     * <p>Luật 5d của {@code ArchitectureTest} cấm mọi phép nối chuỗi trong tầng
     * {@code infrastructure}, và nó được ép bằng một lệnh {@code grep} trong CI — thô, vì từ
     * Java 9 phép cộng hai chuỗi biên dịch thành {@code invokedynamic} và ArchUnit không còn
     * nhìn thấy nó nữa. Bộ lọc ấy không phân biệt được một câu SQL với một câu log, và
     * <b>không nên</b> biết phân biệt: nới nó ra là bỏ hàng rào cuối cùng chặn một mệnh đề
     * {@code WHERE} được ghép từ dữ liệu người dùng (bất biến #5, SEC2).
     *
     * <p>Cái giá phải trả là đúng chỗ này: một hằng số thay vì hai dòng nối lại. Rẻ — cả tầng
     * {@code infrastructure} chỉ có một chỗ cần đến nó. Bộ lọc thô tới mức nó bắt cả một
     * <i>ví dụ</i> viết trong javadoc, nên đoạn trên cố ý mô tả bằng lời thay vì bằng mã.
     */
    private static final String KHONG_DAY_DUOC =
            "Không đẩy được sự kiện realtime cho submission {}: {}. Trang sẽ tự cập nhật ở nhịp polling kế tiếp.";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final ObjectMapper json;

    public RedisSubmissionEventBus(StringRedisTemplate redis,
                                   RedisMessageListenerContainer container,
                                   ObjectMapper json) {
        this.redis = redis;
        this.container = container;
        this.json = json;
    }

    @Override
    public void publish(SubmissionEvent event) {
        try {
            redis.convertAndSend(channel(event.submissionId()), json.writeValueAsString(event));
        } catch (RuntimeException e) {
            // Xem javadoc của class: mất một thông báo là chuyện nhỏ, hỏng đường ghi verdict
            // thì không. KHÔNG đổi thành ném lại, dù trông "sạch" hơn.
            log.warn(KHONG_DAY_DUOC, event.submissionId(), e.toString());
        }
    }

    @Override
    public AutoCloseable subscribe(long submissionId, SubmissionEventListener listener) {
        ChannelTopic topic = ChannelTopic.of(channel(submissionId));
        MessageListener adapter = new SubmissionMessageListener(listener);
        container.addMessageListener(adapter, topic);
        // Trả về chính lệnh huỷ. Không có nó thì mỗi lần người dùng F5 để lại một listener
        // sống mãi, và sau một buổi contest thì container giao mỗi thông điệp cho hàng nghìn
        // listener của những kết nối đã chết từ lâu.
        return () -> container.removeMessageListener(adapter, topic);
    }

    private static String channel(long submissionId) {
        return CHANNEL_PREFIX + submissionId;
    }

    /**
     * Một thông điệp hỏng không được phép giết luồng giao thông điệp của container — nếu
     * không thì một sự kiện dị dạng làm <b>mọi</b> kết nối SSE của instance đó câm lặng.
     */
    private final class SubmissionMessageListener implements MessageListener {

        private final SubmissionEventListener delegate;

        private SubmissionMessageListener(SubmissionEventListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            try {
                delegate.onEvent(json.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8),
                        SubmissionEvent.class));
            } catch (RuntimeException e) {
                log.warn("Bỏ qua một sự kiện realtime không đọc được: {}", e.toString());
            }
        }
    }
}
