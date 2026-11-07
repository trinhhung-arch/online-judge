package dev.oj.contract;

public enum Verdict {

    AC,
    WA,
    TLE,
    MLE,
    RE,
    CE,
    IE;

    public boolean isAccepted() {
        return this == AC;
    }

    public boolean isSystemFailure() {
        return this == IE;
    }

    public boolean isCompileError() {
        return this == CE;
    }

    public boolean hasRuntimeMeasurements() {
        return this != CE && this != IE;
    }

    public static Verdict fromCode(String code) {
        for (Verdict v : values()) {
            if (v.name().equals(code)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Verdict không hợp lệ: " + code);
    }
}
