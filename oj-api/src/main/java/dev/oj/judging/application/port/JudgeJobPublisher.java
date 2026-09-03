package dev.oj.judging.application.port;

/**
 * Báo cho hệ thống chấm biết có bài mới. <b>Đây là một seam</b>, và là seam có tỉ lệ
 * giá trị/công sức cao nhất của M1 ({@code docs/build-order.md} Phần 1 nguyên tắc 4).
 *
 * <pre>
 *   M1   NoopJudgeEventPublisher    chỉ ghi log; worker tự PULL theo nhịp của nó
 *   M6   RabbitJudgeJobPublisher    đẩy submissionId lên quorum queue      (Bước 6.4)
 * </pre>
 *
 * <h2>Vì sao đổi transport chỉ chạm đúng file này</h2>
 * Vì <b>{@code judge_queue} mới là sự thật, RabbitMQ chỉ là đường dẫn</b>. Hàng đã nằm trong
 * bảng và đã commit trước khi ai đó gọi hàm dưới đây; việc publish chỉ rút ngắn độ trễ từ
 * "một nhịp poll" xuống "vài mili giây". Mất sạch queue thì dựng lại bằng một câu
 * {@code SELECT} trên bảng vài trăm dòng ({@code nfrplan.md} 5.1).
 *
 * <p>Đó là lý do {@code docs/build-order.md} Bước 6.4 dám nói: nếu việc chuyển sang RabbitMQ
 * đụng vào nhiều hơn hai file, nghĩa là ở đâu đó M1 đã làm sai.
 */
public interface JudgeJobPublisher {

    /**
     * Gọi <b>sau COMMIT</b>, không bao giờ bên trong transaction.
     *
     * <p>Publish bên trong transaction là: commit hỏng sau khi đã publish → worker nhận một
     * {@code submissionId} không tồn tại ({@code oj-api/CLAUDE.md} mục 1).
     *
     * <p><b>Ném lỗi ở đây không được phép làm hỏng một bài nộp đã commit.</b> Người gọi bắt
     * mọi ngoại lệ và ghi log WARN — bài đã ở trong {@code judge_queue}, và reaper sẽ nhặt
     * nó. Đây chính là lý do reaper tồn tại.
     */
    void publishEnqueued(long submissionId);

    /**
     * Gõ cửa cho một bài vừa được {@code RejudgeJobHandler} đẩy lại vào hàng đợi —
     * FR-ADM-01, Bước 6.3.
     *
     * <h2>Vì sao một phương thức riêng chứ không dùng lại {@link #publishEnqueued}</h2>
     * Không phải vì thứ tự ưu tiên — thứ tự đến từ {@code ORDER BY (priority, enqueued_at)}
     * của câu claim, và một tiếng chuông không mang thông tin nào về việc chấm bài nào
     * trước. Lý do là <b>vận hành</b>: hai hàng đợi tách nhau thì đo được riêng ("rejudge
     * đang dồn bao nhiêu"), và ngắt binding của rejudge lúc 2 giờ sáng không đụng tới bài
     * nộp trực tiếp.
     *
     * <p>Mặc định <b>không làm gì</b>, và đó là một hiện thực đúng chứ không phải chỗ trống
     * chưa viết: hàng đã nằm trong {@code judge_queue}, nên worker sẽ thấy nó ở nhịp poll kế
     * tiếp. Tiếng chuông chỉ rút ngắn độ trễ.
     */
    default void publishRejudgeEnqueued(long submissionId) {
    }
}
