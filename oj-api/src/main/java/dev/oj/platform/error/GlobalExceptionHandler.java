package dev.oj.platform.error;

import dev.oj.platform.trace.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Chỗ duy nhất biến ngoại lệ thành HTTP. Không controller nào tự bắt lỗi rồi tự dựng response.
 *
 * <h2>Luật của class này</h2>
 * <b>Chỉ {@link DomainException#publicMessage()} được ra tới client.</b> Mọi ngoại lệ khác nhận
 * một câu chung chung cố định, còn chi tiết thật đi vào log kèm {@code traceId}. Không có
 * {@code e.getMessage()} nào lọt ra ngoài — đó là cách duy nhất chắc chắn rằng một ngày nào đó
 * đường dẫn testdata hay câu SQL không hiện lên trình duyệt người dùng
 * ({@code CLAUDE.md} mục 4.2, SEC3).
 *
 * <p>Điều đó nghe như làm khó việc debug, nhưng không: người dùng đọc {@code traceId} cho bạn,
 * bạn {@code grep} ra đúng request đó với đầy đủ chi tiết. Đổi một chút bất tiện lấy việc
 * không bao giờ phải rà lại xem endpoint nào đang lộ gì.
 *
 * <h2>Không bao giờ log thân request</h2>
 * Bất biến #9. Thân của {@code POST /api/v1/submissions} <b>là mã nguồn người dùng</b>. Một
 * dòng {@code log.error("payload={}", body)} thêm vào đây lúc 2 giờ sáng là một đường rò rỉ
 * vĩnh viễn, và không ai nhớ ra để xoá.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Câu duy nhất người dùng thấy khi có lỗi ngoài dự kiến. */
    private static final String GENERIC_MESSAGE =
            "Có lỗi phía hệ thống. Bài nộp của bạn không bị ảnh hưởng. "
                    + "Nếu cần báo lỗi, gửi kèm mã sự cố bên dưới.";

    // -------------------------------------------------------------------------
    // Lỗi nghiệp vụ — đường đi bình thường
    // -------------------------------------------------------------------------

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException e) {
        HttpStatus status = toStatus(e.kind());
        String traceId = TraceIdFilter.current();

        // 4xx là chuyện bình thường (nộp sai, hết quota) -> WARN, không stack trace.
        // 5xx do nghiệp vụ (UNAVAILABLE) -> ERROR kèm nguyên nhân.
        if (status.is5xxServerError()) {
            log.error("[{}] {} — {}", e.code(), status.value(), e.getMessage(), e);
        } else {
            log.warn("[{}] {} — {}", e.code(), status.value(), e.getMessage());
        }

        Map<String, Object> details = null;
        HttpHeaders headers = new HttpHeaders();
        if (e.kind() == DomainException.Kind.RATE_LIMITED && e.retryAfter() != null) {
            long seconds = Math.max(1, e.retryAfter().toSeconds());
            // Retry-After là chuẩn HTTP; details là để UI hiện đếm ngược mà không phải
            // đọc header (FR-SUB-08: rate limit là quy tắc nghiệp vụ ĐƯỢC CÔNG BỐ,
            // không phải cơ chế ẩn — người dùng phải thấy được).
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
            details = Map.of("retryAfterSeconds", seconds);
        }

        return json(status, headers, new ApiError(e.code(), e.publicMessage(), traceId, details));
    }

    /**
     * ★ Mọi phản hồi lỗi <b>luôn</b> là {@code application/json}, đặt tường minh.
     *
     * <h2>Lỗi đã gặp thật, và nó im lặng theo cách tệ nhất</h2>
     * {@code GET /api/v1/submissions/{id}/stream} khai {@code produces = text/event-stream}.
     * Khi nó ném lỗi <i>trước khi</i> ghi byte nào — token hết hạn, bài của người khác —
     * Spring đã đặt sẵn {@code Content-Type} của response theo khai báo đó, rồi không tìm được
     * bộ chuyển đổi nào ghi {@link ApiError} thành {@code text/event-stream}. Kết quả:
     * <b>500 với thân rỗng</b>, thay cho 401 hoặc 404.
     *
     * <p>Hậu quả không phải thẩm mỹ. Frontend phân biệt "token hết hạn, làm mới rồi thử lại"
     * với "hỏng thật" bằng mã lỗi trong thân phản hồi (xem {@code js/api.js}). Một 500 rỗng
     * xoá mất sự phân biệt ấy, nên người dùng có token hết hạn giữa lúc theo dõi một bài nộp
     * sẽ thấy luồng chết vĩnh viễn.
     *
     * <p>Đặt {@code Content-Type} tường minh ghi đè giá trị Spring đã chọn sẵn. Áp cho
     * <b>mọi</b> nhánh, không riêng nhánh SSE: một endpoint tương lai khai
     * {@code produces} khác cũng sẽ gặp đúng vấn đề này, và lúc đó không ai nhớ tới đoạn
     * javadoc này nữa.
     */
    private static ResponseEntity<ApiError> json(HttpStatus status, HttpHeaders headers,
                                                 ApiError than) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(than);
    }

    private static ResponseEntity<ApiError> json(HttpStatus status, ApiError than) {
        return json(status, new HttpHeaders(), than);
    }

    private static HttpStatus toStatus(DomainException.Kind kind) {
        return switch (kind) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    // -------------------------------------------------------------------------
    // Lỗi đầu vào — tên trường là của ta nên trả ra được
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("Dữ liệu gửi lên không hợp lệ: {}", fields.keySet());
        return json(HttpStatus.BAD_REQUEST, new ApiError(
                "request.invalid", "Dữ liệu gửi lên không hợp lệ.",
                TraceIdFilter.current(), fields));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParam(Exception e) {
        log.warn("Tham số không hợp lệ: {}", e.getMessage());
        return json(HttpStatus.BAD_REQUEST, ApiError.of(
                "request.invalid_parameter", "Tham số không hợp lệ.", TraceIdFilter.current()));
    }

    /**
     * JSON hỏng, hoặc một record trong {@code oj-contract} từ chối payload.
     *
     * <p>Trường hợp thứ hai là chỗ đáng nói: compact constructor của {@code JudgeResultDto} ném
     * {@code IllegalArgumentException}, Jackson bọc lại thành ngoại lệ này. Nó <b>phải</b> là
     * 400 chứ không phải 500 — worker nhìn 5xx sẽ retry mãi một payload không bao giờ hợp lệ,
     * còn 4xx thì nó biết là lỗi của chính nó và cho vào DLQ.
     *
     * <p>Thông điệp gốc vẫn không ra ngoài: nó có thể chứa tên class và một đoạn payload —
     * mà payload của {@code /api/v1/submissions} chính là mã nguồn người dùng.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("Không đọc được thân request: {}", rootMessage(e));
        return json(HttpStatus.BAD_REQUEST, ApiError.of(
                "request.malformed", "Nội dung gửi lên không đọc được.", TraceIdFilter.current()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return json(HttpStatus.NOT_FOUND, ApiError.of(
                "resource.not_found", "Không tìm thấy.", TraceIdFilter.current()));
    }

    // -------------------------------------------------------------------------
    // Lưới cuối
    // -------------------------------------------------------------------------

    /**
     * Mọi thứ không lường trước. Người dùng nhận một câu cố định; log giữ toàn bộ sự thật.
     *
     * <p>Nếu handler này chạy thường xuyên, đó không phải chuyện của log — đó là một
     * {@link DomainException} còn thiếu ở đâu đó.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        String traceId = TraceIdFilter.current();
        log.error("Lỗi ngoài dự kiến [traceId={}]", traceId, e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(
                "internal.error", GENERIC_MESSAGE, traceId));
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    // -------------------------------------------------------------------------
    // Thêm ở M4, cùng Bước 4.5-4.6:
    //   @ExceptionHandler(AccessDeniedException.class)      -> 403
    //   @ExceptionHandler(AuthenticationException.class)    -> 401
    // Chưa thêm bây giờ vì kéo spring-security vào ở M1 là thêm một dependency cho một
    // thứ chưa dùng — và thêm dependency là việc phải hỏi người (CLAUDE.md mục 5.2).
    // Ở M4, @RequiresRole ném DomainException(FORBIDDEN) nên phần lớn đã có handler rồi.
    // -------------------------------------------------------------------------
}
