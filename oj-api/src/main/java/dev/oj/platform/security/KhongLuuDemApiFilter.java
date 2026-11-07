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

public class KhongLuuDemApiFilter extends OncePerRequestFilter {

    static final String MAU_URL = "/api/v1/*";

    static final int THU_TU = Ordered.HIGHEST_PRECEDENCE + 6;

    static final String GIA_TRI = "no-store";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, GIA_TRI);
        chain.doFilter(request, response);
    }

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
