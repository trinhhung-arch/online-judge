package dev.oj.problems.domain;

public enum FeedbackLevel {

    NONE,

    TEST_INDEX,

    SAMPLE_DETAIL;

    public static final FeedbackLevel DEFAULT = TEST_INDEX;

    public boolean revealsFailedTestOrdinal() {
        return this != NONE;
    }

    public boolean revealsSampleDetail() {
        return this == SAMPLE_DETAIL;
    }

    public static FeedbackLevel fromCode(String code) {
        for (FeedbackLevel level : values()) {
            if (level.name().equals(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("feedback_level không hợp lệ: " + code);
    }

}
