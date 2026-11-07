package dev.oj.worker.pipeline;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.calibration.HostBenchmark;
import dev.oj.worker.compile.Compiler;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.report.BatchReporter;
import dev.oj.worker.run.SubtaskScorer;
import dev.oj.worker.run.TestRunner;
import dev.oj.worker.run.checker.Checker;
import dev.oj.worker.run.checker.Checkers;
import dev.oj.worker.sandbox.IsolateBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final WorkerProperties properties;
    private final SlotPool slots;
    private final Compiler compiler;
    private final TestRunner testRunner;
    private final HostBenchmark hostBenchmark;
    private final BatchReporter batchReporter;

    public JobExecutor(WorkerProperties properties, SlotPool slots, Compiler compiler,
                       TestRunner testRunner, HostBenchmark hostBenchmark,
                       BatchReporter batchReporter) {
        this.properties = properties;
        this.slots = slots;
        this.compiler = compiler;
        this.testRunner = testRunner;
        this.hostBenchmark = hostBenchmark;
        this.batchReporter = batchReporter;
    }

    public JudgeResultDto execute(JudgeJobDto job, String sourceFileName, Instant startedAt)
            throws InterruptedException {
        int boxId = slots.acquire(properties.lease().toMillis());
        try (IsolateBox box = IsolateBox.open(
                properties.sandbox(), boxId, properties.sandbox().cache().dir().resolve("meta"))) {

            Compiler.CompileResult compiled = compiler.compile(box, job, sourceFileName);
            if (compiled.failed()) {
                return JudgeResultDto.compileError(job.submissionId(), job.attempt(),
                        job.maxScore(), properties.hostName(), hostBenchmark.current(),
                        startedAt, compiled.compileLog());
            }
            return judgeAllTests(job, box, compiled, startedAt);

        } finally {
            slots.release(boxId);
        }
    }

    private JudgeResultDto judgeAllTests(JudgeJobDto job, IsolateBox box,
                                         Compiler.CompileResult compiled, Instant startedAt) {
        java.math.BigDecimal hostFactor = hostBenchmark.current();
        Checker checker = Checkers.of(job.checkerType(), job.checkerEpsilon());

        try (BatchReporter.Session progress = batchReporter.open(job)) {
            SubtaskScorer.TestExecutor executor = testcase -> {
                TestRunner.TestOutcome outcome = testRunner.run(
                        box, job, compiled.artifact(), testcase, hostFactor, checker);
                progress.add(testcase.ordinal(), outcome.verdict(),
                        outcome.cpuTimeMs(), outcome.memoryKb());
                return outcome;
            };

            SubtaskScorer.Result scored = job.scoringMode() == ScoringMode.SUBTASK
                    ? SubtaskScorer.score(job, executor)
                    : scoreAllOrNothing(job, executor);

            log.debug("submission {} attempt {}: {} — {}/{} điểm sau {}/{} test",
                    job.submissionId(), job.attempt(), scored.verdict(), scored.score(),
                    job.maxScore(), scored.testsRun(), job.testcases().size());

            return new JudgeResultDto(job.submissionId(), job.attempt(), scored.verdict(),
                    scored.score(), job.maxScore(), scored.failedOrdinal(), scored.testsRun(),
                    (int) scored.maxCpuTimeMs(), (int) scored.maxMemoryKb(),
                    compiled.compileLog(), null,
                    properties.hostName(), hostFactor, startedAt, scored.subtasks());
        }
    }

    private static SubtaskScorer.Result scoreAllOrNothing(JudgeJobDto job,
                                                          SubtaskScorer.TestExecutor executor) {
        Verdict verdict = Verdict.AC;
        Integer failedOrdinal = null;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        int testsRun = 0;

        for (TestcaseMetaDto testcase : job.testcases()) {
            TestRunner.TestOutcome outcome = executor.run(testcase);
            testsRun++;
            maxCpuMs = Math.max(maxCpuMs, outcome.cpuTimeMs());
            maxMemoryKb = Math.max(maxMemoryKb, outcome.memoryKb());
            if (!outcome.accepted()) {
                verdict = outcome.verdict();
                failedOrdinal = testcase.ordinal();
                break;
            }
        }

        int score = verdict == Verdict.AC ? job.maxScore() : 0;
        return new SubtaskScorer.Result(score, verdict, failedOrdinal, testsRun,
                maxCpuMs, maxMemoryKb, java.util.List.of());
    }

}
