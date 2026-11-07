package dev.oj.judging.domain;

public enum SubmissionStatus {

    QUEUED,

    JUDGING,

    DONE;

    public boolean canTransitionTo(SubmissionStatus next) {
        return switch (this) {
            case QUEUED -> next == JUDGING;
            case JUDGING -> next != JUDGING;
            case DONE -> next == QUEUED;
        };
    }

    public boolean isPending() {
        return this != DONE;
    }

    public static SubmissionStatus fromCode(String code) {
        for (SubmissionStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("submission status không hợp lệ: " + code);
    }
}
