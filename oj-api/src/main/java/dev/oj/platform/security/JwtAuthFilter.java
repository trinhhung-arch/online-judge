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
            CurrentUserHolder.xoa();
        }
    }

    private void ghiNhan(String token) {
        try {
            CurrentUserProvider.CurrentUser user = jwt.doc(token);
            CurrentUserHolder.dat(user);
        } catch (AuthorizationException e) {
            log.debug("Không nhận dạng được người gọi: {}", e.code());
            CurrentUserHolder.datLoi(e);
        }
    }
}
