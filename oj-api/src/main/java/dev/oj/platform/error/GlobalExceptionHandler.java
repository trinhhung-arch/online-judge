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
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_MESSAGE =
            "Có lỗi phía hệ thống. Bài nộp của bạn không bị ảnh hưởng. "
                    + "Nếu cần báo lỗi, gửi kèm mã sự cố bên dưới.";

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException e) {
        HttpStatus status = toStatus(e.kind());
        String traceId = TraceIdFilter.current();

        if (status.is5xxServerError()) {
            log.error("[{}] {} — {}", e.code(), status.value(), e.getMessage(), e);
        } else {
            log.warn("[{}] {} — {}", e.code(), status.value(), e.getMessage());
        }

        Map<String, Object> details = null;
        HttpHeaders headers = new HttpHeaders();
        if (e.kind() == DomainException.Kind.RATE_LIMITED && e.retryAfter() != null) {
            long seconds = Math.max(1, e.retryAfter().toSeconds());
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
            details = Map.of("retryAfterSeconds", seconds);
        }

        return json(status, headers, new ApiError(e.code(), e.publicMessage(), traceId, details));
    }

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
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParam(Exception e) {
        log.warn("Tham số không hợp lệ: {}", e.getMessage());
        return json(HttpStatus.BAD_REQUEST, ApiError.of(
                "request.invalid_parameter", "Tham số không hợp lệ.", TraceIdFilter.current()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("Không đọc được thân request: {}", MoTaLoiAnToan.cua(e));
        return json(HttpStatus.BAD_REQUEST, ApiError.of(
                "request.malformed", "Nội dung gửi lên không đọc được.", TraceIdFilter.current()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return json(HttpStatus.NOT_FOUND, ApiError.of(
                "resource.not_found", "Không tìm thấy.", TraceIdFilter.current()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDaNgatKetNoi(AsyncRequestNotUsableException e) {
        log.debug("Client đã ngắt kết nối trước khi response ghi xong: {}", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        String traceId = TraceIdFilter.current();
        log.error("Lỗi ngoài dự kiến [traceId={}]", traceId, e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(
                "internal.error", GENERIC_MESSAGE, traceId));
    }

}
