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

@Component
public class JudgeLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JudgeLoop.class);

    private final JudgeApiClient api;
    private final JudgeRunner runner;
    private final ResultBuffer results;
    private final JudgeDoorbell chuong;
    private final WorkerProperties properties;

    private final ClaimBackoff backoff;

    private ExecutorService slots;
    private volatile boolean running;

    public JudgeLoop(JudgeApiClient api, JudgeRunner runner, ResultBuffer results,
                     JudgeDoorbell chuong, WorkerProperties properties) {
        this.api = api;
        this.runner = runner;
        this.results = results;
        this.chuong = chuong;
        this.properties = properties;
        this.backoff = new ClaimBackoff(properties.idlePoll());
    }

    @Override
    public void start() {
        running = true;
        slots = Executors.newFixedThreadPool(properties.slots(), r -> {
            Thread t = new Thread(r);
            t.setName("oj-judge-slot-" + t.threadId());
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

    private void pullLoop() {
        ClaimRequestDto request =
                ClaimRequestDto.single(properties.hostName(), properties.arch());
        int lienTiepHong = 0;
        while (running) {
            try {
                Optional<JudgeJobDto> job = api.claim(request);
                backoff.ghiNhanCoLai();
                lienTiepHong = 0;
                if (job.isEmpty()) {
                    if (!chuong.cho(properties.idlePoll())) {
                        return;
                    }
                    continue;
                }
                judge(job.get());
            } catch (JudgeApiException e) {
                Duration cho = backoff.cho(++lienTiepHong);
                backoff.ghiNhanMat(e.getMessage(), cho);
                sleep(cho);
            } catch (Exception e) {
                log.error("Lỗi ngoài dự kiến trong vòng lặp chấm — slot vẫn tiếp tục", e);
                sleep(properties.idlePoll());
            }
        }
    }

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
