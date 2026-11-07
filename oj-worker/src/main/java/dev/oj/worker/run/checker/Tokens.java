package dev.oj.worker.run.checker;

import java.nio.charset.StandardCharsets;

final class Tokens {

    private final byte[] data;
    private int position;
    int start;
    int end;

    Tokens(byte[] data) {
        this.data = data;
    }

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
