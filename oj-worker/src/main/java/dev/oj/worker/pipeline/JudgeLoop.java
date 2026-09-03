package dev.oj.worker.pipeline;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.client.JudgeApiClient.JudgeApiException;
import dev.oj.worker.client.JudgeDoorbell;
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
 * <h2>Hàng đợi rỗng thì chờ, không quay vòng</h2>
 * {@code 204} nghĩa là hết việc. Hỏi lại ngay lập tức là sáu luồng quay tít đốt CPU và dội
 * request vào API — đúng lúc rảnh việc thì lại tốn nhiều tài nguyên nhất.
 *
 * <p><b>M6 · Bước 6.4:</b> nhịp ngủ {@code oj.worker.idle-poll} trở thành một lần
 * {@link JudgeDoorbell#cho} — dậy ngay khi API gõ cửa qua RabbitMQ, và <i>vẫn</i> dậy sau
 * {@code idlePoll} nếu không có tiếng nào. Vế thứ hai là thứ giữ cho bước 6.4 an toàn: broker
 * chết thì worker quay về đúng hành vi M1. Xem javadoc {@code JudgeDoorbell}.
 *
 * <h2>Tắt máy êm — Bước 6.8, A3</h2>
 * {@link #stop()} <b>không</b> gọi {@code shutdownNow()} nữa. Xem javadoc của nó.
 */
@Component
public class JudgeLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JudgeLoop.class);

    private final JudgeApiClient api;
    private final JudgeRunner runner;
    private final ResultBuffer results;
    private final JudgeDoorbell chuong;
    private final WorkerProperties properties;

    private ExecutorService slots;
    private volatile boolean running;

    public JudgeLoop(JudgeApiClient api, JudgeRunner runner, ResultBuffer results,
                     JudgeDoorbell chuong, WorkerProperties properties) {
        this.api = api;
        this.runner = runner;
        this.results = results;
        this.chuong = chuong;
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
                    // Hàng đợi rỗng: chờ tiếng chuông, tối đa idlePoll. Bước 6.4.
                    if (!chuong.cho(properties.idlePoll())) {
                        return;      // bị ngắt -> đang tắt máy
                    }
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

    /**
     * ★ Bước 6.8 — SIGTERM: ngừng nhận job mới, <b>chấm nốt bài đang chạy</b>, rồi mới thoát.
     *
     * <h2>Bản M1 gọi {@code shutdownNow()}, và đó là một lỗi thật</h2>
     * {@code shutdownNow()} <b>ngắt</b> mọi luồng đang chạy — kể cả sáu slot đang chấm dở.
     * Mỗi lần deploy worker là sáu bài bị cắt ngang giữa chừng: chúng không mất (lease 120s
     * hết hạn thì reaper giao lại), nhưng người nộp chờ thêm hai phút cho một verdict mà máy
     * đã tính gần xong. Nhân với một lần deploy giữa contest thì đó là A3 bị phá:
     * <i>"deploy worker: 0 bài mất"</i> đúng theo nghĩa đen, sai theo nghĩa người dùng thấy.
     *
     * <p>{@code shutdown()} thì ngược lại: không nhận việc mới, để luồng đang chạy làm nốt.
     *
     * <h2>Ba việc, đúng thứ tự — {@code oj-worker/CLAUDE.md} mục 5</h2>
     * <ol>
     *   <li>{@code running = false} — slot nào xong bài hiện tại thì thoát, không claim nữa.</li>
     *   <li>{@link JudgeDoorbell#reo()} — <b>đánh thức slot đang chờ chuông</b>. Không có dòng
     *       này thì một worker rảnh việc phải nằm hết {@code idlePoll} rồi mới thấy cờ đã đổi,
     *       và mỗi lần tắt máy đội thêm chừng ấy thời gian mà không vì lý do gì.</li>
     *   <li>Chờ tới {@code shutdown-grace}. Hết hạn mà còn slot chạy thì mới cắt — một bài
     *       chấm lâu bất thường không được giữ tiến trình lại vô hạn.</li>
     * </ol>
     *
     * <p>Việc gửi nốt kết quả và dọn box nằm ở {@code GracefulShutdown}, chạy <b>sau</b> lớp
     * này (phase thấp hơn).
     */
    @Override
    public void stop() {
        running = false;
        chuong.reo();
        if (slots == null) {
            return;
        }
        Duration anHan = properties.shutdownGrace();
        slots.shutdown();
        try {
            if (slots.awaitTermination(anHan.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("Mọi slot đã chấm xong bài của mình và dừng.");
                return;
            }
            log.warn("Còn slot đang chấm sau {} — cắt. Những bài đó sẽ được reaper giao lại "
                    + "sau khi lease {} hết hạn.", anHan, properties.lease());
            slots.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            slots.shutdownNow();
        }
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
