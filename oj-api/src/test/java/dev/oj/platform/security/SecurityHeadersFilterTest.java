package dev.oj.platform.security;

import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.config.SecurityHeadersProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Access token nằm trong {@code localStorage}, nên một lỗ XSS là một tài khoản bị chiếm.
 * Những ca dưới đây giữ cho lưới thứ hai không bị tháo mất trong một lần dọn dẹp.
 */
class SecurityHeadersFilterTest {

    private static final String CSP_THU = "default-src 'none'; script-src 'self'";

    private MockHttpServletResponse chay(SecurityHeadersProperties properties) throws Exception {
        var response = new MockHttpServletResponse();
        new SecurityHeadersFilter(AppPropertiesGia.voiSecurityHeaders(properties))
                .doFilter(new MockHttpServletRequest("GET", "/api/v1/problems"),
                        response, (req, res) -> { });
        return response;
    }

    @Test
    @DisplayName("mọi response mang đủ năm header, và CSP đúng chuỗi trong config")
    void gan_du_header() throws Exception {
        var response = chay(new SecurityHeadersProperties(CSP_THU, Duration.ZERO));

        assertThat(response.getHeader(SecurityHeadersFilter.CSP)).isEqualTo(CSP_THU);
        assertThat(response.getHeader(SecurityHeadersFilter.NOSNIFF)).isEqualTo("nosniff");
        assertThat(response.getHeader(SecurityHeadersFilter.KHUNG)).isEqualTo("DENY");
        assertThat(response.getHeader(SecurityHeadersFilter.REFERRER)).isNotBlank();
        assertThat(response.getHeader(SecurityHeadersFilter.QUYEN)).contains("camera=()");
    }

    @Test
    @DisplayName("★ HSTS TẮT khi max-age = 0 — bật nhầm trên localhost là tự khoá máy dev")
    void hsts_tat_khi_khong_cau_hinh() throws Exception {
        var response = chay(new SecurityHeadersProperties(CSP_THU, Duration.ZERO));

        assertThat(response.getHeader(SecurityHeadersFilter.HSTS))
                .as("trình duyệt NHỚ HSTS kể cả sau khi header biến mất; gỡ ra phải vào "
                        + "chrome://net-internals/#hsts")
                .isNull();
    }

    @Test
    @DisplayName("HSTS bật khi có max-age, kèm includeSubDomains")
    void hsts_bat_khi_cau_hinh() throws Exception {
        var response = chay(new SecurityHeadersProperties(CSP_THU, Duration.ofDays(365)));

        assertThat(response.getHeader(SecurityHeadersFilter.HSTS))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    @DisplayName("★ header đặt TRƯỚC chain — nếu không, SSE (đã commit response) sẽ không có")
    void dat_truoc_chain() throws Exception {
        var response = new MockHttpServletResponse();
        var filter = new SecurityHeadersFilter(AppPropertiesGia.voiSecurityHeaders(
                new SecurityHeadersProperties(CSP_THU, Duration.ZERO)));

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/submissions/1/theo-doi"),
                response, (req, res) ->
                        // Bên trong chain, header PHẢI đã có mặt rồi. Đây là điều kiện để một
                        // response giữ mở lâu (SSE) vẫn được bảo vệ: sau khi byte đầu tiên đi
                        // ra, setHeader bị container bỏ qua trong im lặng.
                        assertThat(((MockHttpServletResponse) res)
                                .getHeader(SecurityHeadersFilter.CSP)).isEqualTo(CSP_THU));

        assertThat(response.getHeader(SecurityHeadersFilter.CSP)).isEqualTo(CSP_THU);
    }

    @Test
    @DisplayName("CSP rỗng thì KHÔNG khởi động được — im lặng mất lưới là kiểu hỏng tệ nhất")
    void csp_rong_thi_crash() {
        assertThatThrownBy(() -> new SecurityHeadersProperties("  ", Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localStorage");
    }
}
