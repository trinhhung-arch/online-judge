package dev.oj.judging.domain;

import java.time.Instant;

public record JudgeQueueEntry(
        long submissionId,
        int priority,
        int attempt,
        Instant enqueuedAt,
        Instant claimedAt,
        Integer claimedByHost,
        Instant leaseUntil,
        int ieRetryCount) {

    public JudgeQueueEntry {
        if (submissionId <= 0) {
            throw new IllegalArgumentException("submissionId phải dương");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority không âm: " + priority);
        }
        if (attempt < DomainRules.ATTEMPT_NONE || ieRetryCount < 0) {
            throw new IllegalArgumentException("attempt và ieRetryCount không âm");
        }
        if (enqueuedAt == null) {
            throw new NullPointerException("enqueuedAt");
        }
        if ((claimedAt == null) != (leaseUntil == null)) {
            throw new IllegalStateException(
                    "claimedAt và leaseUntil phải cùng có hoặc cùng không — submission " + submissionId);
        }
        if (claimedAt == null && claimedByHost != null) {
            throw new IllegalStateException("chưa claim thì không có claimedByHost");
        }
    }

    public boolean isClaimed() {
        return claimedAt != null;
    }

    public boolean isWaiting() {
        return claimedAt == null;
    }

    public boolean isLeaseExpired(Instant now) {
        return isClaimed() && leaseUntil.isBefore(now);
    }

    public int nextAttempt() {
        return attempt + 1;
    }

    public boolean canRetryIe(int maxRetries) {
        return ieRetryCount < maxRetries;
    }

    public boolean isLive() {
        return priority == DomainRules.PRIORITY_LIVE;
    }
}
