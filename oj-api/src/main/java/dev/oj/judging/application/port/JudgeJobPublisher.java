package dev.oj.judging.application.port;

public interface JudgeJobPublisher {

    void publishEnqueued(long submissionId);

    default void publishRejudgeEnqueued(long submissionId) {
    }
}
