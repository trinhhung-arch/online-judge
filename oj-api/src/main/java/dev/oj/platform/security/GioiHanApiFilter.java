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

public class GioiHanApiFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GioiHanApiFilter.class);

    static final String MAU_URL = "/api/v1/*";

    static final int THU_TU = Ordered.HIGHEST_PRECEDENCE + 15;

    private static final String MA_LOI = "api.rate_limited";
    private static final String CAU = "Bạn gọi quá nhiều yêu cầu trong một phút. Chờ một chút rồi thử lại.";

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
        if (tran <= 0) {
            chain.doFilter(request, response);
            return;
        }
        String khoa = userId != null ? "u:" + userId : "ip:" + ClientIp.khoaGioiHan(request);
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

    private void tuChoi(HttpServletRequest request, HttpServletResponse response,
                        String khoa, int tran) throws IOException {
        long giay = Math.max(1, gioiHan.window().toSeconds());
        log.warn("[{}] 429 — {} vượt {} lượt/{} ({} {})", MA_LOI, khoa, tran, gioiHan.window(),
                request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(giay));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getWriter(),
                new ApiError(MA_LOI, CAU, TraceIdFilter.current(), Map.of("retryAfterSeconds", giay)));
    }

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
