package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.LoginRateLimiter;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * {@code SET NX PX} như {@code RedisSubmissionRateLimiter} — nguyên tử, một lệnh.
 *
 * <h2>★ Redis chết thì CHO QUA, ngược với rate limit đăng ký</h2>
 * Ba hàng rào trong dự án này hỏng theo ba kiểu khác nhau, và mỗi kiểu có lý do riêng:
 * <ul>
 *   <li><b>Nộp bài</b> → chuyển sang Postgres. Không được mất bài nộp.</li>
 *   <li><b>Đăng ký</b> → TỪ CHỐI. Không có đường dự phòng thật, và cho qua là mở đúng cái
 *       cửa ấy vào lúc hệ thống yếu nhất. Hoãn đăng ký thì người ta bấm lại.</li>
 *   <li><b>Đăng nhập</b> (đây) → CHO QUA. Từ chối nghĩa là khoá <i>toàn bộ người dùng</i> ra
 *       khỏi hệ thống mỗi khi Redis chớp — biến một sự cố cache thành một sự cố toàn diện.
 *       Và trần CPU vẫn còn nguyên ở {@code bcrypt-concurrency}, thứ không phụ thuộc Redis.</li>
 * </ul>
 * Ba lựa chọn khác nhau không phải vì thiếu nhất quán, mà vì cái mất khi hỏng khác nhau.
 */
@Component
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginRateLimiter.class);

    private static final String KHOA_PREFIX = "oj:ratelimit:login:";
    private static final String CHIEM_CHO = "1";

    private final StringRedisTemplate redis;
    private final Duration khoangCach;

    public RedisLoginRateLimiter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.khoangCach = properties.auth().loginMinInterval();
    }

    @Override
    public void kiemVaGhiNhan(long userId) {
        if (khoangCach.isZero()) {
            return;
        }
        Boolean chiemDuoc;
        try {
            chiemDuoc = redis.opsForValue().setIfAbsent(
                    KHOA_PREFIX + userId, CHIEM_CHO, khoangCach.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.warn("Redis không dùng được cho rate limit đăng nhập: {}. Cho qua — "
                    + "xem javadoc để biết vì sao không từ chối.", e.toString());
            return;
        }
        if (Boolean.FALSE.equals(chiemDuoc)) {
            throw IdentityException.dangNhapQuaNhanh(khoangCach);
        }
    }
}
