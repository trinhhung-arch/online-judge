package dev.oj.worker.run.checker;

public final class TokenChecker implements Checker {

    @Override
    public boolean matches(byte[] expected, byte[] actual) {
        Tokens want = new Tokens(expected);
        Tokens got = new Tokens(actual);
        while (want.next()) {
            if (!got.next() || !want.sameAs(got)) {
                return false;
            }
        }
        return !got.next();
    }
}
