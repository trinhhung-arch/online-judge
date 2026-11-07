package dev.oj.platform.web;

import dev.oj.platform.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class SseHeartbeat {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "oj-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final long periodMs;

    public SseHeartbeat(AppProperties properties) {
        this.periodMs = properties.sse().heartbeat().toMillis();
    }

    public AutoCloseable start(SseEmitter emitter) {
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> SseEmitters.ping(emitter), periodMs, periodMs, TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
