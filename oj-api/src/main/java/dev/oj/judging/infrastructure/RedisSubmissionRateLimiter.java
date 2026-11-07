package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SubmissionRateLimiter;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class RedisSubmissionRateLimiter implements SubmissionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSubmissionRateLimiter.class);

    private static final String REDIS_CHET =
            "Redis không dùng được cho rate limit nộp bài: {}. Chuyển sang đường dự phòng Postgres.";

    private static final String KHOA_PREFIX = "oj:ratelimit:submit:";

    private static final String CHIEM_CHO = "1";

    private final StringRedisTemplate redis;
    private final SubmissionRepository submissions;
    private final Duration cuaSo;
    private final Clock clock;

    public RedisSubmissionRateLimiter(StringRedisTemplate redis,
                                      SubmissionRepository submissions,
                                      AppProperties properties,
                                      Clock clock) {
        this.redis = redis;
        this.submissions = submissions;
        this.cuaSo = properties.submission().rateLimit();
        this.clock = clock;
    }

    @Override
    public void kiemTraVaGhiNhan(long userId) {
        if (cuaSo.isZero() || cuaSo.isNegative()) {
            return;
        }
        try {
            quaRedis(userId);
        } catch (JudgingException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn(REDIS_CHET, e.toString());
            quaPostgres(userId);
        }
    }

    private void quaRedis(long userId) {
        String khoa = khoa(userId);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(khoa, CHIEM_CHO, cuaSo))) {
            return;
        }
        Long conLaiMs = redis.getExpire(khoa, TimeUnit.MILLISECONDS);
        if (conLaiMs == null || conLaiMs <= 0) {
            return;
        }
        throw JudgingException.nopQuaNhanh(Duration.ofMillis(conLaiMs));
    }

    private void quaPostgres(long userId) {
        Instant ganNhat = submissions.lastSubmittedAt(userId).orElse(null);
        if (ganNhat == null) {
            return;
        }
        Duration daTroi = Duration.between(ganNhat, clock.instant());
        if (daTroi.isNegative() || daTroi.compareTo(cuaSo) >= 0) {
            return;
        }
        throw JudgingException.nopQuaNhanh(cuaSo.minus(daTroi));
    }

    private static String khoa(long userId) {
        return KHOA_PREFIX + userId;
    }
}
