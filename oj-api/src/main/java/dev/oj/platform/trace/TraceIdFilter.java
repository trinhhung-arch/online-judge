package dev.oj.platform.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";

    public static final String HEADER = "X-Trace-Id";

    private static final int MAX_HEADER_LENGTH = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolve(request);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(HttpServletRequest request) {
        if (isInternal(request)) {
            String incoming = sanitize(request.getHeader(HEADER));
            if (incoming != null) {
                return incoming;
            }
        }
        return generate();
    }

    private static boolean isInternal(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/internal/");
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return value;
    }

    private static String generate() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "-" : value;
    }

    public static void set(String traceId) {
        String clean = sanitize(traceId);
        MDC.put(MDC_KEY, clean != null ? clean : generate());
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
