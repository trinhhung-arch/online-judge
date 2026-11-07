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

@Component
public class RedisSubmissionEventBus implements SubmissionEventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisSubmissionEventBus.class);

    private static final String CHANNEL_PREFIX = "oj:submission:";

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
            log.warn(KHONG_DAY_DUOC, event.submissionId(), e.toString());
        }
    }

    @Override
    public AutoCloseable subscribe(long submissionId, SubmissionEventListener listener) {
        ChannelTopic topic = ChannelTopic.of(channel(submissionId));
        MessageListener adapter = new SubmissionMessageListener(listener);
        container.addMessageListener(adapter, topic);
        return () -> container.removeMessageListener(adapter, topic);
    }

    private static String channel(long submissionId) {
        return CHANNEL_PREFIX + submissionId;
    }

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
