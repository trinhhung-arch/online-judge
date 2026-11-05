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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String CSP = "Content-Security-Policy";
    static final String NOSNIFF = "X-Content-Type-Options";
    static final String KHUNG = "X-Frame-Options";
    static final String REFERRER = "Referrer-Policy";
    static final String QUYEN = "Permissions-Policy";
    static final String HSTS = "Strict-Transport-Security";

    private static final String NOSNIFF_GIA_TRI = "nosniff";

    private static final String KHUNG_GIA_TRI = "DENY";

    private static final String REFERRER_GIA_TRI = "strict-origin-when-cross-origin";

    private static final String QUYEN_GIA_TRI =
            "geolocation=(), microphone=(), camera=(), payment=(), usb=()";

    private final SecurityHeadersProperties properties;

    private final String hstsGiaTri;

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
