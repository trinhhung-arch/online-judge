package dev.oj.worker.config;

import dev.oj.worker.client.ResultBuffer;
import dev.oj.worker.sandbox.IsolateBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

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
            log.warn("Vẫn còn {} kết quả chưa gửi sau {} — API có thể đang xuống. Những bài đó "
                    + "sẽ được chấm lại sau khi lease hết hạn.", conLai, properties.shutdownGrace());
        }
    }

    private void donMoiBox() {
        WorkerProperties.Sandbox sandbox = properties.sandbox();
        if (!sandbox.enabled()) {
            return;
        }
        int dau = sandbox.firstBoxId();
        for (int boxId = dau; boxId < dau + properties.slots(); boxId++) {
            IsolateBox.donDep(sandbox, boxId);
        }
        log.info("Đã dọn dải box {}..{}", dau, dau + properties.slots() - 1);
    }
}
