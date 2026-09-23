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
import java.util.List;

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
 * <h2>Ba lớp, và filter này giữ hai lớp sau</h2>
 * <ol>
 *   <li><b>Luật ingress của cloudflared</b> ({@code infra/cloudflared/config.yml}) trả 404
 *       cho {@code /internal} trước khi request rời Cloudflare.</li>
 *   <li><b>Request mang dấu Cloudflare thì 404</b> — {@link #DAU_CLOUDFLARE}, ở đây. Worker
 *       gọi thẳng {@code localhost}, không bao giờ đi qua tunnel ({@code oj-api/CLAUDE.md}
 *       mục 5), nên một request tới {@code /internal} mà có {@code CF-Ray} là request từ
 *       internet — bất kể nó mang secret gì.</li>
 *   <li><b>Shared secret</b> — phần còn lại của lớp này.</li>
 * </ol>
 *
 * <p>Lớp 2 tồn tại vì lớp 1 là một regex, và regex ấy từng thủng: {@code //internal/…},
 * {@code /./internal/…}, {@code /internal;x=1/…} lọt qua {@code ^/internal(/|$)} của
 * cloudflared, còn Tomcat chuẩn hoá chúng về đúng {@code /internal/judge/*} (đo 2026-09-23).
 * Lớp 2 không phụ thuộc chuỗi đường dẫn: servlet container đã chuẩn hoá xong mới gọi filter
 * này, nên biến thể nào tới được endpoint thì cũng tới được đây. Nhờ nó, <b>lộ secret không
 * còn đồng nghĩa với bị ghi verdict từ internet</b>.
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

    /**
     * Header mà Cloudflare đặt vào MỌI request nó chuyển về origin — kể cả qua tunnel. Có
     * {@code CF-Connecting-IP} tới được Tomcat là chuyện {@link ClientIp} đã dựa vào từ M4.
     *
     * <p>Kẻ tấn công không gỡ được chúng khỏi request đi qua Cloudflare: edge ghi đè
     * {@code CF-*} và nối {@code CDN-Loop}. Họ chỉ có thể THÊM — và thêm thì bị chặn, nên
     * chiều sai duy nhất của phép kiểm này là chặn nhầm, không phải cho lọt.
     *
     * <p>⚠️ Hệ quả: worker <b>không bao giờ</b> gọi được {@code /internal} qua Cloudflare. Muốn
     * đặt worker ở máy khác thì đi mạng riêng (WireGuard, Tailscale), không đi tunnel công khai.
     */
    static final List<String> DAU_CLOUDFLARE = List.of("CF-Ray", "CF-Connecting-IP", "CDN-Loop");

    private final byte[] expected;

    public InternalSecretFilter(AppProperties properties) {
        this.expected = properties.internal().sharedSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Lớp 2 TRƯỚC secret: request từ internet bị từ chối kể cả khi mang đúng secret.
        if (quaCloudflare(request)) {
            tuChoiQuaTunnel(request, response);
            return;
        }
        String presented = request.getHeader(HEADER);
        if (presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            reject(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean quaCloudflare(HttpServletRequest request) {
        for (String h : DAU_CLOUDFLARE) {
            if (request.getHeader(h) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 404, không phải 401: cùng câu trả lời với luật ingress {@code http_status:404}, nên người
     * dò từ ngoài không phân biệt được lớp nào đã chặn — và không biết đường dẫn có thật.
     * {@code scripts/kiem-tunnel.sh} đọc 404 là ĐẠT, 401 là hai lớp đầu cùng thủng.
     *
     * <p>Log ở WARN: tới được đây nghĩa là luật ingress đã để lọt — việc của người trực.
     */
    private void tuChoiQuaTunnel(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("Từ chối {} {} từ {} — request tới /internal mang header Cloudflare. Luật "
                        + "ingress đã để lọt: chạy scripts/kiem-tunnel.sh [traceId={}]",
                request.getMethod(), request.getRequestURI(), ClientIp.cua(request),
                TraceIdFilter.current());
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.getWriter().flush();
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
