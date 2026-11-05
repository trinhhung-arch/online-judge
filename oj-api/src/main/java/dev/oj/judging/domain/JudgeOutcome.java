package dev.oj.judging.domain;

import dev.oj.contract.Verdict;

public record JudgeOutcome(
        Verdict verdict,
        int score,
        int maxScore,
        Integer failedTestOrdinal,
        Integer timeMs,
        Integer memoryKb) {

    public JudgeOutcome {
        if (verdict == null) {
            throw new NullPointerException("verdict — markDone từ chối một kết quả không có verdict");
        }
        if (score < 0 || maxScore < 0) {
            throw new IllegalArgumentException("điểm không âm: " + score + "/" + maxScore);
        }
        if (score > maxScore) {
            throw new IllegalArgumentException("score (" + score + ") > maxScore (" + maxScore + ")");
        }
        if (failedTestOrdinal != null
                && (failedTestOrdinal < 1 || failedTestOrdinal > DomainRules.MAX_TEST_ORDINAL)) {
            throw new IllegalArgumentException(
                    "failedTestOrdinal ngoài [1.." + DomainRules.MAX_TEST_ORDINAL + "]: "
                            + failedTestOrdinal);
        }
        if (verdict.isAccepted() && failedTestOrdinal != null) {
            throw new IllegalArgumentException("verdict=AC nhưng failedTestOrdinal=" + failedTestOrdinal);
        }

        if (!verdict.hasRuntimeMeasurements()) {
            timeMs = null;
            memoryKb = null;
        }
        if (timeMs != null && timeMs < 0) {
            timeMs = null;
        }
        if (memoryKb != null && memoryKb < 0) {
            memoryKb = null;
        }
    }

    public boolean isAccepted() {
        return verdict.isAccepted();
    }

    public boolean isSystemFailure() {
        return verdict.isSystemFailure();
    }
}
