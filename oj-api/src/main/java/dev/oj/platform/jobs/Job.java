package dev.oj.platform.jobs;

import java.time.Instant;
import java.util.Map;

public record Job(
        long id,
        JobType type,
        JobStatus status,
        Map<String, Object> params,
        Map<String, Object> cursorState,
        Integer totalItems,
        int doneItems,
        String leaseOwner,
        Instant leaseUntil,
        Long createdBy,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage) {

    public Job {
        if (type == null || status == null) {
            throw new NullPointerException("type và status bắt buộc");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
        cursorState = cursorState == null ? Map.of() : Map.copyOf(cursorState);
    }

    public Integer phanTram() {
        if (totalItems == null || totalItems <= 0) {
            return null;
        }
        return (int) Math.min(100L, Math.round(doneItems * 100.0 / totalItems));
    }
}
