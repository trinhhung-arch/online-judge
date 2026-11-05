package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.worker.calibration.HostBenchmark;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.pipeline.JobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "oj.worker.sandbox", name = "enabled", havingValue = "true")
public class IsolateJudgeRunner implements JudgeRunner {

    private static final Logger log = LoggerFactory.getLogger(IsolateJudgeRunner.class);

    private final JobExecutor executor;
    private final WorkerProperties properties;
    private final HostBenchmark hostBenchmark;

    public IsolateJudgeRunner(JobExecutor executor, WorkerProperties properties,
                              HostBenchmark hostBenchmark) {
        this.executor = executor;
        this.properties = properties;
        this.hostBenchmark = hostBenchmark;
        log.info("Chấm bằng isolate: {} (box {}..{}), {} slot",
                properties.sandbox().isolateBinary(),
                properties.sandbox().firstBoxId(),
                properties.sandbox().firstBoxId() + properties.slots() - 1,
                properties.slots());
    }

    @Override
    public JudgeResultDto run(JudgeJobDto job) {
        Instant startedAt = Instant.now();
        try {
            return executor.execute(job, job.sourceFileName(), startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return internalError(job, startedAt, "bị ngắt khi đang chấm", e);
        } catch (Throwable e) {
            return internalError(job, startedAt, e.getClass().getSimpleName(), e);
        }
    }

    private JudgeResultDto internalError(JudgeJobDto job, Instant startedAt, String status,
                                         Throwable cause) {
        log.error("IE cho submission {} attempt {} (trace {}): {}",
                job.submissionId(), job.attempt(), job.traceId(), cause.toString(), cause);
        return JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                properties.hostName(), hostBenchmark.current(), startedAt, status);
    }

}
