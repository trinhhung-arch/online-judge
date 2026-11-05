package dev.oj.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record JudgeResultDto(
        long submissionId,
        int attempt,
        Verdict verdict,
        int score,
        int maxScore,
        Integer failedTestOrdinal,
        int testsRun,
        Integer timeMs,
        Integer memoryKb,
        String compileLog,
        String isolateStatus,
        String hostName,
        BigDecimal hostFactor,
        Instant startedAt,
        List<SubtaskResultDto> subtasks) {

    public static final int MAX_COMPILE_LOG_BYTES = 32_768;

    public static final int MAX_ISOLATE_STATUS_BYTES = 4_096;

    public JudgeResultDto {
        ContractChecks.requirePositive(submissionId, "submissionId");
        ContractChecks.requireAtLeast(attempt, 1, "attempt");
        if (verdict == null) {
            throw new NullPointerException("verdict");
        }
        ContractChecks.requireAtLeast(score, 0, "score");
        ContractChecks.requireAtLeast(maxScore, 0, "maxScore");
        if (score > maxScore) {
            throw new IllegalArgumentException("score (" + score + ") > maxScore (" + maxScore + ")");
        }
        ContractChecks.requireNullOrRange(
                failedTestOrdinal, 1, TestcaseMetaDto.MAX_ORDINAL, "failedTestOrdinal");
        ContractChecks.requireAtLeast(testsRun, 0, "testsRun");
        ContractChecks.requireNullOrRange(timeMs, 0, Integer.MAX_VALUE, "timeMs");
        ContractChecks.requireNullOrRange(memoryKb, 0, Integer.MAX_VALUE, "memoryKb");
        ContractChecks.requireText(hostName, "hostName");
        if (hostFactor == null || hostFactor.signum() <= 0) {
            throw new IllegalArgumentException("hostFactor phải dương, nhận được: " + hostFactor);
        }
        if (startedAt == null) {
            throw new NullPointerException("startedAt");
        }

        compileLog = ContractChecks.truncateUtf8(compileLog, MAX_COMPILE_LOG_BYTES);
        isolateStatus = ContractChecks.truncateUtf8(isolateStatus, MAX_ISOLATE_STATUS_BYTES);
        subtasks = ContractChecks.frozen(subtasks);

        if (verdict == Verdict.AC && failedTestOrdinal != null) {
            throw new IllegalArgumentException(
                    "verdict=AC nhưng failedTestOrdinal=" + failedTestOrdinal);
        }
    }

    public static JudgeResultDto internalError(long submissionId, int attempt, String hostName,
                                               BigDecimal hostFactor, Instant startedAt,
                                               String isolateStatus) {
        return new JudgeResultDto(submissionId, attempt, Verdict.IE, 0, 0, null, 0,
                null, null, null, isolateStatus, hostName, hostFactor, startedAt, List.of());
    }

    public static JudgeResultDto compileError(long submissionId, int attempt, int maxScore,
                                              String hostName, BigDecimal hostFactor,
                                              Instant startedAt, String compileLog) {
        return new JudgeResultDto(submissionId, attempt, Verdict.CE, 0, maxScore, null, 0,
                null, null, compileLog, null, hostName, hostFactor, startedAt, List.of());
    }

    @Override
    public String toString() {
        return "JudgeResultDto[submissionId=" + submissionId + ", attempt=" + attempt
                + ", verdict=" + verdict + ", score=" + score + "/" + maxScore
                + ", testsRun=" + testsRun + ", timeMs=" + timeMs + ", memoryKb=" + memoryKb
                + ", host=" + hostName + "]";
    }
}
