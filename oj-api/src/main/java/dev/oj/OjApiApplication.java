package dev.oj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @ConfigurationPropertiesScan} để {@code AppProperties} được nạp và <b>kiểm ngay lúc
 * boot</b>: các compact constructor của nó đối chiếu ngưỡng trong {@code application.yml} với
 * hằng số trong {@code oj-contract} và ném lỗi nếu lệch. Thà không khởi động được còn hơn
 * chạy với hai con số 64KB khác nhau ở hai phía.
 *
 * <p>{@code @EnableScheduling} để {@code StaleJobReaper} chạy. Không có annotation này thì
 * {@code @Scheduled} bị bỏ qua <b>trong im lặng</b> — reaper không bao giờ chạy, bài kẹt ở
 * {@code JUDGING} nằm đó mãi mãi, và R1 mất bảo đảm mà không có lỗi nào được ném.
 */
@ConfigurationPropertiesScan
@EnableScheduling
@SpringBootApplication
public class OjApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OjApiApplication.class, args);
    }

}
