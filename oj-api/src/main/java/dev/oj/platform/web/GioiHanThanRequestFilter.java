package dev.oj.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oj.platform.error.ApiError;
import dev.oj.platform.trace.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

public class GioiHanThanRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GioiHanThanRequestFilter.class);

    public static final String MA_LOI = "request.body_too_large";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final long tran;

    public GioiHanThanRequestFilter(DataSize tran) {
        if (tran == null || tran.toBytes() <= 0) {
            throw new IllegalStateException("oj.request-body-max phải dương: " + tran);
        }
        this.tran = tran.toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String loai = request.getContentType();
        return loai != null && loai.toLowerCase().startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > tran) {
            tuChoi(request, response);
            return;
        }
        chain.doFilter(new DemThan(request, tran), response);
    }

    private void tuChoi(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("[{}] 413 — {} {} vượt {} byte", MA_LOI, request.getMethod(),
                request.getRequestURI(), tran);
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getWriter(), loi(tran));
    }

    public static ApiError loi(long tran) {
        return new ApiError(MA_LOI, "Nội dung gửi lên quá lớn.", TraceIdFilter.current(),
                Map.of("maxBytes", tran));
    }

    public static final class ThanQuaLon extends IOException {

        private final long tran;

        ThanQuaLon(long tran) {
            super("thân request vượt " + tran + " byte");
            this.tran = tran;
        }

        public long tran() {
            return tran;
        }
    }

    private static final class DemThan extends HttpServletRequestWrapper {

        private final long tran;
        private ServletInputStream boc;

        DemThan(HttpServletRequest request, long tran) {
            super(request);
            this.tran = tran;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (boc == null) {
                boc = new DemLuong(super.getInputStream());
            }
            return boc;
        }

        private final class DemLuong extends ServletInputStream {

            private final ServletInputStream goc;
            private long daDoc;

            DemLuong(ServletInputStream goc) {
                this.goc = goc;
            }

            @Override
            public int read() throws IOException {
                int b = goc.read();
                if (b >= 0) {
                    dem(1);
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = goc.read(b, off, len);
                if (n > 0) {
                    dem(n);
                }
                return n;
            }

            private void dem(int n) throws IOException {
                daDoc += n;
                if (daDoc > tran) {
                    throw new ThanQuaLon(tran);
                }
            }

            @Override
            public boolean isFinished() {
                return goc.isFinished();
            }

            @Override
            public boolean isReady() {
                return goc.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
                goc.setReadListener(listener);
            }
        }
    }

    @Configuration
    public static class Registration {

        @Bean
        public FilterRegistrationBean<GioiHanThanRequestFilter> gioiHanThanRequestFilter(
                @Value("${oj.request-body-max}") DataSize tran) {
            var registration = new FilterRegistrationBean<>(new GioiHanThanRequestFilter(tran));
            registration.addUrlPatterns("/api/v1/*");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
            registration.setName("gioiHanThanRequestFilter");
            return registration;
        }
    }
}
