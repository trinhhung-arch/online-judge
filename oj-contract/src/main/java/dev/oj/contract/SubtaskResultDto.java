package dev.oj.contract;

public record SubtaskResultDto(
        int subtaskOrdinal,
        Verdict verdict,
        int score,
        int maxScore,
        Integer failedTestOrdinal,
        Integer timeMs,
        Integer memoryKb) {

    public SubtaskResultDto {
        ContractChecks.requireRange(subtaskOrdinal, 1, 100, "subtaskOrdinal");
        ContractChecks.requireAtLeast(score, 0, "score");
        ContractChecks.requireAtLeast(maxScore, 0, "maxScore");
        if (score > maxScore) {
            throw new IllegalArgumentException(
                    "score (" + score + ") > maxScore (" + maxScore + ") ở subtask " + subtaskOrdinal);
        }
        ContractChecks.requireNullOrRange(
                failedTestOrdinal, 1, TestcaseMetaDto.MAX_ORDINAL, "failedTestOrdinal");
    }

    public boolean isSkipped() {
        return verdict == null;
    }

    public static SubtaskResultDto skipped(int subtaskOrdinal, int maxScore) {
        return new SubtaskResultDto(subtaskOrdinal, null, 0, maxScore, null, null, null);
    }
}
