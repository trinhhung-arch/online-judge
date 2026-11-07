package dev.oj.problems.domain;

public record FeedbackPolicy(FeedbackLevel level) {

    public FeedbackPolicy {
        if (level == null) {
            throw new NullPointerException("level — không có mặc định ngầm cho một bộ lọc");
        }
    }

    public static FeedbackPolicy of(FeedbackLevel level) {
        return new FeedbackPolicy(level);
    }

    public Integer failedTestOrdinal(Integer actual) {
        return level.revealsFailedTestOrdinal() ? actual : null;
    }

    public boolean revealsSampleDetail() {
        return level.revealsSampleDetail();
    }
}
