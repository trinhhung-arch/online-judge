package dev.oj.problems.domain;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.SubtaskSpecDto;
import dev.oj.contract.TestcaseMetaDto;

import java.math.BigDecimal;
import java.util.List;

public record JudgeSpec(
        long problemId,
        int timeLimitMs,
        int memoryLimitKb,
        int outputLimitKb,
        CheckerType checkerType,
        BigDecimal checkerEpsilon,
        ScoringMode scoringMode,
        int testdataVersion,
        String manifestSha256,
        List<SubtaskSpecDto> subtasks,
        List<TestcaseMetaDto> testcases) {

    public JudgeSpec {
        if (problemId <= 0) {
            throw new IllegalArgumentException("problemId phải dương");
        }
        if (testdataVersion < 1) {
            throw new IllegalArgumentException(
                    "testdataVersion phải >= 1 — đề chưa có testdata thì không dựng được job");
        }
        subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
        testcases = testcases == null ? List.of() : List.copyOf(testcases);
        if (testcases.isEmpty()) {
            throw new IllegalArgumentException(
                    "JudgeSpec không có testcase nào cho đề " + problemId
                            + " version " + testdataVersion);
        }
    }

    public int timeLimitOnReferenceHost(BigDecimal languageMultiplier, int startupOverheadMs) {
        return timeLimitOnReferenceHost(timeLimitMs, languageMultiplier, startupOverheadMs);
    }

    public static int timeLimitOnReferenceHost(int problemTimeLimitMs,
                                               BigDecimal languageMultiplier,
                                               int startupOverheadMs) {
        if (languageMultiplier == null || languageMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("languageMultiplier phải dương");
        }
        long limit = BigDecimal.valueOf(problemTimeLimitMs).multiply(languageMultiplier).longValue()
                + startupOverheadMs;
        return (int) Math.min(30_000L, Math.max(100L, limit));
    }

    public int memoryLimitOnReferenceHost(int memoryOverheadKb) {
        return memoryLimitOnReferenceHost(memoryLimitKb, memoryOverheadKb);
    }

    public static int memoryLimitOnReferenceHost(int problemMemoryLimitKb, int memoryOverheadKb) {
        long limit = (long) problemMemoryLimitKb + memoryOverheadKb;
        return (int) Math.min(1_048_576L, Math.max(16_384L, limit));
    }

    public int maxScore() {
        if (scoringMode != ScoringMode.SUBTASK) {
            return ALL_OR_NOTHING_MAX_SCORE;
        }
        return subtasks.stream().mapToInt(SubtaskSpecDto::points).sum();
    }

    public static final int ALL_OR_NOTHING_MAX_SCORE = 100;

    public int testCount() {
        return testcases.size();
    }
}
