package dev.oj.worker.pipeline;

import dev.oj.worker.config.WorkerProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Cấp phát box id. Bước 2.6 của {@code build-order.md} ("BoxPool").
 *
 * <h2>Số box = {@code oj.worker.slots}, CỐ ĐỊNH theo cấu hình — không theo số core</h2>
 * {@code Runtime.availableProcessors()} không xuất hiện ở đây, và đó là chủ ý (ADR 008).
 * M1 Max có 10 core nhưng chạy 6 slot: chạy full core 10-15 phút thì máy throttle nhiệt, và
 * bài ở phút thứ 90 của contest chấm chậm hơn bài ở phút thứ 5. Đó là <b>mất công bằng</b>,
 * nó xảy ra âm thầm, và không ai trong phòng thi nhận ra.
 *
 * <h2>Vì sao box id phải được cấp phát chứ không lấy theo tên luồng</h2>
 * Hai worker chạy trên cùng một máy (chuyện thường lúc thử nghiệm) mà cùng dùng box 0..5 thì
 * chúng ghi đè box của nhau: một bên {@code --cleanup} đúng lúc bên kia đang chạy, và triệu
 * chứng là những lượt {@code IE} ngẫu nhiên không tái hiện được. {@code first-box-id} tách
 * hai dải ra; pool này bảo đảm trong một tiến trình không có hai luồng cùng một box.
 */
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

    /**
     * Chờ tới khi có box rỗi.
     *
     * <p>Không có biến thể {@code tryAcquire} trả về {@code empty}: {@code JudgeLoop} chạy
     * đúng {@code slots} luồng và mỗi luồng giữ tối đa một box, nên hàng đợi này về lý thuyết
     * không bao giờ cạn. Nếu nó cạn thì có một chỗ nào đó quên {@link #release(int)} — và lúc
     * ấy <b>treo ở đây rồi timeout</b> là cách phát hiện tốt hơn nhiều so với âm thầm bỏ qua
     * một bài nộp.
     */
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
