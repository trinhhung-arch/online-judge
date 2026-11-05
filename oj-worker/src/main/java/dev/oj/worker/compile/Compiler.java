package dev.oj.worker.compile;

import dev.oj.contract.JudgeJobDto;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.sandbox.CommandTemplate;
import dev.oj.worker.sandbox.IsolateBox;
import dev.oj.worker.sandbox.IsolateCommand;
import dev.oj.worker.sandbox.IsolateMeta;
import dev.oj.worker.sandbox.SandboxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class Compiler {

    private static final Logger log = LoggerFactory.getLogger(Compiler.class);

    private final WorkerProperties properties;
    private final CompileCache cache;

    public Compiler(WorkerProperties properties) {
        this.properties = properties;
        this.cache = new CompileCache(
                properties.sandbox().cache().dir().resolve("binaries"),
                properties.sandbox().cache().maxEntries());
    }

    public record Artifact(String name, byte[] content) {
    }

    public record CompileResult(Artifact artifact, String compileLog) {

        public boolean failed() {
            return artifact == null;
        }
    }

    public CompileResult compile(IsolateBox box, JudgeJobDto job, String sourceFileName) {
        if (job.isInterpreted()) {
            return new CompileResult(
                    new Artifact(sourceFileName,
                            job.sourceContent().getBytes(StandardCharsets.UTF_8)),
                    null);
        }

        String key = job.compileCacheKey();
        var cached = cache.find(key);
        if (cached.isPresent()) {
            log.debug("cache hit biên dịch cho submission {}", job.submissionId());
            return new CompileResult(
                    new Artifact(CommandTemplate.BINARY_NAME, cached.get()), null);
        }

        box.reset();
        box.write(sourceFileName, job.sourceContent());

        List<String> argv = IsolateCommand.compile(
                properties.sandbox(), box.boxId(), box.metaFile(),
                job.compileTimeLimitMs(), job.compileMemoryKb(),
                CommandTemplate.expand(job.compileCommand(), sourceFileName, job.memoryLimitKb(),
                        properties.sandbox().programPath()));

        long logLimit = properties.sandbox().compile().logLimit().toBytes();
        IsolateBox.Execution execution = box.execute(
                argv, null, logLimit, logLimit, watchdog(job.compileTimeLimitMs()));

        String compileLog = sanitize(execution.stderr().text() + execution.stdout().text());
        IsolateMeta.Outcome outcome = execution.meta().outcome();

        if (outcome == IsolateMeta.Outcome.INTERNAL_ERROR) {
            throw new SandboxException("isolate hỏng khi biên dịch submission "
                    + job.submissionId() + ": " + execution.meta().diagnostic());
        }
        if (outcome == IsolateMeta.Outcome.TIME_LIMIT) {
            return new CompileResult(null, compileLog + explainKill(
                    "Biên dịch vượt " + job.compileTimeLimitMs() + "ms."));
        }
        if (outcome == IsolateMeta.Outcome.MEMORY_LIMIT) {
            return new CompileResult(null, compileLog + explainKill(
                    "Trình biên dịch vượt " + job.compileMemoryKb() + "KB bộ nhớ."));
        }
        if (outcome == IsolateMeta.Outcome.RUNTIME_ERROR) {
            return new CompileResult(null, compileLog);
        }
        if (!box.hasRegularFile(CommandTemplate.BINARY_NAME)) {
            return new CompileResult(null, compileLog + explainKill(
                    "Lệnh biên dịch báo thành công nhưng không tạo ra "
                            + CommandTemplate.BINARY_NAME + "."));
        }

        byte[] binary = box.takeRegularFile(CommandTemplate.BINARY_NAME);
        cache.store(key, binary);
        return new CompileResult(new Artifact(CommandTemplate.BINARY_NAME, binary), compileLog);
    }

    private Duration watchdog(int compileTimeLimitMs) {
        return Duration.ofMillis(2L * compileTimeLimitMs)
                .plus(properties.sandbox().watchdogSlack());
    }

    private static String explainKill(String reason) {
        return "\n=== " + reason + " Thường là do #include lồng nhau quá sâu hoặc template "
                + "sinh ra quá nhiều kiểu. ===\n";
    }

    private static String sanitize(String compilerOutput) {
        return compilerOutput.replace(IsolateCommand.BOX_DIR + "/", "");
    }
}
