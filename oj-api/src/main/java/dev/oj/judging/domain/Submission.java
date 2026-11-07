package dev.oj.judging.domain;

import dev.oj.contract.Sha256;

import java.time.Instant;

public record Submission(
        long id,
        long userId,
        long problemId,
        Long contestId,
        int languageId,
        String sourceSha256,
        int sourceBytes,
        Instant createdAt,
        SubmissionStatus status,
        int attempt,
        Integer testdataVersion,
        JudgeOutcome outcome,
        Instant judgedAt,
        Instant hiddenAt,
        Long hiddenBy) {

    public Submission {
        if (id <= 0 || userId <= 0 || problemId <= 0 || languageId <= 0) {
            throw new IllegalArgumentException("id, userId, problemId, languageId phải dương");
        }
        if (contestId != null && contestId <= 0) {
            throw new IllegalArgumentException("contestId phải dương hoặc null");
        }
        if (!Sha256.isHex(sourceSha256)) {
            throw new IllegalArgumentException("sourceSha256 phải là 64 ký tự hex chữ thường");
        }
        if (sourceBytes <= 0 || sourceBytes > DomainRules.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("sourceBytes ngoài (0.." + DomainRules.MAX_SOURCE_BYTES + "]");
        }
        if (createdAt == null || status == null) {
            throw new NullPointerException("createdAt và status đều bắt buộc");
        }
        if (attempt < DomainRules.ATTEMPT_NONE) {
            throw new IllegalArgumentException("attempt không âm: " + attempt);
        }
        if (status == SubmissionStatus.DONE && outcome == null) {
            throw new IllegalStateException("status=DONE nhưng không có outcome — id " + id);
        }
        if ((outcome == null) != (judgedAt == null)) {
            throw new IllegalStateException("outcome và judgedAt phải cùng có hoặc cùng không");
        }
        if ((hiddenAt == null) != (hiddenBy == null)) {
            throw new IllegalStateException("hiddenAt và hiddenBy phải cùng có hoặc cùng không");
        }
    }

    public Submission markJudging(int newAttempt) {
        if (newAttempt <= attempt) {
            throw new IllegalArgumentException(
                    "attempt chỉ tăng: " + attempt + " -> " + newAttempt + " (submission " + id + ")");
        }
        requireTransitionTo(SubmissionStatus.JUDGING);
        return new Submission(id, userId, problemId, contestId, languageId, sourceSha256,
                sourceBytes, createdAt, SubmissionStatus.JUDGING, newAttempt, testdataVersion,
                outcome, judgedAt, hiddenAt, hiddenBy);
    }

    public Submission markDone(JudgeOutcome result, Instant judgedAt) {
        if (result == null) {
            throw new IllegalArgumentException("markDone cần một outcome — submission " + id);
        }
        if (judgedAt == null) {
            throw new NullPointerException("judgedAt");
        }
        requireTransitionTo(SubmissionStatus.DONE);
        return new Submission(id, userId, problemId, contestId, languageId, sourceSha256,
                sourceBytes, createdAt, SubmissionStatus.DONE, attempt, testdataVersion,
                result, judgedAt, hiddenAt, hiddenBy);
    }

    public Submission markQueued() {
        requireTransitionTo(SubmissionStatus.QUEUED);
        return new Submission(id, userId, problemId, contestId, languageId, sourceSha256,
                sourceBytes, createdAt, SubmissionStatus.QUEUED, attempt, testdataVersion,
                outcome, judgedAt, hiddenAt, hiddenBy);
    }

    public boolean isHidden() {
        return hiddenAt != null;
    }

    public boolean isInContest() {
        return contestId != null;
    }

    public boolean isPending() {
        return status.isPending();
    }

    private void requireTransitionTo(SubmissionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "submission " + id + ": không được chuyển " + status + " -> " + next);
        }
    }
}
