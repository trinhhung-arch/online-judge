package dev.oj.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chạy một việc <b>sau khi transaction đã commit</b>, không phải trong lúc nó đang mở.
 *
 * <h2>Vì sao mọi lần đẩy sự kiện đều phải đi qua đây</h2>
 * Cùng một lý do mà {@code oj-api/CLAUDE.md} mục 1 cấm publish RabbitMQ bên trong transaction
 * nộp bài: nếu commit hỏng <b>sau</b> khi đã đẩy tin, thì thế giới bên ngoài tin vào một
 * trạng thái không tồn tại trong cơ sở dữ liệu. Với SSE thì triệu chứng là trang hiện "AC"
 * cho một bài mà bảng {@code submissions} vẫn ghi là đang chấm — và F5 một cái là verdict
 * biến mất.
 *
 * <p>Chiều ngược lại (commit xong rồi đẩy hỏng) thì vô hại: dữ liệu đã đúng và đã bền, chỉ
 * mất một thông báo realtime, và fallback REST của Bước 3.10 vá ngay ở nhịp polling kế tiếp.
 * <b>Thứ tự này là thứ tự an toàn duy nhất trong hai thứ tự.</b>
 *
 * <h2>Ngoài transaction thì chạy luôn</h2>
 * Để một use-case không transaction vẫn dùng được, và để test không phải dựng transaction
 * chỉ để thấy sự kiện được đẩy.
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            execute(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                execute(action);
            }
        });
    }

    /**
     * Nuốt mọi lỗi. Đây là <b>sau</b> commit: không còn gì để rollback, và một ngoại lệ ném
     * ra từ {@code afterCommit} của Spring sẽ nổi lên chỗ gọi như thể việc chính đã hỏng —
     * trong khi việc chính đã xong và đã bền.
     */
    private static void execute(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Việc sau-commit thất bại (dữ liệu vẫn đúng): {}", e.toString());
        }
    }
}
