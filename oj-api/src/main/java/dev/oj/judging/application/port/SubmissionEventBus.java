package dev.oj.judging.application.port;

import java.time.Instant;

/**
 * ★ Bước 3.8 — kênh đẩy trạng thái bài nộp tới mọi instance API. Hiện thực:
 * {@code RedisSubmissionEventBus}.
 *
 * <h2>Vì sao là pub/sub chứ không phải một danh sách kết nối trong bộ nhớ</h2>
 * Instance đang giữ kết nối SSE của một người <b>không</b> phải instance nhận verdict từ
 * worker — worker gọi {@code /internal/judge/result} qua load balancer. Giữ danh sách kết
 * nối trong bộ nhớ thì chạy 2 instance là <b>50% người dùng không bao giờ nhận được gì</b>,
 * và triệu chứng là "thỉnh thoảng trang không tự cập nhật" — thứ không ai tái hiện được.
 * ({@code oj-api/CLAUDE.md} mục 4.)
 *
 * <h2>★ Thứ sự kiện này CỐ Ý KHÔNG MANG, và đó là điểm quan trọng nhất của cả thiết kế</h2>
 * Không có {@code failedTestOrdinal}. Không có verdict của từng test. Không có gì mà
 * {@code problems.feedback_level} có thể cấm.
 *
 * <p>{@code FeedbackLevel} đã cảnh báo đúng chỗ này: "luồng SSE tiến độ mang verdict TỪNG
 * test — đẩy thẳng nó ra là mở lại đúng đường rò rỉ mà mức NONE sinh ra để đóng". Cách chắc
 * chắn nhất để không quên bộ lọc là <b>không có gì để lọc</b>: luồng này báo <i>đã có tin
 * mới</i>, còn <i>tin đó là gì</i> thì client hỏi lại bằng {@code GET /submissions/{id}} —
 * nơi bộ lọc đã có sẵn và chỉ có một chỗ để sai.
 *
 * <p>Đổi lại một round-trip HTTP cho mỗi lần trạng thái đổi. Rẻ, và nó cũng chính là
 * <b>fallback REST bắt buộc</b> của Bước 3.10 — nên nó phải hoạt động dù có SSE hay không.
 * Một đường dẫn dữ liệu duy nhất, được kiểm ở một chỗ duy nhất.
 */
public interface SubmissionEventBus {

    /**
     * Đẩy một sự kiện. <b>Không bao giờ ném ra ngoài</b>: mất một thông báo realtime thì
     * trang chỉ chậm cập nhật cho tới nhịp polling kế tiếp, còn ném lỗi ở đây sẽ làm hỏng
     * transaction ghi verdict — tức là biến một sự cố hiển thị thành một bài nộp mất kết quả.
     */
    void publish(SubmissionEvent event);

    /**
     * Nghe sự kiện của đúng một bài nộp.
     *
     * @return đóng lại để huỷ đăng ký. <b>Bắt buộc gọi</b> khi kết nối SSE đứt, nếu không thì
     *         mỗi lần F5 của người dùng để lại một listener sống mãi
     */
    AutoCloseable subscribe(long submissionId, SubmissionEventListener listener);

    @FunctionalInterface
    interface SubmissionEventListener {
        void onEvent(SubmissionEvent event);
    }

    /**
     * Một lần trạng thái đổi.
     *
     * @param status    {@code QUEUED} · {@code JUDGING} · {@code DONE}
     * @param verdict   chỉ có khi {@code DONE}. Đây là <b>verdict tổng của bài</b>, thứ mà
     *                  mọi mức {@code feedback_level} đều cho xem — khác hẳn với verdict của
     *                  từng test
     * @param testsDone tiến độ, để vẽ thanh phần trăm. Là một CON SỐ, không phải danh sách:
     *                  "đã chạy 40/100 test" không nói gì về test nào sai
     * @param at        thời điểm sinh sự kiện; client dùng để bỏ qua sự kiện đến trễ sau khi
     *                  kết nối lại
     */
    record SubmissionEvent(
            long submissionId,
            int attempt,
            String status,
            String verdict,
            Integer testsDone,
            Integer totalTests,
            Instant at) {

        public static SubmissionEvent progress(long submissionId, int attempt,
                                               int testsDone, int totalTests) {
            return new SubmissionEvent(submissionId, attempt, "JUDGING", null,
                    testsDone, totalTests, Instant.now());
        }

        public static SubmissionEvent done(long submissionId, int attempt, String verdict) {
            return new SubmissionEvent(submissionId, attempt, "DONE", verdict,
                    null, null, Instant.now());
        }

        /** Client đóng luồng khi thấy sự kiện cuối — không chờ timeout. */
        public boolean isTerminal() {
            return "DONE".equals(status);
        }
    }
}
