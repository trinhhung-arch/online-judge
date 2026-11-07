package dev.oj.judging.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

public class RabbitTopologyDeclarer {

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyDeclarer.class);

    private final ConnectionFactory connectionFactory;

    public RabbitTopologyDeclarer(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void khaiBaoLucKhoiDong() {
        try {
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
