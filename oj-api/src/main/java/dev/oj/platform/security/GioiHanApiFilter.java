package dev.oj.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.error.ApiError;
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
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * ★ Trần chung cho MỌI endpoint dưới {@code /api/v1/} — nfrplan 4.2, oj-api/CLAUDE.md mục 8.
 *
 * <h2>Vì sao cần, khi đã có giới hạn riêng cho nộp bài, đăng nhập và đăng ký</h2>
 * Ba giới hạn ấy chắn ba đường đắt nhất. Mọi đường còn lại — danh sách bài nộp, bảng xếp hạng,
 * trang đề — không có trần nào: một tài khoản hợp lệ gọi mười nghìn lượt mỗi phút vẫn được phục
 * vụ, và mỗi lượt là một lượt đọc Postgres trên pool dùng chung với đường nộp bài. Đặt ở bộ lọc
 * chứ không ở use-case là cố ý: use-case viết sau này sẽ không nhớ tự thêm trần cho mình.
 *
 * <h2>Hai xô đếm, và lý do chúng lệch nhau một bậc</h2>
 * Người đã đăng nhập đếm theo {@code userId} — mỗi người một xô, đúng con số đã công bố.
 * Người chưa đăng nhập không có danh tính nào khác ngoài IP, mà một phòng thi, một trường, một
 * nhà mạng di động đều ra internet bằng CHUNG một IP: đặt xô IP bằng xô người là để người thứ
 * hai trong phòng chặn người thứ nhất. Vì thế xô IP rộng hơn hẳn và chỉ đếm lượt ẩn danh.
 *
 * <h2>Đếm hỏng thì CHO QUA</h2>
 * Đây là lớp chống lạm dụng, không phải chốt đúng đắn. Redis chết mà chặn hết thì một sự cố hạ
 * tầng thành một sự cố mất dịch vụ — đúng thứ ta muốn tránh. Mất trần vài phút thì chấp nhận
 * được; mất trang thì không. Cùng lập luận với {@code RedisStandingsEventBus}.
 *
 * <h2>KHÔNG áp cho {@code /internal/**}</h2>
 * Worker gọi {@code result} và {@code progress} hàng trăm lượt mỗi phút từ MỘT máy: áp trần ở
 * đó là tự bóp đường ghi verdict, tức phá R1 bằng chính cái vốn để bảo vệ hệ thống. Cửa ấy đã
 * có {@link InternalSecretFilter} và luật chặn trong cấu hình tunnel.
 *
 * <h2>★ Phạm vi do servlet container chọn, KHÔNG do một câu {@code startsWith}</h2>
 * Bản đầu là {@code @Component} kèm {@code shouldNotFilter} so tiền tố {@code "/api/v1/"} trên
 * {@code getRequestURI()} — chuỗi THÔ, chưa giải mã, chưa bỏ {@code ;tham-so}. Nhưng Tomcat và
 * Spring chọn controller trên đường dẫn ĐÃ chuẩn hoá. Hai cách đọc lệch nhau đúng ở chỗ kẻ lạm
 * dụng cần: {@code /api/v1;x/status} và {@code /%61pi/v1/status} tới thẳng controller mà không
 * bị đếm lượt nào (đo 2026-09-16, {@code GioiHanApiHttpIT}).
 *
 * <p>Nên bộ lọc đăng ký qua {@link Registration} với {@code urlPatterns}, đúng như
 * {@link InternalSecretFilter}: container so mẫu trên chính đường dẫn nó dùng để định tuyến,
 * nên không còn khoảng lệch nào để chen vào. Đừng thêm lại {@code @Component} — Spring Boot sẽ
 * đăng ký thêm một bản áp cho MỌI đường, kể cả {@code /internal/**}.
 */
public class GioiHanApiFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GioiHanApiFilter.class);

    /** Mẫu servlet, không phải tiền tố chuỗi — xem mục "Phạm vi" ở javadoc của class. */
    static final String MAU_URL = "/api/v1/*";

    /** Sau {@link JwtAuthFilter} ({@code +10}): phải biết {@code userId} mới chọn được xô. */
    static final int THU_TU = Ordered.HIGHEST_PRECEDENCE + 15;

    private static final String MA_LOI = "api.rate_limited";
    private static final String CAU = "Bạn gọi quá nhiều yêu cầu trong một phút. Chờ một chút rồi thử lại.";

    /**
     * ★ Bộ tuần tự RIÊNG, không phải bean {@code ObjectMapper} của ứng dụng. Đo 2026-09-12:
     * context của {@code TestdataImportIT} không có bean ấy, và cả ứng dụng không khởi động
     * được chỉ vì một bộ lọc cần nó để in một thân lỗi ba trường. Một trần chống lạm dụng
     * không được phép là lý do hệ thống không lên.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DemTocDo dem;
    private final AppProperties.ApiRateLimit gioiHan;

    public GioiHanApiFilter(DemTocDo dem, AppProperties properties) {
        this.dem = dem;
        this.gioiHan = properties.apiRateLimit();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Long userId = CurrentUserHolder.idNeuCo();
        int tran = userId != null ? gioiHan.perUser() : gioiHan.perIp();
        if (tran <= 0) {                    // 0 = tắt hẳn, kể cả lượt đếm — stack đo tải dùng nhánh này
            chain.doFilter(request, response);
            return;
        }
        String khoa = userId != null ? "u:" + userId : "ip:" + ClientIp.khoaGioiHan(request);   // IPv6 gom theo /64 — xem ClientIp.khoaGioiHan
        long luot;
        try {
            luot = dem.tang(khoa, gioiHan.window());
        } catch (RuntimeException e) {
            log.warn("Không đếm được nhịp gọi API: {}. CHO QUA — trần là lớp chống lạm dụng, "
                    + "không phải chốt đúng đắn.", e.toString());
            chain.doFilter(request, response);
            return;
        }
        if (luot > tran) {
            tuChoi(request, response, khoa, tran);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Cùng hình dạng thân lỗi với {@code GlobalExceptionHandler}: bộ lọc chạy ngoài tầm với của nó. */
    private void tuChoi(HttpServletRequest request, HttpServletResponse response,
                        String khoa, int tran) throws IOException {
        long giay = Math.max(1, gioiHan.window().toSeconds());
        log.warn("[{}] 429 — {} vượt {} lượt/{} ({} {})", MA_LOI, khoa, tran, gioiHan.window(),
                request.getMethod(), request.getRequestURI());
        // Jakarta Servlet không có hằng cho 429 — HttpStatus của Spring có.
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(giay));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getWriter(),
                new ApiError(MA_LOI, CAU, TraceIdFilter.current(), Map.of("retryAfterSeconds", giay)));
    }

    /**
     * Đăng ký cho <b>đúng một mẫu đường dẫn</b>, và đứng sau {@link JwtAuthFilter} — xem mục
     * "Phạm vi" ở javadoc của class. Cùng khuôn với {@code InternalSecretFilter.Registration}.
     */
    @Configuration
    public static class Registration {

        @Bean
        public FilterRegistrationBean<GioiHanApiFilter> gioiHanApiFilter(DemTocDo dem,
                                                                         AppProperties properties) {
            var registration = new FilterRegistrationBean<>(new GioiHanApiFilter(dem, properties));
            registration.addUrlPatterns(MAU_URL);
            registration.setOrder(THU_TU);
            registration.setName("gioiHanApiFilter");
            return registration;
        }
    }
}
