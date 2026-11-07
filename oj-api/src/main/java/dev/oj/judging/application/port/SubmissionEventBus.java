package dev.oj.judging.application.port;

import java.time.Instant;

public interface SubmissionEventBus {

    void publish(SubmissionEvent event);

    AutoCloseable subscribe(long submissionId, SubmissionEventListener listener);

    @FunctionalInterface
    interface SubmissionEventListener {
        void onEvent(SubmissionEvent event);
    }

    record SubmissionEvent(
            long submissionId,
            int attempt,
            String status,
            String verdict,
            Integer testsDone,
            Integer totalTests,
            Instant at) {

        public static SubmissionEvent progress(long submissionId, int attempt,
                                               int testsDone, int totalTests) {
            return new SubmissionEvent(submissionId, attempt, "JUDGING", null,
                    testsDone, totalTests, Instant.now());
        }

        public static SubmissionEvent done(long submissionId, int attempt, String verdict) {
            return new SubmissionEvent(submissionId, attempt, "DONE", verdict,
                    null, null, Instant.now());
        }

        public boolean isTerminal() {
            return "DONE".equals(status);
        }
    }
}
