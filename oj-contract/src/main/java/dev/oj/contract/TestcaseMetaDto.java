package dev.oj.contract;

public record TestcaseMetaDto(
        int ordinal,
        boolean isSample,
        String inputSha256,
        String outputSha256,
        Integer subtaskOrdinal) {

    public static final int MAX_ORDINAL = 1000;

    public TestcaseMetaDto {
        ContractChecks.requireRange(ordinal, 1, MAX_ORDINAL, "ordinal");
        ContractChecks.requireSha256(inputSha256, "inputSha256");
        ContractChecks.requireSha256(outputSha256, "outputSha256");
        ContractChecks.requireNullOrRange(subtaskOrdinal, 1, 100, "subtaskOrdinal");
    }

    public boolean belongsToSubtask() {
        return subtaskOrdinal != null;
    }
}
