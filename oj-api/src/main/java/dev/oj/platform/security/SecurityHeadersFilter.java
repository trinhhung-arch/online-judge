package dev.oj.platform.security;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.SecurityHeadersProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gắn header bảo mật vào mọi response. Chuẩn bị mở ra internet — xem
 * {@link SecurityHeadersProperties} để biết vì sao từng header có mặt.
 *
 * <h2>★ Đặt header TRƯỚC {@code chain.doFilter}, không phải sau</h2>
 * Sau khi chain chạy xong, response có thể đã được commit — byte đầu tiên đã đi ra dây, và
 * header thì nằm trước byte đầu tiên. Servlet container lúc ấy <b>bỏ qua {@code setHeader}
 * trong im lặng</b>: không ngoại lệ, không log, chỉ là header không tới trình duyệt.
 *
 * <p>Đường SSE làm chuyện này thành chắc chắn chứ không phải xui rủi:
 * {@code SubmissionSseController} giữ kết nối mở hàng phút và đẩy dữ liệu dần, nên response
 * đã commit từ lâu trước khi filter thấy lại quyền điều khiển. Đặt header trước là cách duy
 * nhất để SSE cũng được bảo vệ.
 *
 * <h2>Chạy sớm, nhưng SAU {@code TraceIdFilter}</h2>
 * Để một response lỗi phát ra từ bất kỳ filter nào phía sau vẫn mang đủ header. Thứ tự nằm
 * giữa {@code TraceIdFilter} và {@link JwtAuthFilter} — trace vẫn là thứ đầu tiên, vì một
 * dòng log không có {@code traceId} thì không truy được.
 *
 * <h2>Vì sao KHÔNG lọc {@code /internal/**}</h2>
 * Worker không phải trình duyệt và không quan tâm mấy header này. Nhưng thêm một nhánh
 * ngoại lệ là thêm một chỗ để sai, và cái giá của việc gửi thừa vài header cho worker bằng
 * không. Bề mặt nào cũng được bảo vệ là một quy tắc dễ kiểm hơn "bề mặt nào cũng được bảo
 * vệ trừ những cái sau".
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = "Content-Security-Policy";
    static final String NOSNIFF = "X-Content-Type-Options";
    static final String KHUNG = "X-Frame-Options";
    static final String REFERRER = "Referrer-Policy";
    static final String QUYEN = "Permissions-Policy";
    static final String HSTS = "Strict-Transport-Security";

    /**
     * Trình duyệt đoán kiểu nội dung khi header nói một đằng mà byte nói một nẻo. Một file
     * testdata tải về, hoặc một thông báo lỗi chứa chuỗi người dùng nhập, có thể bị đoán
     * thành HTML rồi chạy như HTML.
     */
    private static final String NOSNIFF_GIA_TRI = "nosniff";

    /**
     * Bản cũ của {@code frame-ancestors}. Gửi cả hai vì trình duyệt cũ không hiểu CSP; trình
     * duyệt mới thấy cả hai thì ưu tiên CSP, nên không có xung đột.
     */
    private static final String KHUNG_GIA_TRI = "DENY";

    /**
     * Đường dẫn ở đây mang {@code submissionId} và mã đề. Gửi chúng sang tên miền khác qua
     * header {@code Referer} là rò rỉ một cách âm thầm — {@code strict-origin-when-cross-origin}
     * giữ đường dẫn đầy đủ cho chính mình và chỉ gửi origin ra ngoài.
     */
    private static final String REFERRER_GIA_TRI = "strict-origin-when-cross-origin";

    /** Không trang nào cần những thứ này. Tắt sẵn thì một script lạ cũng không xin được. */
    private static final String QUYEN_GIA_TRI =
            "geolocation=(), microphone=(), camera=(), payment=(), usb=()";

    private final SecurityHeadersProperties properties;

    /** Dựng sẵn lúc khởi động: nối chuỗi mỗi request là việc thừa, và giá trị không đổi. */
    private final String hstsGiaTri;

    /**
     * Nhận {@link AppProperties} chứ không phải {@link SecurityHeadersProperties}: chỉ
     * {@code AppProperties} mới là bean ({@code @ConfigurationProperties}), các record con
     * chỉ là thành phần của nó. Cùng lối với {@code JwtService} và {@code properties.auth()}.
     */
    public SecurityHeadersFilter(AppProperties app) {
        this.properties = app.securityHeaders();
        long giay = this.properties.hstsMaxAge().toSeconds();
        this.hstsGiaTri = giay > 0 ? "max-age=" + giay + "; includeSubDomains" : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(CSP, properties.contentSecurityPolicy());
        response.setHeader(NOSNIFF, NOSNIFF_GIA_TRI);
        response.setHeader(KHUNG, KHUNG_GIA_TRI);
        response.setHeader(REFERRER, REFERRER_GIA_TRI);
        response.setHeader(QUYEN, QUYEN_GIA_TRI);
        if (hstsGiaTri != null) {
            response.setHeader(HSTS, hstsGiaTri);
        }
        chain.doFilter(request, response);
    }
}
