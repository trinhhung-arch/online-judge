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

/**
 * ★ <b>Không bao giờ vứt một kết quả đã chấm xong.</b>
 *
 * <p>API không phản hồi thì giữ lại và thử lại với backoff ({@code oj-worker/CLAUDE.md} mục 6).
 * Vứt đi nghĩa là bài đó phải chờ hết lease 120s, reaper nhặt lại, rồi một worker chấm lại từ
 * đầu — <b>phí một lượt chấm thật</b>, đúng lúc hệ thống đang có sự cố nên năng lực chấm là
 * thứ khan hiếm nhất.
 *
 * <h2>Một luồng gửi, không phải mỗi slot tự gửi</h2>
 * Thứ tự gửi không quan trọng (mỗi kết quả độc lập, khoá lạc quan lo phần trùng lặp), nhưng
 * <b>một luồng thì "chưa gửi được cái nào" là một trạng thái đọc được</b>. Sáu slot cùng retry
 * độc lập là sáu lịch backoff chồng lên nhau, và lúc API sống lại chúng sẽ dội cùng lúc.
 *
 * <h2>Vì sao hàng đợi có trần, và vì sao trần đó không bao giờ chạm tới</h2>
 * Khi API xuống thì {@code claim} cũng hỏng, nên không có việc mới vào. Số kết quả tồn đọng
 * tối đa bằng số slot đang chấm dở. Trần dưới đây rộng gấp nhiều lần con số đó; nếu nó đầy
 * thật thì {@link #submit} <b>chặn</b> luồng chấm lại — đó là backpressure đúng, không phải
 * lỗi: dừng chấm còn hơn chấm rồi vứt.
 *
 * <p><b>Giới hạn đã biết:</b> buffer nằm trong bộ nhớ. Worker bị kill -9 thì các kết quả chưa
 * gửi mất theo, và bài quay lại hàng đợi qua reaper. Đó là đánh đổi có ý thức — ghi buffer
 * xuống đĩa nghĩa là worker có trạng thái bền, mà worker có trạng thái là worker không thay
 * thế được bằng một cái khác (S1, S2).
 */
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

    /**
     * Xếp một kết quả vào hàng chờ gửi. <b>Chặn</b> nếu hàng đầy — xem javadoc lớp.
     */
    public void submit(JudgeResultDto result) {
        try {
            pending.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Mất kết quả này là điều duy nhất ta không muốn, nên ghi ERROR đủ để dựng lại
            // bằng tay nếu cần. Không log compileLog — nó là output từ mã người dùng.
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

    /**
     * @return backoff cho lần sau: về {@code retryMin} khi gửi được, gấp đôi khi hỏng
     */
    private Duration send(JudgeResultDto result, Duration backoff) {
        try {
            api.reportResult(result);
            return retryMin;
        } catch (JudgeApiException e) {
            if (!e.retryable()) {
                // 4xx: payload này sẽ KHÔNG BAO GIỜ hợp lệ. Thử lại là kẹt vĩnh viễn cả hàng
                // đợi vì một bản ghi hỏng. Bỏ đúng cái này, ghi ERROR, và để reaper cho bài
                // được chấm lại — lần sau có thể ra một payload khác.
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

    /** Trả về đầu hàng thì mất thứ tự, nhưng thứ tự không quan trọng — không mất mới quan trọng. */
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

    /** Số kết quả chưa gửi được — dùng cho log tắt máy và cho metric ở M6. */
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
