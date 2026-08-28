package dev.oj.worker.pipeline;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.client.JudgeApiClient.JudgeApiException;
import dev.oj.worker.client.ResultBuffer;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.run.JudgeRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Vòng lặp chấm bài: {@code claim → run → report}, chạy trên {@code slots} luồng song song.
 *
 * <h2>PULL, không PUSH</h2>
 * Worker tự đi xin việc. Server không giữ danh sách worker, không heartbeat, không service
 * discovery — bật thêm một worker là nó tự vào việc, <b>không sửa một dòng config nào phía
 * API</b> (S2, ADR 004). Đó cũng là thứ làm bài test scalability tuần 12 (Mac + hai WSL)
 * không tốn đồng nào.
 *
 * <h2>Số luồng = {@code slots}, cố định theo cấu hình</h2>
 * <b>Không</b> theo số core. M1 Max có 10 core nhưng chạy 6 slot: chạy full core 10-15 phút
 * sẽ throttle, và bài phút thứ 90 chấm chậm hơn bài phút thứ 5 — mất công bằng ngay giữa
 * contest, mà không ai nhận ra (ADR 008).
 *
 * <h2>Hàng đợi rỗng thì ngủ, không quay vòng</h2>
 * {@code 204} nghĩa là hết việc. Hỏi lại ngay lập tức là sáu luồng quay tít đốt CPU và dội
 * request vào API — đúng lúc rảnh việc thì lại tốn nhiều tài nguyên nhất. Nhịp
 * {@code oj.worker.idle-poll} là độ trễ giao việc phải trả ở M1; M6 đổi sang RabbitMQ push
 * và độ trễ đó biến mất.
 */
@Component
public class JudgeLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JudgeLoop.class);

    private final JudgeApiClient api;
    private final JudgeRunner runner;
    private final ResultBuffer results;
    private final WorkerProperties properties;

    private ExecutorService slots;
    private volatile boolean running;

    public JudgeLoop(JudgeApiClient api, JudgeRunner runner, ResultBuffer results,
                     WorkerProperties properties) {
        this.api = api;
        this.runner = runner;
        this.results = results;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
        slots = Executors.newFixedThreadPool(properties.slots(), r -> {
            Thread t = new Thread(r);
            t.setName("oj-judge-slot-" + t.threadId());
            // Luồng thường, KHÔNG virtual thread: đây là công việc bị CPU chặn trong sandbox,
            // và virtual thread không giúp gì cho loại đó. Virtual thread là chuyện của SSE
            // phía API (1000 kết nối chờ I/O), không phải chuyện ở đây.
            t.setDaemon(false);
            return t;
        });
        for (int i = 0; i < properties.slots(); i++) {
            slots.submit(this::pullLoop);
        }
        log.info("JudgeLoop khởi động: {} slot, máy '{}' ({}), API {}",
                properties.slots(), properties.hostName(), properties.arch(),
                properties.apiBaseUrl());
    }

    /** Một slot: xin việc, chấm, xếp kết quả vào buffer, lặp lại. */
    private void pullLoop() {
        ClaimRequestDto request =
                ClaimRequestDto.single(properties.hostName(), properties.arch());
        while (running) {
            try {
                Optional<JudgeJobDto> job = api.claim(request);
                if (job.isEmpty()) {
                    sleep(properties.idlePoll());     // hàng đợi rỗng
                    continue;
                }
                judge(job.get());
            } catch (JudgeApiException e) {
                // API đang xuống. Ngủ rồi thử lại — đừng quay vòng dội request vào một hệ
                // thống đang có sự cố.
                log.warn("Không xin được việc ({}) — thử lại sau {}",
                        e.getMessage(), properties.idlePoll());
                sleep(properties.idlePoll());
            } catch (Exception e) {
                log.error("Lỗi ngoài dự kiến trong vòng lặp chấm — slot vẫn tiếp tục", e);
                sleep(properties.idlePoll());
            }
        }
    }

    /**
     * Chấm đúng một bài.
     *
     * <p>{@link JudgeRunner} cam kết không ném ra ngoài, nhưng ở đây vẫn bắt: một hiện thực
     * tương lai vi phạm cam kết đó sẽ làm <b>mất một lượt chấm mà API không được báo gì</b>,
     * và bài phải chờ hết lease 120s. Đổi nó thành {@code IE} thì API cho chấm lại ngay
     * (FR-SUB-12).
     */
    private void judge(JudgeJobDto job) {
        Instant startedAt = Instant.now();
        JudgeResultDto result;
        try {
            result = runner.run(job);
        } catch (Exception e) {
            log.error("JudgeRunner ném ra ngoài cho submission {} attempt {} — trả IE",
                    job.submissionId(), job.attempt(), e);
            result = JudgeResultDto.internalError(job.submissionId(), job.attempt(),
                    properties.hostName(), properties.hostFactor(), startedAt,
                    e.getClass().getSimpleName());
        }
        warnIfOverLease(job, startedAt);
        results.submit(result);
    }

    /**
     * Chấm lâu hơn lease nghĩa là reaper đã thu hồi bài và giao cho người khác — kết quả sắp
     * gửi đi <b>chắc chắn sẽ bị khoá lạc quan từ chối</b>. Không phải lỗi, nhưng là tín hiệu
     * vận hành: hoặc lease quá ngắn, hoặc máy này đang quá tải.
     */
    private void warnIfOverLease(JudgeJobDto job, Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, Instant.now());
        if (elapsed.compareTo(properties.lease()) > 0) {
            log.warn("submission {} attempt {} chấm mất {} — vượt lease {}. Kết quả sẽ bị từ "
                            + "chối và bài đã được giao lại cho worker khác.",
                    job.submissionId(), job.attempt(), elapsed, properties.lease());
        }
    }

    @Override
    public void stop() {
        running = false;
        if (slots != null) {
            slots.shutdownNow();
            try {
                if (!slots.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Còn slot chưa dừng sau 10 giây");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("JudgeLoop dừng. Tắt máy êm đầy đủ — chấm nốt bài đang chạy rồi mới thoát — "
                + "là Bước 6.8 (A3: deploy worker không mất bài).");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
