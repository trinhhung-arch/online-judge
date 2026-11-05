package dev.oj.worker.run.checker;

import java.math.BigDecimal;

public final class FloatChecker implements Checker {

    private final double epsilon;

    public FloatChecker(BigDecimal epsilon) {
        if (epsilon == null || epsilon.signum() < 0) {
            throw new IllegalArgumentException("epsilon phải >= 0, nhận: " + epsilon);
        }
        this.epsilon = epsilon.doubleValue();
    }

    @Override
    public boolean matches(byte[] expected, byte[] actual) {
        Tokens want = new Tokens(expected);
        Tokens got = new Tokens(actual);
        while (want.next()) {
            if (!got.next() || !tokenMatches(want, got)) {
                return false;
            }
        }
        return !got.next();
    }

    private boolean tokenMatches(Tokens want, Tokens got) {
        if (want.sameAs(got)) {
            return true;
        }
        Double w = asNumber(want.text());
        Double g = asNumber(got.text());
        if (w == null || g == null) {
            return false;
        }
        double difference = Math.abs(w - g);
        return difference <= epsilon || difference <= epsilon * Math.abs(w);
    }

    private static Double asNumber(String token) {
        try {
            double value = Double.parseDouble(token);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
