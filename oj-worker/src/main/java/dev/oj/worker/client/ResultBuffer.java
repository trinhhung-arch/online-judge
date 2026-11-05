package dev.oj.worker.client;

import dev.oj.contract.JudgeResultDto;
import dev.oj.worker.client.JudgeApiClient.JudgeApiException;
import dev.oj.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class ResultBuffer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResultBuffer.class);

    private static final int CAPACITY = 256;

    private final JudgeApiClient api;
    private final Duration retryMin;
    private final Duration retryMax;
    private final BlockingQueue<JudgeResultDto> pending = new LinkedBlockingQueue<>(CAPACITY);
    private final Thread flusher;

    private volatile boolean running = true;

    public ResultBuffer(JudgeApiClient api, WorkerProperties properties) {
        this.api = api;
        this.retryMin = properties.resultRetryMin();
        this.retryMax = properties.resultRetryMax();
        this.flusher = new Thread(this::flushLoop, "oj-result-flusher");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    public void submit(JudgeResultDto result) {
        try {
            pending.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Bị ngắt khi xếp kết quả submission {} attempt {} — bài sẽ được chấm lại "
                    + "sau khi lease hết hạn", result.submissionId(), result.attempt());
        }
    }

    private void flushLoop() {
        Duration backoff = retryMin;
        while (running || !pending.isEmpty()) {
            JudgeResultDto result;
            try {
                result = pending.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = send(result, backoff);
        }
    }

    private Duration send(JudgeResultDto result, Duration backoff) {
        try {
            api.reportResult(result);
            return retryMin;
        } catch (JudgeApiException e) {
            if (!e.retryable()) {
                log.error("API từ chối vĩnh viễn kết quả của submission {} attempt {} ({}). "
                                + "Bỏ qua bản ghi này; reaper sẽ đưa bài về hàng đợi.",
                        result.submissionId(), result.attempt(), e.getMessage());
                return retryMin;
            }
            requeue(result);
            log.warn("Chưa gửi được kết quả submission {} attempt {} ({}) — giữ lại, thử lại "
                    + "sau {}", result.submissionId(), result.attempt(), e.getMessage(), backoff);
            sleep(backoff);
            return backoff.multipliedBy(2).compareTo(retryMax) > 0 ? retryMax : backoff.multipliedBy(2);
        }
    }

    private void requeue(JudgeResultDto result) {
        try {
            pending.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Bị ngắt khi giữ lại kết quả submission {} attempt {}",
                    result.submissionId(), result.attempt());
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        running = false;
        if (pendingCount() > 0) {
            log.error("Tắt worker khi còn {} kết quả CHƯA GỬI ĐƯỢC. Những bài đó sẽ được chấm "
                    + "lại sau khi lease hết hạn. Graceful shutdown đầy đủ là Bước 6.8.",
                    pendingCount());
        }
        flusher.interrupt();
    }
}
