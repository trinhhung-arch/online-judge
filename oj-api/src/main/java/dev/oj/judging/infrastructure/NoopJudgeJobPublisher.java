package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeJobPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class NoopJudgeJobPublisher implements JudgeJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopJudgeJobPublisher.class);

    @Override
    public void publishEnqueued(long submissionId) {
        log.debug("submission {} đã vào judge_queue (M1: worker tự PULL, chưa có RabbitMQ)",
                submissionId);
    }

    @ConditionalOnProperty(name = "oj.judge.rabbit.enabled", havingValue = "false")
    @Configuration
    public static class Registration {

        @Bean
        @ConditionalOnMissingBean(JudgeJobPublisher.class)
        public JudgeJobPublisher noopJudgeJobPublisher() {
            return new NoopJudgeJobPublisher();
        }
    }
}
