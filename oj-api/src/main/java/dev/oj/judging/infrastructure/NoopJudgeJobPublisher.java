package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeJobPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
     * ★ M6 đã sửa điều kiện ở đây, và đó là một lỗi thật chứ không phải dọn dẹp.
     *
     * <p>Bản M1 chỉ có {@code @ConditionalOnMissingBean(JudgeJobPublisher.class)}, với ghi chú
     * "ngày {@code RabbitJudgeJobPublisher} ra đời thì bean này tự biến mất". Nó <b>không</b>
     * tự biến mất một cách đáng tin: {@code @ConditionalOnMissingBean} chỉ có bảo đảm thứ tự
     * trong auto-configuration, còn hai lớp {@code @Configuration} do component scan tìm thấy
     * thì được xử lý theo thứ tự không cam kết. Kết quả có thể là bean Noop thắng dù RabbitMQ
     * đã bật — và triệu chứng là <i>độ trễ chấm bài lặng lẽ quay về nhịp poll</i>, thứ không
     * có test nào bắt được vì mọi bài vẫn được chấm đúng.
     *
     * <p>Một thuộc tính, hai giá trị đối nhau. Đọc {@code application.yml} là biết transport
     * nào đang chạy, không phải đọc thứ tự quét package.
     */
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
