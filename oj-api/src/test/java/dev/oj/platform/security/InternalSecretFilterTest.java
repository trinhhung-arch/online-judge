package dev.oj.platform.security;

import dev.oj.platform.config.AppProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bề mặt {@code /internal/judge/*} — ai qua được đây thì <b>ghi được verdict cho bất kỳ bài
 * nộp nào</b>.
 *
 * <p>{@code CLAUDE.md} mục 6 bắt mọi endpoint mới phải có test phân quyền, với yêu cầu rất cụ
 * thể: gọi sai quyền phải bị <b>từ chối</b>, không phải nhận "200 rỗng". Ở M1 chưa có JWT nên
 * ba endpoint công khai chưa kiểm được điều đó, nhưng hai endpoint nội bộ thì có — và chúng
 * mới là bề mặt nguy hiểm.
 */
class InternalSecretFilterTest {

    private static final String SECRET = "day-la-mot-secret-du-32-ky-tu-tro-len";

    private InternalSecretFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private AtomicBoolean chainCalled;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalSecretFilter(properties(SECRET));
        request = new MockHttpServletRequest("POST", "/internal/judge/claim");
        response = new MockHttpServletResponse();
        chainCalled = new AtomicBoolean(false);
        chain = (req, res) -> chainCalled.set(true);
    }

    @Test
    @DisplayName("★ không có header → 401, và request KHÔNG đi tiếp tới controller")
    void thieu_header_thi_chan_han() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    @DisplayName("★ sai secret → 401, không phải 200 rỗng")
    void sai_secret_thi_chan_han() throws Exception {
        request.addHeader(InternalSecretFilter.HEADER, "sai-be-bét-nhưng-đủ-dài-32-ký-tự-nhé");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    /** Đúng một tiền tố cũng không đủ — {@code MessageDigest.isEqual} chạy hết chuỗi. */
    @Test
    void dung_tien_to_cung_khong_qua_duoc() throws Exception {
        request.addHeader(InternalSecretFilter.HEADER, SECRET.substring(0, SECRET.length() - 1));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void dung_secret_thi_di_tiep() throws Exception {
        request.addHeader(InternalSecretFilter.HEADER, SECRET);

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);   // chưa ai đổi, controller sẽ đặt
    }

    /**
     * Phản hồi từ chối không được nói thiếu cái gì: phân biệt "thiếu header" với "sai giá trị"
     * là xác nhận cho người dò rằng họ đã tìm đúng tên header.
     */
    @Test
    void hai_kieu_tu_choi_cho_cung_mot_phan_hoi() throws Exception {
        var khongHeader = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/internal/judge/result"),
                khongHeader, chain);

        request.addHeader(InternalSecretFilter.HEADER, "khac-hoan-toan");
        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(khongHeader.getStatus());
        assertThat(response.getContentAsString()).isEqualTo(khongHeader.getContentAsString());
    }

    private static AppProperties properties(String secret) {
        return new AppProperties(
                new AppProperties.Submission(65_536, Duration.ofSeconds(10)),
                new AppProperties.Judge(Duration.ofSeconds(120), Duration.ofSeconds(15),
                        2, 20, "mac-m1max-host"),
                new AppProperties.Page(20, 50),
                new AppProperties.Internal(secret),
                new AppProperties.Sse(Duration.ofMinutes(5), Duration.ofSeconds(15)),
                new AppProperties.Ai(5, Duration.ofSeconds(30)));
    }
}
