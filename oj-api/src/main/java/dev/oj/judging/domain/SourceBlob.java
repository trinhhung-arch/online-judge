package dev.oj.judging.domain;

import dev.oj.contract.Sha256;

import java.nio.charset.StandardCharsets;

public record SourceBlob(String sha256, String content, int byteSize) {

    public SourceBlob {
        if (content == null) {
            throw new NullPointerException("content");
        }
        if (!Sha256.isHex(sha256)) {
            throw new IllegalArgumentException("sha256 phải là 64 ký tự hex chữ thường");
        }
        if (byteSize <= 0) {
            throw JudgingException.emptySource();
        }
        if (byteSize > DomainRules.MAX_SOURCE_BYTES) {
            throw JudgingException.sourceTooLarge(byteSize);
        }
        if (byteSize != utf8Length(content)) {
            throw new IllegalArgumentException(
                    "byteSize (" + byteSize + ") không khớp độ dài UTF-8 thật ("
                            + utf8Length(content) + ")");
        }
    }

    public static SourceBlob of(String content) {
        if (content == null || content.isBlank()) {
            throw JudgingException.emptySource();
        }
        int bytes = utf8Length(content);
        if (bytes > DomainRules.MAX_SOURCE_BYTES) {
            throw JudgingException.sourceTooLarge(bytes);
        }
        return new SourceBlob(Sha256.hexOf(content), content, bytes);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return "SourceBlob[sha256=" + sha256 + ", byteSize=" + byteSize + "]";
    }
}
