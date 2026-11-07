package dev.oj.platform.error;

import java.util.Map;

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
