package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
@ConditionalOnProperty(prefix = "oj.worker.sandbox", name = "enabled", havingValue = "false")
public class ScriptedJudgeRunner implements JudgeRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptedJudgeRunner.class);

    private static final String MARKER = "EXPECT:";

    private static final List<String> DIRECTIVES =
            List.of("AC", "WA", "TLE", "CE", "CRASH");

    private final WorkerProperties properties;

    public ScriptedJudgeRunner(WorkerProperties properties) {
        this.properties = properties;
        log.warn("""

                ┌────────────────────────────────────────────────────────────────┐
                │  CHẤM BÀI ĐANG GIẢ LẬP — ScriptedJudgeRunner (chỉ dành cho M1) │
                │  Không mã người dùng nào được thực thi. Bài không mang chỉ thị │
                │  '// EXPECT: ...' sẽ nhận verdict IE.                          │
                │  Thay bằng IsolateJudgeRunner khi 14/14 test tấn công xanh.    │
                └────────────────────────────────────────────────────────────────┘
                """);
    }

    @Override
    public JudgeResultDto run(JudgeJobDto job) {
        Instant startedAt = Instant.now();
        String directive = directiveOf(job.sourceContent());
        log.info("submission {} attempt {} — kịch bản '{}'",
                job.submissionId(), job.attempt(), directive);

        return switch (directive) {
            case "AC" -> done(job, Verdict.AC, job.maxScore(), null, startedAt);
            case "WA" -> done(job, Verdict.WA, 0, 1, startedAt);
            case "TLE" -> timeLimitExceeded(job, startedAt);
            case "CE" -> JudgeResultDto.compileError(job.submissionId(), job.attempt(),
                    job.maxScore(), properties.hostName(), properties.hostFactor(),
                    startedAt, "error: kịch bản CE của ScriptedJudgeRunner, không phải "
                            + "log compiler thật");
            case "CRASH" -> throw new IllegalStateException(
                    "kịch bản CRASH — worker chết giữa chừng để test reaper (submission "
                            + job.submissionId() + ")");
            default -> JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                    properties.hostName(), properties.hostFactor(), startedAt,
                    "ScriptedJudgeRunner: bài nộp không mang chỉ thị '// " + MARKER + " ...'. "
                            + "M1 không thực thi mã người dùng (bất biến #4).");
        };
    }

    private JudgeResultDto timeLimitExceeded(JudgeJobDto job, Instant startedAt) {
        long limitMs = job.timeLimitMs();
        try {
            Thread.sleep(limitMs / 10 + 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                    properties.hostName(), properties.hostFactor(), startedAt,
                    "bị ngắt giữa kịch bản TLE");
        }
        return new JudgeResultDto(job.submissionId(), job.attempt(), Verdict.TLE,
                0, job.maxScore(), 1, 1,
                (int) limitMs + 30, 8192, null, null,
                properties.hostName(), properties.hostFactor(), startedAt, List.of());
    }

    private JudgeResultDto done(JudgeJobDto job, Verdict verdict, int score,
                                Integer failedTestOrdinal, Instant startedAt) {
        return new JudgeResultDto(job.submissionId(), job.attempt(), verdict,
                score, job.maxScore(), failedTestOrdinal,
                job.testcases().size(), 23, 8192, null, null,
                properties.hostName(), properties.hostFactor(), startedAt, List.of());
    }

    static String directiveOf(String source) {
        if (source == null || source.isBlank()) {
            return "NONE";
        }
        int end = source.indexOf('\n');
        String firstLine = (end < 0 ? source : source.substring(0, end))
                .toUpperCase(Locale.ROOT);
        int at = firstLine.indexOf(MARKER);
        if (at < 0) {
            return "NONE";
        }
        String tail = firstLine.substring(at + MARKER.length()).trim();
        return DIRECTIVES.stream().filter(tail::startsWith).findFirst().orElse("NONE");
    }
}
