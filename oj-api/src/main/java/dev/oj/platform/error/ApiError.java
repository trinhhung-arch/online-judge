package dev.oj.platform.error;

import java.util.Map;

/**
 * Thân của mọi response lỗi. Đây là hình dạng duy nhất — không endpoint nào tự chế kiểu khác.
 *
 * <pre>
 * {
 *   "code":    "submission.rate_limited",
 *   "message": "Bạn vừa nộp bài cách đây 4 giây. Mỗi 10 giây được nộp một lần.",
 *   "traceId": "a3f19c2b7d004e51",
 *   "details": { "retryAfterSeconds": 6 }
 * }
 * </pre>
 *
 * <h2>Ba thứ cố ý KHÔNG có trong record này</h2>
 * <ul>
 *   <li><b>Stack trace</b> — {@code CLAUDE.md} mục 4.2. Nó lộ cấu trúc package, phiên bản thư
 *       viện, và đường dẫn tuyệt đối trên máy host.</li>
 *   <li><b>Tên class ngoại lệ</b> — {@code "NullPointerException"} chẳng giúp người dùng, còn
 *       với người dò lỗ hổng thì nó là thông tin.</li>
 *   <li><b>Đường dẫn file, câu SQL, tên bảng</b> — thứ hay lọt ra khi ai đó trả thẳng
 *       {@code e.getMessage()} của một exception JDBC.</li>
 * </ul>
 *
 * <h2>{@code traceId} là thứ khiến response này dùng được</h2>
 * Người dùng gặp lỗi 500 chỉ cần đọc một chuỗi 16 ký tự cho bạn, và bạn {@code grep} ra đúng
 * request đó xuyên API → queue → worker. Không có nó thì mọi báo lỗi đều là
 * <i>"em bấm nộp thì nó báo đỏ"</i>.
 *
 * @param code    mã ổn định, xem {@link DomainException#code()}
 * @param message câu tiếng người, an toàn để hiển thị. Với lỗi ngoài dự kiến thì đây là một
 *                câu chung chung cố định, không phải {@code e.getMessage()}
 * @param traceId lấy từ MDC, luôn có
 * @param details dữ liệu phụ có cấu trúc, hoặc {@code null}. Ví dụ lỗi validate thì đây là
 *                {@code {"field": "lý do"}}
 */
public record ApiError(
        String code,
        String message,
        String traceId,
        Map<String, Object> details) {

    public ApiError {
        details = details == null ? null : Map.copyOf(details);
    }

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, null);
    }

    public static ApiError of(String code, String message, String traceId,
                              Map<String, Object> details) {
        return new ApiError(code, message, traceId, details);
    }
}
