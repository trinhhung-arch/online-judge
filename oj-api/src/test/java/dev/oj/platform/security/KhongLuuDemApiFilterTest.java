package dev.oj.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phạm vi thật (biến thể đường dẫn, lỗi, file tĩnh) nằm ở {@code KhongLuuDemApiHttpIT} — ở đây
 * chỉ giữ hai điều không cần Tomcat mới thấy.
 */
class KhongLuuDemApiFilterTest {

    @Test
    @DisplayName("★ header đặt TRƯỚC chain — nếu không, SSE (đã commit response) sẽ không có")
    void dat_truoc_chain() throws Exception {
        var response = new MockHttpServletResponse();

        new KhongLuuDemApiFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/submissions/1/stream"),
                response, (req, res) -> assertThat(((MockHttpServletResponse) res)
                        .getHeader("Cache-Control")).isEqualTo("no-store"));

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("đăng ký đúng một mẫu /api/v1/*, đứng trước GioiHanApiFilter — 429 cũng phải mang header")
    void dang_ky_dung_pham_vi() {
        FilterRegistrationBean<KhongLuuDemApiFilter> dk =
                new KhongLuuDemApiFilter.Registration().khongLuuDemApiFilter();

        assertThat(dk.getUrlPatterns()).containsExactly("/api/v1/*");
        assertThat(dk.getOrder())
                .as("GioiHanApiFilter tự viết 429 và không gọi chain — đứng sau nó là 429 không có header")
                .isLessThan(GioiHanApiFilter.THU_TU);
    }
}
