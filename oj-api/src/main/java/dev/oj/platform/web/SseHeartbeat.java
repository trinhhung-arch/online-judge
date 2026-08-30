package dev.oj.platform.web;

import dev.oj.platform.config.AppProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gửi nhịp giữ kết nối cho các luồng SSE. Bước 3.9.
 *
 * <h2>Vì sao cần nhịp tim, khi "im lặng" mới là trạng thái bình thường</h2>
 * Đúng vì thế mà cần. Một bài chấm 30 giây thì suốt 30 giây đó không có gì để nói — và mọi
 * proxy, mọi load balancer, Cloudflare Tunnel trước hết, đều đóng một kết nối im lặng quá
 * lâu. Không có nhịp tim thì tính năng realtime hỏng <b>đúng ở những bài chấm lâu</b>, tức
 * là đúng những bài người dùng thật sự cần theo dõi.
 *
 * <p>Nhịp là một dòng comment SSE ({@code ": ping"}) — trình duyệt bỏ qua, proxy thấy có lưu
 * lượng. Không tốn một sự kiện nào của giao thức.
 *
 * <h2>★ Bộ lập lịch RIÊNG, không dùng chung với {@code @Scheduled}</h2>
 * Pool của {@code spring.task.scheduling} mặc định có <b>một</b> luồng, và
 * {@code StaleJobReaper} đang chạy trên đó. Nhét 1000 nhịp tim vào cùng pool là đẩy reaper ra
 * sau hàng đợi — và reaper trễ nghĩa là bài kẹt ở {@code JUDGING} lâu hơn 120 giây.
 * <b>Một tiện nghi hiển thị không bao giờ được xếp hàng trước một bảo đảm không mất bài</b>
 * (R1).
 *
 * <p>Một luồng là đủ: mỗi nhịp là một lần ghi vài byte. Việc đẩy sự kiện thật thì nằm trên
 * luồng ảo của {@code RedisMessageListenerContainer}, không phải ở đây.
 */
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

    /**
     * @return lệnh dừng nhịp. <b>Phải gọi</b> khi kết nối đóng — một nhịp tim còn sống cho
     *         một emitter đã chết sẽ ném {@code IOException} mỗi 15 giây, mãi mãi
     */
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
