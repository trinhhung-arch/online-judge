package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.SubtaskResultDto;
import dev.oj.contract.SubtaskScoring;
import dev.oj.contract.SubtaskSpecDto;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SubtaskScorer {

    private SubtaskScorer() {
    }

    @FunctionalInterface
    public interface TestExecutor {
        TestRunner.TestOutcome run(TestcaseMetaDto testcase);
    }

    public record Result(
            int score,
            Verdict verdict,
            Integer failedOrdinal,
            int testsRun,
            long maxCpuTimeMs,
            long maxMemoryKb,
            List<SubtaskResultDto> subtasks) {
    }

    public static Result score(JudgeJobDto job, TestExecutor executor) {
        Map<Integer, List<TestcaseMetaDto>> byGroup = groupTests(job.testcases());
        Map<Integer, SubtaskResultDto> done = new LinkedHashMap<>();

        int totalScore = 0;
        int testsRun = 0;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        Verdict overall = Verdict.AC;
        Integer failedOrdinal = null;

        for (SubtaskSpecDto subtask : job.subtasks()) {
            if (!dependenciesSatisfied(subtask, done)) {
                done.put(subtask.ordinal(),
                        SubtaskResultDto.skipped(subtask.ordinal(), subtask.points()));
                continue;
            }

            List<TestcaseMetaDto> tests = byGroup.getOrDefault(subtask.ordinal(), List.of());
            GroupRun run = runGroup(subtask, tests, executor);

            testsRun += run.testsRun;
            maxCpuMs = Math.max(maxCpuMs, run.maxCpuMs);
            maxMemoryKb = Math.max(maxMemoryKb, run.maxMemoryKb);
            totalScore += run.result.score();
            done.put(subtask.ordinal(), run.result);

            if (run.result.verdict() != Verdict.AC && failedOrdinal == null) {
                overall = run.result.verdict();
                failedOrdinal = run.result.failedTestOrdinal();
            }
        }

        if (totalScore < job.maxScore() && overall == Verdict.AC) {
            overall = Verdict.WA;
        }
        return new Result(totalScore, overall, failedOrdinal, testsRun,
                maxCpuMs, maxMemoryKb, List.copyOf(done.values()));
    }

    private static boolean dependenciesSatisfied(SubtaskSpecDto subtask,
                                                 Map<Integer, SubtaskResultDto> done) {
        for (Integer dependency : subtask.dependsOn()) {
            SubtaskResultDto result = done.get(dependency);
            if (result == null || result.isSkipped() || result.score() < result.maxScore()) {
                return false;
            }
        }
        return true;
    }

    private record GroupRun(SubtaskResultDto result, int testsRun, long maxCpuMs, long maxMemoryKb) {
    }

    private static GroupRun runGroup(SubtaskSpecDto subtask, List<TestcaseMetaDto> tests,
                                     TestExecutor executor) {
        int accepted = 0;
        int testsRun = 0;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        Verdict groupVerdict = Verdict.AC;
        Integer failedOrdinal = null;

        for (TestcaseMetaDto testcase : tests) {
            TestRunner.TestOutcome outcome = executor.run(testcase);
            testsRun++;
            maxCpuMs = Math.max(maxCpuMs, outcome.cpuTimeMs());
            maxMemoryKb = Math.max(maxMemoryKb, outcome.memoryKb());

            if (outcome.accepted()) {
                accepted++;
                continue;
            }
            if (failedOrdinal == null) {
                groupVerdict = outcome.verdict();
                failedOrdinal = testcase.ordinal();
            }
            if (subtask.scoring() == SubtaskScoring.MIN) {
                break;
            }
        }

        int score = scoreOf(subtask, accepted, tests.size());
        return new GroupRun(
                new SubtaskResultDto(subtask.ordinal(), groupVerdict, score, subtask.points(),
                        failedOrdinal, (int) maxCpuMs, (int) maxMemoryKb),
                testsRun, maxCpuMs, maxMemoryKb);
    }

    private static int scoreOf(SubtaskSpecDto subtask, int accepted, int testCount) {
        if (testCount == 0) {
            return 0;
        }
        if (accepted == testCount) {
            return subtask.points();
        }
        return subtask.scoring() == SubtaskScoring.MIN
                ? 0
                : subtask.points() * accepted / testCount;
    }

    private static Map<Integer, List<TestcaseMetaDto>> groupTests(List<TestcaseMetaDto> testcases) {
        Map<Integer, List<TestcaseMetaDto>> byGroup = new LinkedHashMap<>();
        for (TestcaseMetaDto testcase : testcases) {
            byGroup.computeIfAbsent(testcase.subtaskOrdinal(), key -> new ArrayList<>())
                    .add(testcase);
        }
        return byGroup;
    }
}
