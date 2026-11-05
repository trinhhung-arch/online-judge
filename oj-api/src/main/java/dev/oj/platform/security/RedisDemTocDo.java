package dev.oj.platform.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
public class RedisDemTocDo implements DemTocDo {

    private static final String TIEN_TO = "oj:ratelimit:api:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisDemTocDo(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public long tang(String khoa, Duration cuaSo) {
        long giay = Math.max(1, cuaSo.toSeconds());
        String o = TIEN_TO + khoa + ':' + (clock.instant().getEpochSecond() / giay);
        Long luot = redis.opsForValue().increment(o);
        redis.expire(o, Duration.ofSeconds(giay * 2));
        return luot == null ? 0 : luot;
    }
}
