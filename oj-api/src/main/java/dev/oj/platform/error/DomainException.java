package dev.oj.platform.error;

import java.time.Duration;

public abstract class DomainException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        INVALID,
        FORBIDDEN,
        UNAUTHENTICATED,
        CONFLICT,
        RATE_LIMITED,
        UNAVAILABLE
    }

    private final Kind kind;
    private final String code;
    private final String publicMessage;
    private final Duration retryAfter;

    protected DomainException(Kind kind, String code, String publicMessage) {
        this(kind, code, publicMessage, publicMessage, null, null);
    }

    protected DomainException(Kind kind, String code, String publicMessage, String logMessage) {
        this(kind, code, publicMessage, logMessage, null, null);
    }

    protected DomainException(Kind kind, String code, String publicMessage, String logMessage,
                              Duration retryAfter, Throwable cause) {
        super(logMessage, cause);
        if (kind == null || code == null || code.isBlank()
                || publicMessage == null || publicMessage.isBlank()) {
            throw new IllegalArgumentException("DomainException cần đủ kind, code và publicMessage");
        }
        this.kind = kind;
        this.code = code;
        this.publicMessage = publicMessage;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public String publicMessage() {
        return publicMessage;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

}
