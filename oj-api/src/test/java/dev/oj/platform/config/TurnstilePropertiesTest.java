package dev.oj.platform.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Một hàng rào bật-nhưng-không-cấu-hình là hàng rào tệ nhất — nên thiếu cấu hình thì API không
 * khởi động. Ở đây giữ phần {@code hostnames}: thiếu nó thì token giải ở bất cứ đâu cũng qua.
 */
class TurnstilePropertiesTest {

    private static TurnstileProperties bat(List<String> hostnames) {
        return new TurnstileProperties(true, "site", "secret", hostnames,
                "https://vi-du.test/siteverify", Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("★ BẬT mà không có hostname nào → không khởi động")
    void bat_ma_thieu_hostnames_thi_crash() {
        assertThatThrownBy(() -> bat(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OJ_TURNSTILE_HOSTNAMES");
        assertThatThrownBy(() -> bat(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("BẬT mà có một mục rỗng (dấu phẩy thừa trong env) → không khởi động")
    void bat_ma_co_muc_rong_thi_crash() {
        assertThatThrownBy(() -> bat(List.of("oj.vi-du.test", " ")))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Prod đưa giá trị vào bằng MỘT chuỗi env ({@code OJ_TURNSTILE_HOSTNAMES=a,b}), không phải
     * một {@code List}. Ca này đi qua chính {@link Binder} của Spring Boot: dấu phẩy phải tách,
     * khoảng trắng phải được bỏ, và chuỗi rỗng khi bật phải làm API không khởi động.
     */
    @Test
    @DisplayName("★ chuỗi env có dấu phẩy được tách đúng; chuỗi rỗng khi BẬT thì không khởi động")
    void bind_tu_chuoi_env() {
        assertThat(bind("onlinejudge67.click, oj.vi-du.test").hostnames())
                .containsExactly("onlinejudge67.click", "oj.vi-du.test");
        assertThatThrownBy(() -> bind(""))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("OJ_TURNSTILE_HOSTNAMES");
    }

    private static TurnstileProperties bind(String hostnames) {
        var nguon = new MapConfigurationPropertySource(Map.of(
                "t.enabled", "true", "t.site-key", "site", "t.secret", "secret",
                "t.hostnames", hostnames, "t.verify-url", "https://vi-du.test/siteverify",
                "t.timeout", "3s"));
        return new Binder(nguon).bindOrCreate("t", TurnstileProperties.class);
    }

    @Test
    @DisplayName("TẮT thì không cần hostname — máy dev và bộ IT không có khoá Cloudflare")
    void tat_thi_khong_can() {
        var p = new TurnstileProperties(false, "", "", null,
                "https://vi-du.test/siteverify", Duration.ofSeconds(3));

        assertThat(p.hostnames()).isEmpty();
    }
}
