package dev.oj.worker.run.checker;

public final class ExactChecker implements Checker {

    @Override
    public boolean matches(byte[] expected, byte[] actual) {
        int e = trimmedLength(expected);
        int a = trimmedLength(actual);
        if (e != a) {
            return false;
        }
        for (int i = 0; i < e; i++) {
            if (expected[i] != actual[i]) {
                return false;
            }
        }
        return true;
    }

    private static int trimmedLength(byte[] data) {
        int length = data.length;
        while (length > 0 && Tokens.isSpace(data[length - 1])) {
            length--;
        }
        return length;
    }
}
