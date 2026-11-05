package dev.oj.contract;

public enum CheckerType {

    EXACT("exact"),
    TOKEN("token"),
    FLOAT("float");

    private final String code;

    CheckerType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CheckerType fromCode(String code) {
        for (CheckerType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("checker_type không hợp lệ: " + code);
    }
}
