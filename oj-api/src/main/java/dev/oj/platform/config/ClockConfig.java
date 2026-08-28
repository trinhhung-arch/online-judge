package dev.oj.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Một {@link Clock} duy nhất cho cả hệ thống, tiêm vào mọi use-case cần biết "bây giờ".
 *
 * <h2>Vì sao không gọi thẳng {@code Instant.now()}</h2>
 * Vì mọi bất biến quan trọng của {@code judging} đều là bất biến về <b>thời gian</b>: lease
 * hết hạn sau 120 giây, reaper chạy mỗi 15 giây, rate limit 10 giây. Test những thứ đó với
 * đồng hồ thật nghĩa là hoặc {@code Thread.sleep(120_000)}, hoặc không test.
 *
 * <p>Với bean này, {@code Clock.fixed(...)} trong test cho phép hỏi thẳng: "đúng một phần
 * nghìn giây trước hạn thì reaper có nhặt không?" — câu hỏi mà chaos test sẽ không bao giờ
 * hỏi được vì nó không điều khiển được thời gian.
 *
 * <p>{@code systemUTC} chứ không phải {@code systemDefaultZone}: mọi cột thời gian trong
 * schema là {@code TIMESTAMPTZ}, và máy dev (WSL, giờ VN) khác host (Mac) là chuyện thường.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
