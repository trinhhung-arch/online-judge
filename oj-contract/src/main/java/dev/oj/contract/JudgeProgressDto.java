package dev.oj.contract;

import java.util.List;

public record JudgeProgressDto(
        long submissionId,
        int attempt,
        int fromOrdinal,
        int toOrdinal,
        int totalTests,
        List<TestOutcome> outcomes) {

    public static final int BATCH_SIZE = 20;

    public JudgeProgressDto {
        ContractChecks.requirePositive(submissionId, "submissionId");
        ContractChecks.requireAtLeast(attempt, 1, "attempt");
        ContractChecks.requireRange(fromOrdinal, 1, TestcaseMetaDto.MAX_ORDINAL, "fromOrdinal");
        ContractChecks.requireRange(toOrdinal, fromOrdinal, TestcaseMetaDto.MAX_ORDINAL, "toOrdinal");
        ContractChecks.requireRange(totalTests, toOrdinal, TestcaseMetaDto.MAX_ORDINAL, "totalTests");
        outcomes = ContractChecks.frozen(outcomes);
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("outcomes rỗng — đừng gửi một lô không có gì");
        }
    }

    public int percentDone() {
        return (int) Math.round(100.0 * toOrdinal / totalTests);
    }

    public record TestOutcome(
            int ordinal,
            Verdict verdict,
            Integer timeMs,
            Integer memoryKb) {

        public TestOutcome {
            ContractChecks.requireRange(ordinal, 1, TestcaseMetaDto.MAX_ORDINAL, "ordinal");
            if (verdict == null) {
                throw new NullPointerException("verdict");
            }
            ContractChecks.requireNullOrRange(timeMs, 0, Integer.MAX_VALUE, "timeMs");
            ContractChecks.requireNullOrRange(memoryKb, 0, Integer.MAX_VALUE, "memoryKb");
        }
    }
}
