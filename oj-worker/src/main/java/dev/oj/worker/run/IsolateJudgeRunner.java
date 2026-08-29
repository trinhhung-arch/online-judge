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

/**
 * ★ Hiện thực M2 của {@link JudgeRunner}: {@code isolate} + cgroup v2, thật.
 *
 * <p>⛔ <b>Cổng chuyển đã mở:</b> class này chỉ được thay {@code ScriptedJudgeRunner} khi
 * 14/14 test tấn công xanh — xem {@code SandboxAttackIT}. Từ nay mọi PR chạm vào
 * {@code worker.sandbox} phải chạy lại <b>toàn bộ</b> 14 ca, kể cả PR "chỉ là refactor".
 *
 * <h2>Class này mỏng, và mọi thứ nó làm là một lời hứa của interface</h2>
 * {@link JudgeRunner#run} cam kết <b>không bao giờ ném ra ngoài</b>. Đây là chỗ duy nhất giữ
 * lời hứa đó, nên nó bắt {@code Throwable} chứ không bắt {@code Exception}: một
 * {@code StackOverflowError} hay {@code OutOfMemoryError} lọt ra ngoài sẽ giết luồng chấm và
 * làm bài nộp treo cho tới khi hết lease 120 giây — trong khi {@code IE} cho API chấm lại
 * <b>ngay</b>, tối đa 2 lần (FR-SUB-12).
 *
 * <h2>Không có nhánh nào đoán verdict</h2>
 * Mọi lối thoát bất thường đều thành {@code IE}. "Không chắc chắn kết quả là gì thì đó là
 * {@code IE}" ({@code oj-worker/CLAUDE.md} mục 6) — đoán sai một verdict giữa contest thì
 * không ai phát hiện ra, và đó mới là điều tệ.
 */
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
        // Log KHÔNG chứa source, output, hay nội dung testcase (bất biến #9). job.toString()
        // của hợp đồng đã được ghi đè để bảo đảm điều đó ngay cả khi ai đó log nguyên job.
        log.error("IE cho submission {} attempt {} (trace {}): {}",
                job.submissionId(), job.attempt(), job.traceId(), cause.toString(), cause);
        return JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                properties.hostName(), hostBenchmark.current(), startedAt, status);
    }

}
