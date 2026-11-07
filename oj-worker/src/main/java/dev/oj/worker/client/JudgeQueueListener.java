package dev.oj.worker.client;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

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
            channel.basicNack(deliveryTag, false, true);
            log.warn("Không xử lý được một tiếng chuông, trả về hàng đợi: {}", e.toString());
        }
    }
}
