package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeJobPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hiện thực M1 của {@link JudgeJobPublisher}: <b>chỉ ghi log</b>.
 *
 * <h2>Vì sao "không làm gì" lại là một hiện thực hợp lệ</h2>
 * Vì hàng đã nằm trong {@code judge_queue} và đã commit trước khi ai đó gọi tới đây. Worker
 * PULL theo nhịp của nó và sẽ thấy hàng đó ở lần claim kế tiếp. Việc publish chỉ rút ngắn độ
 * trễ từ "một nhịp poll" xuống vài mili giây — nó là <b>tối ưu</b>, không phải cơ chế giao việc.
 *
 * <p>Đó chính là điều làm cho Bước 6.4 (chuyển sang RabbitMQ) chỉ chạm hai file. Nếu một ngày
 * nào đó việc bỏ lời gọi publish làm bài nộp không được chấm nữa, nghĩa là ai đó đã biến queue
 * từ đường dẫn thành kho chứa — và lúc đó R1 không còn được bảo đảm bởi Postgres nữa.
 */
public class NoopJudgeJobPublisher implements JudgeJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopJudgeJobPublisher.class);

    @Override
    public void publishEnqueued(long submissionId) {
        log.debug("submission {} đã vào judge_queue (M1: worker tự PULL, chưa có RabbitMQ)",
                submissionId);
    }

    /**
     * Đăng ký qua {@code @Bean} chứ không phải {@code @Component}, vì cùng lý do với
     * {@code DevSecurityConfig}: {@code @ConditionalOnMissingBean} chỉ đáng tin ở đây.
     * Ngày {@code RabbitJudgeJobPublisher} ra đời (Bước 6.4), bean này tự biến mất.
     */
    @Configuration
    public static class Registration {

        @Bean
        @ConditionalOnMissingBean(JudgeJobPublisher.class)
        public JudgeJobPublisher noopJudgeJobPublisher() {
            return new NoopJudgeJobPublisher();
        }
    }
}
