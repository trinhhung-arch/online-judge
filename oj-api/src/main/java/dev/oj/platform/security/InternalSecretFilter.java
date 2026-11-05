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

public class InternalSecretFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalSecretFilter.class);

    public static final String HEADER = JudgeEndpoints.SECRET_HEADER;

    static final List<String> DAU_CLOUDFLARE = List.of("CF-Ray", "CF-Connecting-IP", "CDN-Loop");

    private final byte[] expected;

    public InternalSecretFilter(AppProperties properties) {
        this.expected = properties.internal().sharedSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
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

    private void tuChoiQuaTunnel(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("Từ chối {} {} từ {} — request tới /internal mang header Cloudflare. Luật "
                        + "ingress đã để lọt: chạy scripts/kiem-tunnel.sh [traceId={}]",
                request.getMethod(), request.getRequestURI(), ClientIp.cua(request),
                TraceIdFilter.current());
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.getWriter().flush();
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.warn("Từ chối {} {} từ {} — thiếu hoặc sai {} [traceId={}]",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                HEADER, TraceIdFilter.current());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().flush();
    }

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
