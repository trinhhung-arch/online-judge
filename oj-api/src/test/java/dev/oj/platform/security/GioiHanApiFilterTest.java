package dev.oj.platform.security;

import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bộ lọc này đứng trước MỌI endpoint công khai, nên hai hướng hỏng của nó đều tệ như nhau: chặn
 * nhầm là cả site 429, không chặn là trần không tồn tại. Mỗi test dưới đây giữ một hướng.
 */
class GioiHanApiFilterTest {

    private final List<String> khoaDaDem = new ArrayList<>();
    private long luotTraVe = 1;
    private RuntimeException loiDem;
    private AtomicBoolean daQua;
    private FilterChain chain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        daQua = new AtomicBoolean(false);
        chain = (req, res) -> daQua.set(true);
        request = new MockHttpServletRequest("GET", "/api/v1/problems");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        CurrentUserHolder.xoa();
    }

    private GioiHanApiFilter loc(int moiNguoi, int moiIp) {
        DemTocDo dem = (khoa, cuaSo) -> {
            khoaDaDem.add(khoa);
            if (loiDem != null) {
                throw loiDem;
            }
            return luotTraVe;
        };
        return new GioiHanApiFilter(dem, AppPropertiesGia.voiGioiHanApi(moiNguoi, moiIp));
    }

    @Test
    @DisplayName("đúng bằng trần → vẫn đi tiếp (trần là 'quá' mới chặn, không phải 'chạm')")
    void dung_bang_tran_thi_di_tiep() throws Exception {
        luotTraVe = 100;
        loc(100, 600).doFilter(request, response, chain);
        assertThat(daQua).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("★ vượt trần → 429 kèm Retry-After, và request KHÔNG tới controller")
    void vuot_tran_thi_429() throws Exception {
        CurrentUserHolder.dat(new CurrentUser(7L, "an", Role.USER));
        luotTraVe = 101;
        loc(100, 600).doFilter(request, response, chain);
        assertThat(daQua).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString())
                .contains("api.rate_limited")
                .contains("retryAfterSeconds");
        assertThat(response.getContentType()).startsWith("application/json");
    }

    @Test
    @DisplayName("★ người đã đăng nhập đếm theo userId, không theo IP — hai người sau một NAT không chặn nhau")
    void dang_nhap_thi_dem_theo_user() throws Exception {
        CurrentUserHolder.dat(new CurrentUser(7L, "an", Role.USER));
        request.addHeader("CF-Connecting-IP", "203.0.113.9");
        loc(100, 600).doFilter(request, response, chain);
        assertThat(khoaDaDem).containsExactly("u:7");
    }

    @Test
    @DisplayName("★ ẩn danh đếm theo IP và dùng TRẦN IP — trần người dùng không áp cho khách")
    void an_danh_thi_dem_theo_ip() throws Exception {
        request.addHeader("CF-Connecting-IP", "203.0.113.9");
        luotTraVe = 500;
        loc(1, 600).doFilter(request, response, chain);   // trần người = 1, nhưng khách đi theo xô IP = 600
        assertThat(khoaDaDem).containsExactly("ip:203.0.113.9");
        assertThat(daQua).isTrue();
    }

    @Test
    @DisplayName("★ đếm hỏng (Redis chết) → CHO QUA, không biến sự cố hạ tầng thành mất dịch vụ")
    void dem_hong_thi_cho_qua() throws Exception {
        loiDem = new IllegalStateException("Redis chết");
        luotTraVe = 999_999;
        loc(100, 600).doFilter(request, response, chain);
        assertThat(daQua).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("trần 0 = tắt hẳn: không gọi bộ đếm lần nào (stack đo tải dùng nhánh này)")
    void tran_0_thi_khong_dem() throws Exception {
        loc(0, 0).doFilter(request, response, chain);
        assertThat(khoaDaDem).isEmpty();
        assertThat(daQua).isTrue();
    }

    @Test
    @DisplayName("★ /internal/** và file tĩnh KHÔNG bị áp trần — bóp cửa worker là bóp đường ghi verdict")
    void ngoai_api_v1_thi_khong_ap() {
        GioiHanApiFilter filter = loc(100, 600);
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/internal/judge/result"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/index.html"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/problems"))).isFalse();
    }
}
