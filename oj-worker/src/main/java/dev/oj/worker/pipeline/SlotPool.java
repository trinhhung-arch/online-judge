package dev.oj.worker.pipeline;

import dev.oj.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class SlotPool {

    private final BlockingQueue<Integer> free;
    private final int size;

    public SlotPool(WorkerProperties properties) {
        this.size = properties.slots();
        this.free = new ArrayBlockingQueue<>(size);
        int first = properties.sandbox().firstBoxId();
        for (int i = 0; i < size; i++) {
            free.add(first + i);
        }
    }

    public int size() {
        return size;
    }

    public int available() {
        return free.size();
    }

    public int acquire(long timeoutMs) throws InterruptedException {
        Integer boxId = free.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (boxId == null) {
            throw new IllegalStateException("Hết box sau " + timeoutMs + "ms trong khi chỉ có "
                    + size + " luồng chấm. Có chỗ quên release() — đây là rò rỉ slot, không "
                    + "phải quá tải");
        }
        return boxId;
    }

    public void release(int boxId) {
        free.add(boxId);
    }
}
