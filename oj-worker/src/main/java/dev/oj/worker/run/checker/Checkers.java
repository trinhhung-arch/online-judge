package dev.oj.worker.run.checker;

import dev.oj.contract.CheckerType;

import java.math.BigDecimal;

public final class Checkers {

    private Checkers() {
    }

    public static Checker of(CheckerType type, BigDecimal epsilon) {
        return switch (type) {
            case EXACT -> new ExactChecker();
            case TOKEN -> new TokenChecker();
            case FLOAT -> new FloatChecker(epsilon);
        };
    }
}
