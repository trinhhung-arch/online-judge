package dev.oj.contract;

public enum SubtaskScoring {

    MIN,

    SUM;

    public String code() {
        return name();
    }

    public static SubtaskScoring fromCode(String code) {
        for (SubtaskScoring scoring : values()) {
            if (scoring.name().equals(code)) {
                return scoring;
            }
        }
        throw new IllegalArgumentException("subtasks.scoring không hợp lệ: " + code);
    }
}
