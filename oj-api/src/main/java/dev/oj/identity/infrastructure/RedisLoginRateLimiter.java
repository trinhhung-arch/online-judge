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
