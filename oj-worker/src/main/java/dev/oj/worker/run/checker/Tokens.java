package dev.oj.worker.run.checker;

import java.nio.charset.StandardCharsets;

/**
 * Tách token trên mảng byte, không dựng {@code String} trung gian.
 *
 * <p>Token = một dãy byte không phải khoảng trắng. Khoảng trắng lấy đúng định nghĩa của
 * {@code isspace()} trong C — space, tab, CR, LF, form feed, vertical tab — vì đó là thứ
 * chương trình của thí sinh dùng để in.
 */
final class Tokens {

    private final byte[] data;
    private int position;
    int start;
    int end;

    Tokens(byte[] data) {
        this.data = data;
    }

    /** Nhảy tới token kế tiếp. {@code false} khi hết. */
    boolean next() {
        while (position < data.length && isSpace(data[position])) {
            position++;
        }
        if (position >= data.length) {
            return false;
        }
        start = position;
        while (position < data.length && !isSpace(data[position])) {
            position++;
        }
        end = position;
        return true;
    }

    int length() {
        return end - start;
    }

    boolean sameAs(Tokens other) {
        if (length() != other.length()) {
            return false;
        }
        for (int i = 0; i < length(); i++) {
            if (data[start + i] != other.data[other.start + i]) {
                return false;
            }
        }
        return true;
    }

    String text() {
        return new String(data, start, length(), StandardCharsets.US_ASCII);
    }

    static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0x0B;
    }
}
