package dev.oj.platform.security;

import dev.oj.contract.JudgeEndpoints;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.trace.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Cửa duy nhất vào {@code /internal/**}. Xác thực bằng <b>shared secret đọc từ env</b>, không
 * phải JWT người dùng ({@code oj-api/CLAUDE.md} mục 5).
 *
 * <h2>Vì sao không dùng JWT ở đây</h2>
 * Worker không phải một người dùng. Cho nó một tài khoản nghĩa là tạo một tài khoản có quyền
 * ghi verdict cho mọi bài nộp — và tài khoản thì có thể bị đổi vai trò, bị vô hiệu hoá, hoặc
 * bị dùng để đăng nhập vào giao diện. Một secret dùng riêng cho một bề mặt duy nhất thì không
 * có những đường đó.
 *
 * <h2>Đây là lớp phòng thủ thứ HAI, không phải thứ nhất</h2>
 * Lớp thứ nhất là mạng: Cloudflare Tunnel chỉ publish {@code /api/v1/**}, nên
 * {@code /internal/**} không có đường đi từ internet vào. Filter này bảo vệ trường hợp lớp
 * ấy sai — cấu hình tunnel bị sửa nhầm, hoặc một ngày nào đó host mở cổng ra LAN. Kiểm tay
 * cấu hình tunnel ở tuần 9 ({@code build-order.md} Bước M1-8).
 *
 * <h2>So sánh trong thời gian hằng định</h2>
 * {@code String.equals} thoát ra ngay ở byte đầu tiên khác nhau, nên thời gian phản hồi rò rỉ
 * độ dài tiền tố đúng. Với một secret 32+ ký tự và một kẻ tấn công kiên nhẫn, đó là một đường
 * dò từng ký tự. {@link MessageDigest#isEqual} chạy hết chuỗi.
 */
public class InternalSecretFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalSecretFilter.class);

    /**
     * Tên header — lấy từ {@link JudgeEndpoints}, <b>không gõ lại</b>.
     *
     * <p>Trước đây hằng này và {@code JudgeApiClient.SECRET_HEADER} là hai chuỗi độc lập giống
     * nhau. Lệch một ký tự thì trình biên dịch im, test hai bên vẫn xanh (mỗi bên dùng hằng
     * của chính mình), và triệu chứng duy nhất là mọi request từ worker nhận 401.
     */
    public static final String HEADER = JudgeEndpoints.SECRET_HEADER;

    private final byte[] expected;

    public InternalSecretFilter(AppProperties properties) {
        this.expected = properties.internal().sharedSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            reject(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Thiếu header và sai secret cho <b>cùng một phản hồi</b>: 401, không thân, không nói
     * thiếu cái gì. Phân biệt hai trường hợp là xác nhận cho người dò rằng họ đã tìm đúng
     * tên header.
     *
     * <p>Log ghi IP và đường dẫn, <b>không bao giờ ghi giá trị header</b> — kể cả giá trị sai,
     * vì một lần gõ nhầm của chính worker sẽ đưa secret thật vào file log (bất biến #9).
     */
    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("Từ chối {} {} từ {} — thiếu hoặc sai {} [traceId={}]",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                HEADER, TraceIdFilter.current());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().flush();
    }

    /**
     * Đăng ký cho <b>đúng một tiền tố đường dẫn</b>.
     *
     * <p>Không dùng {@code @Component}: filter đánh dấu bằng annotation đó sẽ chạy trên
     * <i>mọi</i> request, kể cả {@code /api/v1/**}, và lúc đó nó phải tự kiểm đường dẫn — thêm
     * một câu {@code if} mà quên là mở toang, hoặc viết dư là khoá luôn phần công khai.
     * {@code urlPatterns} để servlet container lo việc đó.
     *
     * <p>Chạy sau {@link TraceIdFilter} ({@code @Order(HIGHEST_PRECEDENCE)}) nên dòng log từ
     * chối ở trên luôn có {@code traceId}.
     */
    @Configuration
    public static class Registration {

        @Bean
        public FilterRegistrationBean<InternalSecretFilter> internalSecretFilter(
                AppProperties properties) {
            var registration = new FilterRegistrationBean<>(new InternalSecretFilter(properties));
            registration.addUrlPatterns(JudgeEndpoints.BASE + "/*");
            registration.setName("internalSecretFilter");
            return registration;
        }
    }
}
