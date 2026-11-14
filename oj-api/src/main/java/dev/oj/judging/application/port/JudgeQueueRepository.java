package dev.oj.judging.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JudgeQueueRepository {

    void enqueue(long submissionId, int priority);

    Optional<ClaimedJob> claim(String hostName, int leaseSeconds);

    Optional<ReleasedSubmission> releaseWithOptimisticLock(long submissionId, int attempt);

    List<Long> reapExpired(int maxReaps);

    List<ExhaustedJob> exhaustedJobs(int maxReaps);

    boolean retryIe(long submissionId, int attempt, int maxRetries);

    boolean renewLease(long submissionId, int attempt, int leaseSeconds);

    QueueStats queueDepth();

    record ClaimedJob(
            long submissionId,
            int attempt,
            long problemId,
            int testdataVersion,
            String sourceSha256,
            String sourceContent,
            LanguageSpec language) {

        public record LanguageSpec(
                String code,
                String sourceExtension,
                String compileCommand,
                String runCommand,
                int compileTimeLimitMs,
                int compileMemoryKb,
                BigDecimal timeMultiplier,
                int startupOverheadMs,
                int memoryOverheadKb) {
        }

        @Override
        public String toString() {
            return "ClaimedJob[submissionId=" + submissionId + ", attempt=" + attempt
                    + ", problemId=" + problemId + ", language="
                    + (language == null ? null : language.code()) + "]";
        }
    }

    record ReleasedSubmission(long submissionId, int attempt, int languageId, int testdataVersion) {
    }

    record ExhaustedJob(long submissionId, int attempt, Instant claimedAt) {
    }

    record QueueStats(int waiting, int claimed, Instant oldestEnqueuedAt) {

        public boolean isEmpty() {
            return waiting == 0 && claimed == 0;
        }
    }
}
