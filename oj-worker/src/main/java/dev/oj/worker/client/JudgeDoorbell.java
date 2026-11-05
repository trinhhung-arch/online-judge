package dev.oj.worker.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class JudgeDoorbell {

    private static final Logger log = LoggerFactory.getLogger(JudgeDoorbell.class);

    private final Object khoa = new Object();

    public void reo() {
        synchronized (khoa) {
            khoa.notifyAll();
        }
    }

    public boolean cho(Duration toiDa) {
        try {
            synchronized (khoa) {
                khoa.wait(toiDa.toMillis());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Slot bị ngắt trong lúc chờ chuông — đang tắt máy");
            return false;
        }
    }
}
