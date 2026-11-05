package dev.oj.problems.domain;

public enum ProblemStatus {

    DRAFT,

    PUBLISHED,

    RETIRED;

    public boolean isPubliclyVisible() {
        return this == PUBLISHED || this == RETIRED;
    }

    public boolean acceptsSubmissions() {
        return this == PUBLISHED;
    }

    public static ProblemStatus fromCode(String code) {
        for (ProblemStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("problem status không hợp lệ: " + code);
    }
}
