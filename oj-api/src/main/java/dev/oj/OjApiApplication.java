package dev.oj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} để {@code AppProperties} được nạp và <b>kiểm ngay lúc
 * boot</b>: các compact constructor của nó đối chiếu ngưỡng trong {@code application.yml} với
 * hằng số trong {@code oj-contract} và ném lỗi nếu lệch. Thà không khởi động được còn hơn
 * chạy với hai con số 64KB khác nhau ở hai phía.
 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class OjApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OjApiApplication.class, args);
    }

}
