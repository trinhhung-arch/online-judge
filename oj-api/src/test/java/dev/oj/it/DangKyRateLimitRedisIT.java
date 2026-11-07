package dev.oj.it;

import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.infrastructure.RedisRegistrationRateLimiter;
import dev.oj.platform.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DangKyRateLimitRedisIT extends PostgresIT {

    private static final String IP = "203.0.113.77";
    private static final String KHOA = "oj:ratelimit:register:" + IP;

    @Autowired RedisRegistrationRateLimiter limiter;
    @Autowired AppProperties properties;

    @Test
    @DisplayName("★ khoá mồ côi không hạn (từ bản cũ) tự lành ở lượt kế tiếp")
    void khoa_mo_coi_tu_lanh() {
        int quaTran = properties.auth().maxRegistrationsPerIp() + 5;
        redis.opsForValue().set(KHOA, String.valueOf(quaTran));
        assertThat(redis.getExpire(KHOA)).as("tiền đề: khoá không có hạn").isEqualTo(-1L);

        assertThatThrownBy(() -> limiter.kiemVaGhiNhan(IP))
                .as("đã quá trần — lượt này vẫn bị chặn")
                .isInstanceOf(IdentityException.class);

        assertThat(redis.getExpire(KHOA))
                .as("nhưng khoá phải có hạn, nếu không IP này bị chặn vĩnh viễn")
                .isPositive()
                .isLessThanOrEqualTo(cuaSo().toSeconds());
    }

    @Test
    @DisplayName("lượt đầu mở cửa sổ mới với đúng hạn trong cấu hình")
    void luot_dau_mo_cua_so_co_han() {
        limiter.kiemVaGhiNhan(IP);

        assertThat(redis.opsForValue().get(KHOA)).isEqualTo("1");
        assertThat(redis.getExpire(KHOA))
                .isGreaterThan(cuaSo().minusSeconds(5).toSeconds())
                .isLessThanOrEqualTo(cuaSo().toSeconds());
    }

    @Test
    @DisplayName("★ khoá đã có hạn thì KHÔNG gia hạn — cửa sổ cố định, không trượt theo bot")
    void khong_gia_han_cua_so_dang_mo() {
        redis.opsForValue().set(KHOA, "1", Duration.ofSeconds(30));

        limiter.kiemVaGhiNhan(IP);

        assertThat(redis.opsForValue().get(KHOA)).isEqualTo("2");
        assertThat(redis.getExpire(KHOA))
                .as("gia hạn ở mọi lượt thì một bot gọi đều giữ cửa sổ mở mãi; "
                        + "mất hạn (-1) thì IP bị chặn mãi")
                .isBetween(1L, 30L);
    }

    @Test
    @DisplayName("đủ trần thì qua, lượt vượt trần thì từ chối kèm thời gian còn lại")
    void vuot_tran_thi_tu_choi() {
        int toiDa = properties.auth().maxRegistrationsPerIp();
        for (int i = 0; i < toiDa; i++) {
            assertThatCode(() -> limiter.kiemVaGhiNhan(IP)).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.kiemVaGhiNhan(IP))
                .isInstanceOfSatisfying(IdentityException.class, e ->
                        assertThat(e.retryAfter())
                                .isPositive()
                                .isLessThanOrEqualTo(cuaSo()));
    }

    private Duration cuaSo() {
        return properties.auth().registrationWindow();
    }
}
