package dev.oj.contests.infrastructure;

import dev.oj.contests.application.StandingsEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

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
            redis.opsForValue().increment(StandingsKeys.phienBan(contestId));
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
