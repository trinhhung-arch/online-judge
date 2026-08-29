package dev.oj.platform.security;

import dev.oj.contract.JudgeEndpoints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Đọc {@code Authorization: Bearer <token>} và ghi kết quả vào {@link CurrentUserHolder}.
 * Bước 4.5, FR-AUTH-02.
 *
 * <h2>Filter này KHÔNG chặn ai cả — nó chỉ nhận dạng</h2>
 * Nó không trả 401, không trả 403, không có danh sách đường dẫn được bảo vệ. Lý do là bất
 * biến #11: <i>"kiểm quyền chỉ ở controller"</i> là kiểm ở chỗ dễ đi vòng nhất, và một danh
 * sách đường dẫn trong filter còn dễ đi vòng hơn thế — chỉ cần một endpoint mới quên thêm
 * vào danh sách.
 *
 * <p>Quyết định "được hay không" nằm ở {@code @RequiresRole} trên use-case (Bước 4.6), tức là
 * ở nơi <b>không có đường nào đi vòng</b>: mọi lối vào một use-case đều đi qua chính nó.
 * Thêm một controller mới, một consumer RabbitMQ, một job nền gọi cùng use-case ấy — tất cả
 * đều bị kiểm, mà không ai phải nhớ điều gì.
 *
 * <h2>Vì sao bỏ qua {@code /internal/**}</h2>
 * Worker không phải một người dùng và không mang JWT — nó xác thực bằng shared secret qua
 * {@link InternalSecretFilter} ({@code oj-api/CLAUDE.md} mục 5). Bỏ qua tường minh ở đây để
 * ranh giới ấy nhìn thấy được trong mã, thay vì đúng một cách tình cờ vì worker không gửi
 * header {@code Authorization}.
 *
 * <p>Chạy ngay sau {@code TraceIdFilter} nên mọi dòng log ở đây đều có {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    public static final String HEADER = "Authorization";

    private static final String TIEN_TO = "Bearer ";

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith(JudgeEndpoints.BASE + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(TIEN_TO)) {
            ghiNhan(header.substring(TIEN_TO.length()));
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // BẮT BUỘC — xem javadoc của CurrentUserHolder. Quên dòng này là request sau
            // chạy dưới danh nghĩa người dùng của request trước.
            CurrentUserHolder.xoa();
        }
    }

    private void ghiNhan(String token) {
        try {
            CurrentUserProvider.CurrentUser user = jwt.doc(token);
            CurrentUserHolder.dat(user);
        } catch (AuthorizationException e) {
            // Ghi ở mức DEBUG, không WARN: token hết hạn là chuyện xảy ra 15 phút một lần với
            // mọi tab đang mở. Ở mức WARN thì log đầy những dòng vô hại và người ta thôi đọc
            // log. Và không bao giờ ghi giá trị token (bất biến #9).
            log.debug("Không nhận dạng được người gọi: {}", e.code());
            CurrentUserHolder.datLoi(e);
        }
    }
}
