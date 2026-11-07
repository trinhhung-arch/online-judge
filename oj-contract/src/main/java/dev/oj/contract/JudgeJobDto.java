package dev.oj.contract;

import java.math.BigDecimal;
import java.util.List;

public record JudgeJobDto(
        long submissionId,
        int attempt,
        String traceId,
        String languageCode,
        String compileCommand,
        String runCommand,
        int compileTimeLimitMs,
        int compileMemoryKb,
        int timeLimitMs,
        int memoryLimitKb,
        int outputLimitKb,
        String sourceFileName,
        String sourceContent,
        String sourceSha256,
        CheckerType checkerType,
        BigDecimal checkerEpsilon,
        ScoringMode scoringMode,
        int maxScore,
        int testdataVersion,
        String testdataManifestSha256,
        List<SubtaskSpecDto> subtasks,
        List<TestcaseMetaDto> testcases) {

    public static final int MAX_SOURCE_BYTES = 65_536;

    public JudgeJobDto {
        ContractChecks.requirePositive(submissionId, "submissionId");
        ContractChecks.requireAtLeast(attempt, 1, "attempt");
        ContractChecks.requireText(traceId, "traceId");
        ContractChecks.requireText(languageCode, "languageCode");
        ContractChecks.requireText(runCommand, "runCommand");
        ContractChecks.requirePositive(compileTimeLimitMs, "compileTimeLimitMs");
        ContractChecks.requirePositive(compileMemoryKb, "compileMemoryKb");
        ContractChecks.requireRange(timeLimitMs, 100, 30_000, "timeLimitMs");
        ContractChecks.requireRange(memoryLimitKb, 16_384, 1_048_576, "memoryLimitKb");
        ContractChecks.requirePositive(outputLimitKb, "outputLimitKb");

        requireSafeFileName(sourceFileName);
        ContractChecks.requireText(sourceContent, "sourceContent");
        if (ContractChecks.utf8Length(sourceContent) > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "sourceContent vượt " + MAX_SOURCE_BYTES + " byte — lẽ ra đã bị chặn ở "
                            + "SubmitSolutionUseCase, tới được đây là có bug");
        }
        ContractChecks.requireSha256(sourceSha256, "sourceSha256");

        if (checkerType == null) {
            throw new NullPointerException("checkerType");
        }
        boolean laFloat = checkerType == CheckerType.FLOAT;
        if (laFloat != (checkerEpsilon != null)) {
            throw new IllegalArgumentException(
                    "checkerEpsilon bắt buộc có khi và chỉ khi checkerType=FLOAT (nhận "
                            + checkerType + " / " + checkerEpsilon + ")");
        }
        if (scoringMode == null) {
            throw new NullPointerException("scoringMode");
        }
        ContractChecks.requireAtLeast(maxScore, 1, "maxScore");
        ContractChecks.requireAtLeast(testdataVersion, 1, "testdataVersion");
        ContractChecks.requireSha256(testdataManifestSha256, "testdataManifestSha256");

        testcases = ContractChecks.frozen(testcases);
        ContractChecks.requireRange(
                testcases.size(), 1, TestcaseMetaDto.MAX_ORDINAL, "testcases.size");

        subtasks = ContractChecks.frozen(subtasks);
        validateSubtasks(scoringMode, maxScore, subtasks, testcases);
    }

    private static void requireSafeFileName(String name) {
        ContractChecks.requireText(name, "sourceFileName");
        if (name.contains("/") || name.contains("\\") || name.contains("..")
                || name.startsWith(".") || name.length() > 255) {
            throw new IllegalArgumentException(
                    "sourceFileName phải là một tên file trần, không phải đường dẫn: " + name);
        }
    }

    private static void validateSubtasks(ScoringMode scoringMode, int maxScore,
                                         List<SubtaskSpecDto> subtasks,
                                         List<TestcaseMetaDto> testcases) {
        boolean theoNhom = scoringMode == ScoringMode.SUBTASK;
        if (theoNhom != !subtasks.isEmpty()) {
            throw new IllegalArgumentException(
                    "subtasks phải có nội dung khi và chỉ khi scoringMode=SUBTASK (nhận "
                            + scoringMode + " với " + subtasks.size() + " nhóm)");
        }
        if (!theoNhom) {
            return;
        }

        java.util.Set<Integer> ordinals = new java.util.LinkedHashSet<>();
        int tongDiem = 0;
        for (SubtaskSpecDto subtask : subtasks) {
            if (!ordinals.add(subtask.ordinal())) {
                throw new IllegalArgumentException("Nhóm trùng ordinal: " + subtask.ordinal());
            }
            tongDiem += subtask.points();
        }

        if (tongDiem != maxScore) {
            throw new IllegalArgumentException(
                    "Tổng điểm các nhóm (" + tongDiem + ") khác maxScore (" + maxScore + ")");
        }

        for (SubtaskSpecDto subtask : subtasks) {
            for (Integer dependency : subtask.dependsOn()) {
                if (!ordinals.contains(dependency)) {
                    throw new IllegalArgumentException("Nhóm " + subtask.ordinal()
                            + " phụ thuộc nhóm " + dependency + " không tồn tại");
                }
            }
        }
        for (TestcaseMetaDto testcase : testcases) {
            if (!testcase.belongsToSubtask()) {
                throw new IllegalArgumentException(
                        "Đề chấm theo nhóm nhưng test " + testcase.ordinal()
                                + " không thuộc nhóm nào — điểm của nó sẽ rơi vào hư không");
            }
            if (!ordinals.contains(testcase.subtaskOrdinal())) {
                throw new IllegalArgumentException("Test " + testcase.ordinal()
                        + " thuộc nhóm " + testcase.subtaskOrdinal() + " không tồn tại");
            }
        }
    }

    public boolean isInterpreted() {
        return compileCommand == null || compileCommand.isBlank();
    }

    public String compileCacheKey() {
        return Sha256.hexOf(sourceSha256 + ' ' + languageCode + ' '
                + (compileCommand == null ? "" : compileCommand));
    }

    public long effectiveCpuLimitMs(BigDecimal hostFactor) {
        return BigDecimal.valueOf(timeLimitMs).multiply(hostFactor).longValue();
    }

    public long effectiveWallLimitMs(BigDecimal hostFactor) {
        return 2 * effectiveCpuLimitMs(hostFactor);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long submissionId;
        private int attempt;
        private String traceId;
        private String languageCode;
        private String compileCommand;
        private String runCommand;
        private int compileTimeLimitMs = 10_000;
        private int compileMemoryKb = 1_048_576;
        private int timeLimitMs;
        private int memoryLimitKb;
        private int outputLimitKb = 65_536;
        private String sourceFileName;
        private String sourceContent;
        private String sourceSha256;
        private CheckerType checkerType = CheckerType.TOKEN;
        private BigDecimal checkerEpsilon;
        private ScoringMode scoringMode = ScoringMode.ALL_OR_NOTHING;
        private int maxScore;
        private int testdataVersion;
        private String testdataManifestSha256;
        private List<SubtaskSpecDto> subtasks = List.of();
        private List<TestcaseMetaDto> testcases = List.of();

        private Builder() {
        }

        public Builder submission(long submissionId, int attempt) {
            this.submissionId = submissionId;
            this.attempt = attempt;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder language(String languageCode, String compileCommand, String runCommand) {
            this.languageCode = languageCode;
            this.compileCommand = compileCommand;
            this.runCommand = runCommand;
            return this;
        }

        public Builder compileLimits(int compileTimeLimitMs, int compileMemoryKb) {
            this.compileTimeLimitMs = compileTimeLimitMs;
            this.compileMemoryKb = compileMemoryKb;
            return this;
        }

        public Builder runLimitsOnReferenceHost(int timeLimitMs, int memoryLimitKb, int outputLimitKb) {
            this.timeLimitMs = timeLimitMs;
            this.memoryLimitKb = memoryLimitKb;
            this.outputLimitKb = outputLimitKb;
            return this;
        }

        public Builder source(String sourceFileName, String sourceContent, String sourceSha256) {
            this.sourceFileName = sourceFileName;
            this.sourceContent = sourceContent;
            this.sourceSha256 = sourceSha256;
            return this;
        }

        public Builder checker(CheckerType checkerType, BigDecimal checkerEpsilon) {
            this.checkerType = checkerType;
            this.checkerEpsilon = checkerEpsilon;
            return this;
        }

        public Builder scoring(ScoringMode scoringMode, int maxScore) {
            this.scoringMode = scoringMode;
            this.maxScore = maxScore;
            return this;
        }

        public Builder testdata(int version, String manifestSha256, List<TestcaseMetaDto> testcases) {
            this.testdataVersion = version;
            this.testdataManifestSha256 = manifestSha256;
            this.testcases = testcases;
            return this;
        }

        public Builder subtasks(List<SubtaskSpecDto> subtasks) {
            this.subtasks = subtasks;
            return this;
        }

        public JudgeJobDto build() {
            return new JudgeJobDto(submissionId, attempt, traceId, languageCode,
                    compileCommand, runCommand, compileTimeLimitMs, compileMemoryKb,
                    timeLimitMs, memoryLimitKb, outputLimitKb, sourceFileName, sourceContent,
                    sourceSha256,
                    checkerType, checkerEpsilon, scoringMode, maxScore, testdataVersion,
                    testdataManifestSha256, subtasks, testcases);
        }
    }

    @Override
    public String toString() {
        return "JudgeJobDto[submissionId=" + submissionId + ", attempt=" + attempt
                + ", language=" + languageCode + ", tests=" + testcases.size()
                + ", testdataVersion=" + testdataVersion + ", traceId=" + traceId + "]";
    }
}
