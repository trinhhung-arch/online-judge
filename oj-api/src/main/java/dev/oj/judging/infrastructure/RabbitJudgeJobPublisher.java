package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeJobPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class RabbitJudgeJobPublisher implements JudgeJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitJudgeJobPublisher.class);

    public static final String EXCHANGE = "oj.judge";
    public static final String EXCHANGE_CHET = "oj.judge.dlx";
    public static final String HANG_LIVE = "judge.live";
    public static final String HANG_REJUDGE = "judge.rejudge";
    public static final String HANG_CHET = "judge.dead";

    private static final int TRAN_GIAO_LAI = 3;

    private static final long NGHI_SAU_KHI_HONG_MS = 10_000;

    private final AmqpTemplate amqp;

    private volatile long nghiToi;

    public RabbitJudgeJobPublisher(AmqpTemplate amqp) {
        this.amqp = amqp;
    }

    @Override
    public void publishEnqueued(long submissionId) {
        goCua(HANG_LIVE, submissionId);
    }

    @Override
    public void publishRejudgeEnqueued(long submissionId) {
        goCua(HANG_REJUDGE, submissionId);
    }

    private void goCua(String hangDoi, long submissionId) {
        long bayGio = System.currentTimeMillis();
        if (bayGio < nghiToi) {
            return;
        }
        try {
            amqp.convertAndSend(EXCHANGE, hangDoi, Long.toString(submissionId), m -> {
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
                return m;
            });
            if (nghiToi != 0) {
                nghiToi = 0;
                log.info("RabbitMQ đã trở lại — gõ cửa hoạt động lại.");
            }
            log.trace("Đã gõ cửa {} cho submission {}", hangDoi, submissionId);
        } catch (RuntimeException e) {
            if (nghiToi == 0) {
                log.warn("Không gõ cửa được ({}). Ngừng thử trong {}ms; bài nộp vẫn vào "
                        + "judge_queue và worker sẽ nhặt theo nhịp poll.",
                        e.toString(), NGHI_SAU_KHI_HONG_MS);
            }
            nghiToi = System.currentTimeMillis() + NGHI_SAU_KHI_HONG_MS;
        }
    }

    @ConditionalOnProperty(name = "oj.judge.rabbit.enabled", havingValue = "true",
            matchIfMissing = true)
    @Configuration
    public static class Topology {

        @Bean
        public RabbitJudgeJobPublisher rabbitJudgeJobPublisher(AmqpTemplate amqp) {
            return new RabbitJudgeJobPublisher(amqp);
        }

        @Bean
        public DirectExchange judgeExchange() {
            return new DirectExchange(EXCHANGE, true, false);
        }

        @Bean
        public FanoutExchange judgeDeadExchange() {
            return new FanoutExchange(EXCHANGE_CHET, true, false);
        }

        private static Queue hangCham(String ten) {
            return QueueBuilder.durable(ten)
                    .quorum()
                    .deadLetterExchange(EXCHANGE_CHET)
                    .withArgument("x-delivery-limit", TRAN_GIAO_LAI)
                    .build();
        }

        @Bean
        public Queue hangLive() {
            return hangCham(HANG_LIVE);
        }

        @Bean
        public Queue hangRejudge() {
            return hangCham(HANG_REJUDGE);
        }

        @Bean
        public Queue hangChet() {
            return QueueBuilder.durable(HANG_CHET).quorum().build();
        }

        @Bean
        public Declarables judgeBindings(DirectExchange judgeExchange,
                                         FanoutExchange judgeDeadExchange, Queue hangLive,
                                         Queue hangRejudge, Queue hangChet) {
            return new Declarables(
                    BindingBuilder.bind(hangLive).to(judgeExchange).with(HANG_LIVE),
                    BindingBuilder.bind(hangRejudge).to(judgeExchange).with(HANG_REJUDGE),
                    BindingBuilder.bind(hangChet).to(judgeDeadExchange));
        }

        @Bean
        public RabbitTopologyDeclarer rabbitTopologyDeclarer(ConnectionFactory connectionFactory) {
            return new RabbitTopologyDeclarer(connectionFactory);
        }
    }
}
