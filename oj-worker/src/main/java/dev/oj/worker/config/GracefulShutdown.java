package dev.oj.worker.config;

import dev.oj.worker.client.ResultBuffer;
import dev.oj.worker.sandbox.IsolateBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * ★ Bước 6.8 — hai việc cuối cùng của SIGTERM, sau khi mọi slot đã dừng.
 *
 * <h2>Vì sao là một lớp riêng chứ không nằm trong {@code JudgeLoop.stop()}</h2>
 * Vì thứ tự. {@code oj-worker/CLAUDE.md} mục 5 viết ra bốn bước, và ba bước sau chỉ đúng
 * <b>sau khi</b> bước một xong:
 *
 * <pre>
 *   1. ngừng nhận job mới, chấm nốt bài đang chạy   -> JudgeLoop.stop()
 *   2. gửi nốt kết quả đã tính được                 -> ở đây
 *   3. dọn mọi box                                  -> ở đây
 *   4. thoát
 * </pre>
 *
 * <p>Gửi kết quả <i>trong lúc</i> slot còn đang chấm là gửi một danh sách chưa đầy đủ; dọn box
 * lúc đó là dọn box của một bài đang chạy. Spring dừng {@link SmartLifecycle} theo phase
 * <b>giảm dần</b>, nên {@link #getPhase()} thấp hơn của {@code JudgeLoop} (mặc định
 * {@code Integer.MAX_VALUE}) là cách diễn đạt "sau" mà framework hiểu được — thay vì một
 * lời gọi trực tiếp buộc hai lớp biết nhau.
 *
 * <h2>Bước 2 — "nack phần còn lại về queue" ở hệ thống này nghĩa là gì</h2>
 * Tài liệu viết <i>nack phần chưa bắt đầu về lại queue</i>. Ở đây không có "phần chưa bắt đầu"
 * nằm trong bộ nhớ worker để mà nack: worker không giữ hàng đợi, nó claim từng bài một và
 * {@code judge_queue} là nơi mọi thứ chưa bắt đầu vẫn đang nằm. Việc tương đương — và là việc
 * thật sự cứu được thời gian — là <b>đẩy nốt những verdict đã tính xong nhưng chưa gửi</b>.
 *
 * <p>Không làm bước này thì những bài ấy không mất (khoá lạc quan và reaper lo phần đó), nhưng
 * chúng bị chấm lại từ đầu sau 120 giây — phí đúng phần việc tốn kém nhất, và người nộp chờ
 * thêm hai phút.
 *
 * <p>Với tiếng chuông RabbitMQ chưa ack thì broker tự trả lại khi kết nối đóng; không có gì
 * phải làm ở đây, và đó là lý do {@code prefetch=1} đáng giá — một worker chết trả lại một
 * tiếng chuông, không phải 250.
 */
@Component
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    /** Nhịp hỏi lại {@code pendingCount()}. Đủ nhỏ để không kéo dài việc tắt máy một cách vô ích. */
    private static final Duration NHIP_HOI = Duration.ofMillis(100);

    private final ResultBuffer results;
    private final WorkerProperties properties;

    private volatile boolean running;

    public GracefulShutdown(ResultBuffer results, WorkerProperties properties) {
        this.results = results;
        this.properties = properties;
    }

    @Override
    public int getPhase() {
        // Thấp hơn JudgeLoop (DEFAULT_PHASE = Integer.MAX_VALUE) -> dừng SAU nó. Xem javadoc lớp.
        return Integer.MAX_VALUE - 1000;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        running = false;
        guiNotKetQua();
        donMoiBox();
        log.info("Worker '{}' tắt êm xong.", properties.hostName());
    }

    /** Bước 2. Chờ {@code ResultBuffer} đẩy hết, tối đa {@code shutdown-grace}. */
    private void guiNotKetQua() {
        int conLai = results.pendingCount();
        if (conLai == 0) {
            return;
        }
        log.info("Còn {} kết quả chưa gửi — chờ tối đa {} để đẩy nốt.",
                conLai, properties.shutdownGrace());
        Instant hetHan = Instant.now().plus(properties.shutdownGrace());
        while (results.pendingCount() > 0 && Instant.now().isBefore(hetHan)) {
            try {
                Thread.sleep(NHIP_HOI.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        conLai = results.pendingCount();
        if (conLai > 0) {
            // ResultBuffer.close() sẽ ghi ERROR chi tiết. Ở đây chỉ nói vì sao ta bỏ cuộc.
            log.warn("Vẫn còn {} kết quả chưa gửi sau {} — API có thể đang xuống. Những bài đó "
                    + "sẽ được chấm lại sau khi lease hết hạn.", conLai, properties.shutdownGrace());
        }
    }

    /**
     * Bước 3. Dọn <b>toàn bộ dải box của worker này</b>, không chỉ những box đang dùng.
     *
     * <p>Dọn theo dải chứ không theo danh sách đang mở là cố ý: một lần crash trước đó có thể
     * đã để lại box mồ côi, và lúc tắt máy là lúc duy nhất chắc chắn không có ai đang dùng
     * chúng. {@code first-box-id} tách dải của worker này khỏi worker khác trên cùng máy, nên
     * việc dọn cả dải không đụng vào box của ai.
     */
    private void donMoiBox() {
        WorkerProperties.Sandbox sandbox = properties.sandbox();
        if (!sandbox.enabled()) {
            return;     // ScriptedJudgeRunner không mở box nào
        }
        int dau = sandbox.firstBoxId();
        for (int boxId = dau; boxId < dau + properties.slots(); boxId++) {
            IsolateBox.donDep(sandbox, boxId);
        }
        log.info("Đã dọn dải box {}..{}", dau, dau + properties.slots() - 1);
    }
}
