package dev.oj.identity.infrastructure;

import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisGioiHanIdentityTest {

    private static final String IP = "203.0.113.7";
    private static final String KHOA_DANG_KY = "oj:ratelimit:register:" + IP;
    private static final long USER = 42;
    private static final String KHOA_DANG_NHAP = "oj:ratelimit:login:" + USER;

    private final AppProperties props = AppPropertiesGia.macDinh();

    @Mock
    ValueOperations<String, String> ops;

    private final class RedisGia extends StringRedisTemplate {

        final List<Long> demTraVe = new ArrayList<>();
        final List<String> loiGoiScript = new ArrayList<>();
        long ttlConLaiGiay;
        RuntimeException chet;

        @Override
        public ValueOperations<String, String> opsForValue() {
            if (chet != null) {
                throw chet;
            }
            return ops;
        }

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            if (chet != null) {
                throw chet;
            }
            loiGoiScript.add(keys + " " + List.of(args));
            return script.getResultType().cast(demTraVe.removeFirst());
        }

        @Override
        public Long getExpire(String key) {
            return ttlConLaiGiay;
        }
    }

    private static RedisGia redisChet(RedisGia redis) {
        redis.chet = new RedisConnectionFailureException("giả lập: Redis không phản hồi");
        return redis;
    }

    @Test
    @DisplayName("★ GH-05 · đăng ký khi Redis chết → TỪ CHỐI 429, không cho qua")
    void dang_ky_redis_chet_thi_tu_choi() {
        var hangRao = new RedisRegistrationRateLimiter(redisChet(new RedisGia()), props);

        assertThatThrownBy(() -> hangRao.kiemVaGhiNhan(IP))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                .hasFieldOrPropertyWithValue("code", "identity.dang_ky_qua_nhieu")
                .hasFieldOrPropertyWithValue("retryAfter", Duration.ofHours(1));
    }

    @Test
    @DisplayName("đăng ký: mỗi lượt là MỘT script nguyên tử (đếm + đặt hạn), đúng khoá, cửa sổ 1 giờ tính bằng ms")
    void dang_ky_mot_script_nguyen_tu() {
        var redis = new RedisGia();
        redis.demTraVe.add(1L);

        new RedisRegistrationRateLimiter(redis, props).kiemVaGhiNhan(IP);

        assertThat(redis.loiGoiScript)
                .containsExactly("[" + KHOA_DANG_KY + "] [" + Duration.ofHours(1).toMillis() + "]");
    }

    @Test
    @DisplayName("đăng ký: lượt 10 qua; lượt 11 bị chặn với đúng thời gian còn lại của khoá")
    void dang_ky_luot_10_qua_luot_11_bi_chan() {
        var redis = new RedisGia();
        redis.ttlConLaiGiay = 1_234;
        redis.demTraVe.addAll(List.of(10L, 11L));
        var hangRao = new RedisRegistrationRateLimiter(redis, props);

        assertThatCode(() -> hangRao.kiemVaGhiNhan(IP)).doesNotThrowAnyException();
        assertThatThrownBy(() -> hangRao.kiemVaGhiNhan(IP))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("code", "identity.dang_ky_qua_nhieu")
                .hasFieldOrPropertyWithValue("retryAfter", Duration.ofSeconds(1_234));
    }

    @Test
    @DisplayName("★ GH-06 · giãn nhịp đăng nhập khi Redis chết → CHO QUA, người dùng vẫn đăng nhập được")
    void dang_nhap_redis_chet_thi_cho_qua() {
        var hangRao = new RedisLoginRateLimiter(redisChet(new RedisGia()), props);

        assertThatCode(() -> hangRao.kiemVaGhiNhan(USER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("đăng nhập: Redis sống mà khoá còn giữ chỗ → 429 identity.dang_nhap_qua_nhanh, chờ đúng 2 giây")
    void dang_nhap_qua_nhanh_thi_429() {
        when(ops.setIfAbsent(KHOA_DANG_NHAP, "1", 2_000, TimeUnit.MILLISECONDS)).thenReturn(false);
        var hangRao = new RedisLoginRateLimiter(new RedisGia(), props);

        assertThatThrownBy(() -> hangRao.kiemVaGhiNhan(USER))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                .hasFieldOrPropertyWithValue("code", "identity.dang_nhap_qua_nhanh")
                .hasFieldOrPropertyWithValue("retryAfter", Duration.ofSeconds(2));
    }
}
