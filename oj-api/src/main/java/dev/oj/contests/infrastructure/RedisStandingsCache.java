package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Primary
@Component
public class RedisStandingsCache implements StandingsReader {

    private static final Logger log = LoggerFactory.getLogger(RedisStandingsCache.class);

    private static final String REDIS_CHET =
            "Cache bảng xếp hạng không dùng được: {}. Đọc thẳng Postgres — chậm hơn, vẫn đúng.";

    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcStandingsReader postgres;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisStandingsCache(JdbcStandingsReader postgres, StringRedisTemplate redis,
                               ObjectMapper json) {
        this.postgres = postgres;
        this.redis = redis;
        this.json = json;
    }

    @Override
    public List<Dong> top(long contestId, int n, boolean dongBang) {
        String khoa = StandingsKeys.zset(contestId, dongBang);
        try {
            List<Dong> tuCache = docCache(khoa, n);
            if (!tuCache.isEmpty()) {
                return tuCache;
            }
            List<Dong> tuDb = postgres.top(contestId, n, dongBang);
            napCache(khoa, tuDb);
            return tuDb;
        } catch (RuntimeException e) {
            log.warn(REDIS_CHET, e.toString());
            return postgres.top(contestId, n, dongBang);
        }
    }

    @Override
    public Optional<Dong> cuaNguoi(long contestId, long userId, boolean dongBang) {
        return postgres.cuaNguoi(contestId, userId, dongBang);
    }

    @Override
    public Optional<Integer> hang(long contestId, long userId, boolean dongBang) {
        return postgres.hang(contestId, userId, dongBang);
    }

    private List<Dong> docCache(String khoa, int n) {
        Set<String> cac = redis.opsForZSet().reverseRange(khoa, 0, n - 1L);
        if (cac == null || cac.isEmpty()) {
            return List.of();
        }
        List<Dong> ketQua = new ArrayList<>(cac.size());
        for (String s : cac) {
            ketQua.add(json.readValue(s, Dong.class));
        }
        return ketQua;
    }

    private void napCache(String khoa, List<Dong> cac) {
        if (cac.isEmpty()) {
            return;
        }
        try {
            redis.opsForZSet().add(khoa, StandingsKeys.dongThanhTuple(cac, json));
            redis.expire(khoa, TTL);
        } catch (RuntimeException e) {
            log.warn(REDIS_CHET, e.toString());
        }
    }
}
