package dev.oj.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ★ {@code Cache-Control: no-store} trên mọi response dưới {@code /api/v1/}.
 *
 * <h2>Vì sao cần</h2>
 * Thân response ở đây là dữ liệu nhìn bằng vai trò của NGƯỜI GỌI: hồ sơ, mã nguồn bài nộp,
 * danh sách đề mà admin thấy cả đề chưa mở. Hai chỗ có thể giữ nó lại mà không hỏi ai:
 * <ol>
 *   <li><b>Trình duyệt trên máy dùng chung</b> — phòng thi, phòng máy. Người đến sau mở cache
 *       đĩa của trình duyệt là đọc được thứ người trước đã xem, dù người trước đã đăng xuất.</li>
 *   <li><b>Cloudflare.</b> Mặc định nó chỉ cache theo đuôi file và không đường nào ở đây có
 *       đuôi ấy. Nhưng một Cache Rule "cache everything" thêm vào để "tăng tốc" là đủ để câu trả
 *       lời của admin — kèm đề chưa mở — được phát lại cho mọi người. {@code no-store} từ origin
 *       làm Cloudflare không lưu, trừ khi chính luật ấy ghi đè header của origin.</li>
 * </ol>
 * Không mất gì về tốc độ: JSON ở đây vốn không có {@code ETag} hay {@code Last-Modified}, nên
 * trước đây cũng không được cache có ích ở đâu cả.
 *
 * <h2>Phạm vi do servlet container chọn</h2>
 * Đăng ký bằng mẫu {@value #MAU_URL}, không so tiền tố trên {@code getRequestURI()} — cùng lý do
 * và cùng khuôn với {@link GioiHanApiFilter}: {@code /api/v1;x/status} và
 * {@code /%61pi/v1/status} tới đúng controller, và phải mang đúng header.
 *
 * <h2>File tĩnh KHÔNG bị áp, cố ý</h2>
 * {@code /*.html}, {@code /js}, {@code /css} giống hệt nhau cho mọi người, và cache chúng ở
 * edge là thứ ta muốn.
 *
 * <h2>Đặt header TRƯỚC {@code chain.doFilter}</h2>
 * Cùng lý do với {@link SecurityHeadersFilter}: luồng SSE commit response từ rất sớm, sau đó
 * {@code setHeader} bị container bỏ qua trong im lặng.
 */
public class KhongLuuDemApiFilter extends OncePerRequestFilter {

    /** Mẫu servlet, không phải tiền tố chuỗi — xem mục "Phạm vi" ở trên. */
    static final String MAU_URL = "/api/v1/*";

    /**
     * Ngay sau {@link SecurityHeadersFilter} ({@code +5}), và phải TRƯỚC {@link GioiHanApiFilter}
     * ({@code +15}) — bộ lọc duy nhất trên {@code /api/v1} tự viết response (429) mà không đi
     * tiếp xuống chain. Đứng sau nó thì một 429 ra không có header.
     */
    static final int THU_TU = Ordered.HIGHEST_PRECEDENCE + 6;

    static final String GIA_TRI = "no-store";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, GIA_TRI);
        chain.doFilter(request, response);
    }

    /** Đúng một mẫu đường dẫn. Đừng thêm {@code @Component} — Spring Boot sẽ áp cho MỌI đường. */
    @Configuration
    public static class Registration {

        @Bean
        public FilterRegistrationBean<KhongLuuDemApiFilter> khongLuuDemApiFilter() {
            var registration = new FilterRegistrationBean<>(new KhongLuuDemApiFilter());
            registration.addUrlPatterns(MAU_URL);
            registration.setOrder(THU_TU);
            registration.setName("khongLuuDemApiFilter");
            return registration;
        }
    }
}
