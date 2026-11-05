package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.compile.Compiler;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.run.checker.Checker;
import dev.oj.worker.sandbox.CommandTemplate;
import dev.oj.worker.sandbox.IsolateBox;
import dev.oj.worker.sandbox.IsolateCommand;
import dev.oj.worker.sandbox.IsolateMeta;
import dev.oj.worker.sandbox.SandboxException;
import dev.oj.worker.testdata.TestdataFetcher;
import dev.oj.worker.testdata.TestdataUnavailableException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
public class TestRunner {

    private final WorkerProperties properties;
    private final TestdataFetcher testdata;

    public TestRunner(WorkerProperties properties, TestdataFetcher testdata) {
        this.properties = properties;
        this.testdata = testdata;
    }

    public record TestOutcome(Verdict verdict, long cpuTimeMs, long memoryKb, String diagnostic) {

        public boolean accepted() {
            return verdict == Verdict.AC;
        }
    }

    public TestOutcome run(IsolateBox box, JudgeJobDto job, Compiler.Artifact artifact,
                           TestcaseMetaDto testcase, BigDecimal hostFactor, Checker checker) {
        Path input = testdata.fetch(testcase.inputSha256());
        byte[] expected = read(testdata.fetch(testcase.outputSha256()), testcase.outputSha256());

        box.reset();
        box.writeExecutable(artifact.name(), artifact.content());

        long cpuLimitMs = job.effectiveCpuLimitMs(hostFactor);
        long wallLimitMs = job.effectiveWallLimitMs(hostFactor);
        List<String> argv = IsolateCommand.run(
                properties.sandbox(), box.boxId(), box.metaFile(),
                cpuLimitMs, wallLimitMs, job.memoryLimitKb(),
                CommandTemplate.expand(job.runCommand(), artifact.name(), job.memoryLimitKb(),
                        properties.sandbox().programPath()));

        IsolateBox.Execution execution = box.execute(
                argv, input,
                (long) job.outputLimitKb() * 1024,
                properties.sandbox().run().stderrLimit().toBytes(),
                Duration.ofMillis(wallLimitMs).plus(properties.sandbox().watchdogSlack()));

        IsolateMeta meta = execution.meta();
        return new TestOutcome(verdictOf(meta, execution.stdout(), expected, checker),
                meta.cpuTimeMs(), meta.memoryKb(), meta.diagnostic());
    }

    private Verdict verdictOf(IsolateMeta meta, OutputLimiter.Captured stdout,
                              byte[] expected, Checker checker) {
        return switch (meta.outcome()) {
            case TIME_LIMIT -> Verdict.TLE;
            case MEMORY_LIMIT -> Verdict.MLE;
            case RUNTIME_ERROR -> Verdict.RE;
            case INTERNAL_ERROR -> throw new SandboxException(
                    "isolate trả mã lạ khi chạy: " + meta.diagnostic());
            case OK -> stdout.truncated() || !checker.matches(expected, stdout.bytes())
                    ? Verdict.WA
                    : Verdict.AC;
        };
    }

    private static byte[] read(Path file, String sha256) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TestdataUnavailableException("Không đọc được đáp án " + sha256, e);
        }
    }
}
