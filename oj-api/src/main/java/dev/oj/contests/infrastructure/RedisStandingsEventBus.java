package dev.oj.contests.infrastructure;

import dev.oj.contests.application.StandingsEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * ★ Fan-out SSE cho bảng xếp hạng, và <b>xoá cache</b> — FR-CON-04, Bước 5.7.
 *
 * <h2>Một lời gọi làm hai việc, và thứ tự giữa chúng quan trọng</h2>
 * <ol>
 *   <li><b>Xoá cache trước.</b> Nếu đẩy tin trước, một client nhận tin rồi đọc lại ngay sẽ
 *       trúng cache cũ — và nó vừa được bảo là có gì đó mới. Kết quả tệ hơn không báo gì:
 *       người dùng thấy trang "cập nhật" mà số không đổi.</li>
 *   <li><b>Đẩy tin sau.</b> Lúc này mọi kết nối đọc lại đều thấy bản mới.</li>
 * </ol>
 *
 * <h2>Mọi lỗi bị nuốt, và đó là quyết định</h2>
 * Hàm này được gọi <b>sau khi bảng xếp hạng đã commit</b>. Ném lỗi ở đây không cứu được gì —
 * dữ liệu đã đúng và đã bền — mà chỉ có thể phá: một {@code RuntimeException} lọt lên làm hỏng
 * nhịp {@code StandingsUpdater}, và bảng xếp hạng của <i>mọi</i> kỳ thi đứng im.
 *
 * <p>Redis chết thì trang bảng xếp hạng chậm đi tới nhịp polling kế tiếp — fallback REST là
 * bắt buộc ở cả hai trang có SSE ({@code oj-api/CLAUDE.md} mục 4), chính vì ngày này.
 *
 * <p>{@code RedisMessageListenerContainer} là bean Spring Boot <b>đã</b> tự cấu hình. Tự khai
 * thêm một cái nữa là {@code NoUniqueBeanDefinitionException} lúc khởi động — bài học đã trả
 * giá bằng một lần cả bộ integration test đỏ ở M3.
 */
@Component
public class RedisStandingsEventBus implements StandingsEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisStandingsEventBus.class);

    private static final String KHONG_DAY_DUOC =
            "Không đẩy được sự kiện bảng xếp hạng cho contest {}: {}. Trang sẽ tự cập nhật ở nhịp polling kế tiếp.";

    private static final String KENH = "oj:standings-changed:";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;

    public RedisStandingsEventBus(StringRedisTemplate redis,
                                  RedisMessageListenerContainer container) {
        this.redis = redis;
        this.container = container;
    }

    @Override
    public void bangDaDoi(long contestId) {
        try {
            // Thứ tự bắt buộc: xoá cache TRƯỚC, đẩy tin SAU. Xem javadoc của class.
            redis.delete(StandingsKeys.zset(contestId, false));
            redis.delete(StandingsKeys.zset(contestId, true));
            redis.convertAndSend(kenh(contestId), String.valueOf(contestId));
        } catch (RuntimeException e) {
            log.warn(KHONG_DAY_DUOC, contestId, e.toString());
        }
    }

    @Override
    public AutoCloseable subscribe(long contestId, Runnable khiDoi) {
        ChannelTopic topic = ChannelTopic.of(kenh(contestId));
        MessageListener listener = (message, pattern) -> {
            try {
                khiDoi.run();
            } catch (RuntimeException e) {
                // Một kết nối hỏng không được phép giết luồng giao thông điệp của container —
                // nếu không thì mọi kết nối SSE của instance đó câm lặng.
                log.warn("Bỏ qua một sự kiện bảng xếp hạng không xử lý được: {}", e.toString());
            }
        };
        container.addMessageListener(listener, topic);
        return () -> container.removeMessageListener(listener, topic);
    }

    private static String kenh(long contestId) {
        return KENH + contestId;
    }
}
