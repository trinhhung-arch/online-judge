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
import org.springframework.data.redis.core.types.Expiration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * ★ Hai hàng rào Redis của {@code identity}, và chúng hỏng theo hai hướng NGƯỢC nhau — có chủ
 * ý. Thiết kế: {@code Claude outputs/identity-test-cases.md}, ca GH-05 và GH-06.
 *
 * <ul>
 *   <li><b>Đăng ký: Redis chết thì ĐÓNG cửa.</b> Cho qua nghĩa là một bot chỉ cần làm Redis
 *       nghẹt là tạo được tài khoản không giới hạn ({@link RedisRegistrationRateLimiter}).</li>
 *   <li><b>Đăng nhập: Redis chết thì MỞ cửa.</b> Giãn nhịp 2 giây chỉ bớt số dòng
 *       {@code refresh_tokens}; chặn ở đây là biến một sự cố hạ tầng thành "không ai đăng nhập
 *       được" ({@link RedisLoginRateLimiter}).</li>
 * </ul>
 *
 * <p>Một lần refactor gom hai lớp về chung một kiểu xử lý lỗi sẽ lật một trong hai hướng mà
 * không test nào khác đỏ: IT chạy trên Redis thật, còn unit test của use-case dùng hàng rào
 * giả. Lớp này giữ cả hai hướng ở cạnh nhau.
 */
@ExtendWith(MockitoExtension.class)
class RedisGioiHanIdentityTest {

    private static final String IP = "203.0.113.7";
    private static final String KHOA_DANG_KY = "oj:ratelimit:register:" + IP;
    private static final long USER = 42;
    private static final String KHOA_DANG_NHAP = "oj:ratelimit:login:" + USER;

    private final AppProperties props = AppPropertiesGia.macDinh();

    @Mock
    ValueOperations<String, String> ops;

    /**
     * {@link StringRedisTemplate} viết tay thay vì để Mockito giả: {@code expire(K, Duration)}
     * là một phương thức {@code default} của {@code RedisOperations}, và mock có chặn được nó
     * hay không là tuỳ mock maker. Đè đúng {@code expire(K, Expiration)} — chỗ phương thức
     * default ấy gọi vào — thì phép kiểm không phụ thuộc vào điều đó.
     */
    private final class RedisGia extends StringRedisTemplate {

        final List<Long> hanDaDatMs = new ArrayList<>();
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
        public Boolean expire(String key, Expiration expiration) {
            assertThat(key).isEqualTo(KHOA_DANG_KY);
            hanDaDatMs.add(expiration.getExpirationTimeInMilliseconds());
            return true;
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

    // ---- Đăng ký: đóng cửa -------------------------------------------------------------

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
    @DisplayName("đăng ký: lượt ĐẦU của cửa sổ đặt hạn 1 giờ cho khoá — quên bước này là khoá sống mãi")
    void dang_ky_luot_dau_dat_han() {
        var redis = new RedisGia();
        when(ops.increment(KHOA_DANG_KY)).thenReturn(1L);

        new RedisRegistrationRateLimiter(redis, props).kiemVaGhiNhan(IP);

        assertThat(redis.hanDaDatMs).containsExactly(Duration.ofHours(1).toMillis());
    }

    @Test
    @DisplayName("đăng ký: lượt 10 qua, không đặt lại hạn; lượt 11 bị chặn với đúng thời gian còn lại của khoá")
    void dang_ky_luot_10_qua_luot_11_bi_chan() {
        var redis = new RedisGia();
        redis.ttlConLaiGiay = 1_234;
        when(ops.increment(KHOA_DANG_KY)).thenReturn(10L, 11L);
        var hangRao = new RedisRegistrationRateLimiter(redis, props);

        assertThatCode(() -> hangRao.kiemVaGhiNhan(IP)).doesNotThrowAnyException();
        assertThatThrownBy(() -> hangRao.kiemVaGhiNhan(IP))
                .isInstanceOf(IdentityException.class)
                .hasFieldOrPropertyWithValue("code", "identity.dang_ky_qua_nhieu")
                .hasFieldOrPropertyWithValue("retryAfter", Duration.ofSeconds(1_234));
        assertThat(redis.hanDaDatMs).as("chỉ lượt đầu của cửa sổ mới đặt hạn").isEmpty();
    }

    // ---- Đăng nhập: mở cửa -------------------------------------------------------------

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
