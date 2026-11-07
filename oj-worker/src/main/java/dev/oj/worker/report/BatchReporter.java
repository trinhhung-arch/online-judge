package dev.oj.worker.report;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeProgressDto.TestOutcome;
import dev.oj.contract.Verdict;
import dev.oj.worker.client.JudgeApiClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BatchReporter {

    private final JudgeApiClient api;

    public BatchReporter(JudgeApiClient api) {
        this.api = api;
    }

    public Session open(JudgeJobDto job) {
        return new Session(job);
    }

    public final class Session implements AutoCloseable {

        private final JudgeJobDto job;
        private final List<TestOutcome> pending = new ArrayList<>(JudgeProgressDto.BATCH_SIZE);
        private int firstOrdinal;

        private Session(JudgeJobDto job) {
            this.job = job;
        }

        public void add(int ordinal, Verdict verdict, long cpuTimeMs, long memoryKb) {
            if (pending.isEmpty()) {
                firstOrdinal = ordinal;
            }
            pending.add(new TestOutcome(ordinal, verdict, (int) cpuTimeMs, (int) memoryKb));
            if (pending.size() >= JudgeProgressDto.BATCH_SIZE) {
                flush();
            }
        }

        @Override
        public void close() {
            flush();
        }

        private void flush() {
            if (pending.isEmpty()) {
                return;
            }
            int lastOrdinal = pending.get(pending.size() - 1).ordinal();
            api.reportProgress(new JudgeProgressDto(
                    job.submissionId(), job.attempt(),
                    firstOrdinal, lastOrdinal, job.testcases().size(),
                    List.copyOf(pending)));
            pending.clear();
        }
    }
}
