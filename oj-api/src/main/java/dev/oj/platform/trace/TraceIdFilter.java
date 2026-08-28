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

/**
 * Gắn một {@code traceId} vào mỗi request và đặt nó vào MDC, để mọi dòng log trong request
 * đó mang cùng một mã.
 *
 * <h2>Vì sao đây là một trong những file đầu tiên của dự án</h2>
 * {@code nfrplan.md} 2.4: <i>"traceId xuyên suốt ngay từ vòng lặp cốt lõi. Không có cái này
 * thì mọi tối ưu sau đều mù."</i> Một bài nộp đi qua API → RabbitMQ → worker → quay lại API.
 * Bốn chặng, hai tiến trình, hai máy khác nhau. Không có mã chung thì câu hỏi
 * <i>"bài này chậm ở chặng nào"</i> không có cách trả lời — và bảng ngân sách thời gian ở
 * {@code nfrplan.md} 2.1 trở thành trang trí.
 *
 * <p>Đây cũng là mã sự cố hiện trong {@link dev.oj.platform.error.ApiError}: người dùng đọc
 * 16 ký tự cho bạn, bạn có nguyên vẹn đường đi của request đó.
 *
 * <h2>Vì sao chỉ tin header từ {@code /internal/**}</h2>
 * Worker gửi lại {@code X-Trace-Id} khi trả kết quả, để chặng chấm bài nối được vào chặng nộp
 * bài. Nhưng nếu nhận header đó từ <b>mọi</b> client thì người dùng tự đặt được traceId —
 * và họ có thể đặt trùng nhau hàng loạt, khiến log không còn tách được request nào với request
 * nào đúng lúc bạn cần nhất. Nên: {@code /internal/**} thì nhận, mọi nơi khác thì tự sinh.
 *
 * <h2>Vì sao phải lọc ký tự trong header</h2>
 * Log của hệ thống là JSON có cấu trúc. Một traceId chứa xuống dòng hoặc dấu ngoặc kép do
 * người ngoài gửi vào sẽ chèn được dòng log giả — <b>log injection</b>. Ở một hệ thống mà
 * {@code audit_log} là bằng chứng, làm bẩn log là chuyện đáng ngăn. Chỉ chấp nhận
 * {@code [A-Za-z0-9_-]}, tối đa 64 ký tự; sai một ký tự thì bỏ luôn header và tự sinh.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** Khoá trong MDC. {@code logback-spring.xml} tham chiếu đúng chuỗi này. */
    public static final String MDC_KEY = "traceId";

    /** Header dùng cả khi nhận (từ worker) lẫn khi trả về (cho client). */
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
            // BẮT BUỘC. MDC là ThreadLocal, và cả thread nền tảng lẫn virtual thread đều
            // được tái sử dụng. Quên xoá thì request sau thừa hưởng traceId của request
            // trước, và log trở nên tệ hơn là không có traceId — vì nó SAI mà trông đúng.
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

    /**
     * Hai endpoint {@code /internal/judge/*} — chỉ nghe trên mạng nội bộ và không lộ ra
     * Cloudflare Tunnel ({@code oj-api/CLAUDE.md} mục 5).
     */
    private static boolean isInternal(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/internal/");
    }

    /** {@code null} nếu header vắng mặt hoặc không sạch — người gọi sẽ tự sinh mã mới. */
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

    /**
     * 16 ký tự hex (64 bit).
     *
     * <p>Không dùng UUID vì 36 ký tự có dấu gạch là thứ người dùng phải đọc qua điện thoại cho
     * bạn. 64 bit đủ để không trùng trong phạm vi log của hệ thống này, và ngắn gọn hơn hẳn.
     */
    private static String generate() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** {@code traceId} của request đang chạy, hoặc {@code "-"} nếu gọi ngoài request. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "-" : value;
    }

    /**
     * Đặt {@code traceId} cho một luồng không phải request: reaper, job nền, consumer RabbitMQ.
     *
     * <p>Với consumer, truyền {@code traceId} lấy từ message vào đây — đó là thứ nối chặng chấm
     * bài với chặng nộp bài trong log. <b>Luôn gọi trong {@code try/finally}</b> với
     * {@link #clear()} ở {@code finally}, vì thread nền cũng được tái sử dụng.
     */
    public static void set(String traceId) {
        String clean = sanitize(traceId);
        MDC.put(MDC_KEY, clean != null ? clean : generate());
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
