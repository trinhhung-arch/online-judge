package dev.oj.worker.run.checker;

import dev.oj.contract.CheckerType;

import java.math.BigDecimal;

/** Chọn checker theo hợp đồng. Một chỗ duy nhất biết cả ba hiện thực. */
public final class Checkers {

    private Checkers() {
    }

    /**
     * @param epsilon bắt buộc có khi và chỉ khi {@code type=FLOAT} — {@code JudgeJobDto} đã
     *                kiểm bất biến đó ở tầng hợp đồng, đây chỉ là chỗ dùng lại nó
     */
    public static Checker of(CheckerType type, BigDecimal epsilon) {
        return switch (type) {
            case EXACT -> new ExactChecker();
            case TOKEN -> new TokenChecker();
            case FLOAT -> new FloatChecker(epsilon);
        };
    }
}
