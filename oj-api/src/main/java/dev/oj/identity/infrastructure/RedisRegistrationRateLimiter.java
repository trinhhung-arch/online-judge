package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.RegistrationRateLimiter;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisRegistrationRateLimiter implements RegistrationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRegistrationRateLimiter.class);

    private static final String KHOA_PREFIX = "oj:ratelimit:register:";

    private static final RedisScript<Long> DEM_VA_DAT_HAN = RedisScript.of("""
            local dem = redis.call('INCR', KEYS[1])
            if redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return dem
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int toiDa;
    private final Duration cuaSo;

    public RedisRegistrationRateLimiter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.toiDa = properties.auth().maxRegistrationsPerIp();
        this.cuaSo = properties.auth().registrationWindow();
    }

    @Override
    public void kiemVaGhiNhan(String clientIp) {
        String khoa = KHOA_PREFIX + clientIp;
        Long dem;
        try {
            dem = redis.execute(DEM_VA_DAT_HAN, List.of(khoa), String.valueOf(cuaSo.toMillis()));
        } catch (RuntimeException e) {
            log.warn("Redis không dùng được cho rate limit đăng ký: {}. Từ chối lượt này — "
                    + "xem javadoc để biết vì sao không cho qua.", e.toString());
            throw IdentityException.quaNhieuDangKy(cuaSo);
        }
        if (dem != null && dem > toiDa) {
            throw IdentityException.quaNhieuDangKy(conLai(khoa));
        }
    }

    private Duration conLai(String khoa) {
        try {
            Long giay = redis.getExpire(khoa);
            return giay != null && giay > 0 ? Duration.ofSeconds(giay) : cuaSo;
        } catch (RuntimeException e) {
            return cuaSo;
        }
    }
}
