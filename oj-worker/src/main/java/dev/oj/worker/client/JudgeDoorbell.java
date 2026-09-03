package dev.oj.worker.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ★ Chuông cửa — Bước 6.4. Thứ duy nhất RabbitMQ thay thế trong worker.
 *
 * <h2>Nó thay đúng một dòng: {@code Thread.sleep(idlePoll)}</h2>
 * Trước bước này, một slot rảnh việc ngủ 500ms rồi hỏi lại. Ngân sách
 * {@code enqueue → claim} là 100ms ({@code nfrplan.md} 2.1), nên nhịp ngủ ấy một mình đã
 * vượt ngân sách — và nó vượt bằng thời gian máy chấm ngồi không.
 *
 * <p>Sau bước này, slot rảnh <b>chờ có điều kiện</b>: dậy ngay khi có tiếng chuông, và vẫn dậy
 * sau {@code idlePoll} nếu không có tiếng nào. Vế thứ hai không phải phần thừa — nó là toàn bộ
 * lý do bước này an toàn:
 *
 * <ul>
 *   <li>Broker chết → không tiếng chuông nào → worker quay về đúng hành vi M1, mọi bài vẫn
 *       được chấm ({@code nfrplan.md} 7.2, dòng RabbitMQ của bảng degraded mode).</li>
 *   <li>Reaper thu hồi một bài, hoặc {@code publishEnqueued} hỏng sau khi commit → không có
 *       tiếng chuông nào cho bài đó, nhưng nhịp chờ vẫn nhặt được nó.</li>
 * </ul>
 *
 * <p>Nói cách khác: <b>tiếng chuông là tối ưu, nhịp chờ là bảo đảm.</b> Bỏ nhịp chờ đi là
 * biến RabbitMQ từ đường dẫn thành kho chứa, và lúc đó R1 không còn được Postgres bảo đảm nữa.
 *
 * <h2>Monitor, không phải {@code Semaphore}</h2>
 * {@code Semaphore} tích luỹ giấy phép: 1000 tiếng chuông lúc cả sáu slot đang bận sẽ để lại
 * 1000 giấy phép, và khi rảnh việc các slot quay 1000 vòng claim rỗng — đúng lúc vừa hết một
 * đợt tải thì lại dội thêm 1000 request vào API. Một monitor không có trạng thái tích luỹ:
 * đánh thức ai đang chờ, không nợ ai điều gì.
 *
 * <p>Đánh thức nhầm (spurious wakeup) ở đây vô hại — hậu quả tệ nhất là một lượt
 * {@code claim} trả về 204.
 */
@Component
public class JudgeDoorbell {

    private static final Logger log = LoggerFactory.getLogger(JudgeDoorbell.class);

    private final Object khoa = new Object();

    /** Có việc mới. Đánh thức <b>mọi</b> slot đang chờ; slot nào claim được thì làm. */
    public void reo() {
        synchronized (khoa) {
            khoa.notifyAll();
        }
    }

    /**
     * Chờ tiếng chuông, tối đa {@code toiDa}.
     *
     * @return {@code false} nếu luồng bị ngắt — người gọi phải dừng vòng lặp, đây là tín hiệu
     *         tắt máy (Bước 6.8)
     */
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
