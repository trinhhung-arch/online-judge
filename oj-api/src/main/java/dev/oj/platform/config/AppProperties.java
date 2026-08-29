package dev.oj.platform.config;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeProgressDto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Mọi ngưỡng, giới hạn, timeout của hệ thống — một chỗ duy nhất.
 *
 * <p>{@code CLAUDE.md} mục 7: <i>"Không có số ma thuật rải trong code."</i> Nếu bạn sắp gõ một
 * con số vào giữa một use-case, nó thuộc về đây.
 *
 * <h2>Phân biệt với bảng {@code system_settings}</h2>
 * <ul>
 *   <li><b>File này</b> — ngưỡng cố định, đổi thì deploy lại. Ví dụ: lease reaper, kích thước trang.</li>
 *   <li><b>{@code system_settings}</b> — công tắc ADMIN phải bật/tắt được <i>lúc 2 giờ sáng
 *       giữa contest mà không deploy lại</i>: {@code submissions.accepting} (FR-ADM-06),
 *       {@code ai_review.enabled} (FR-AI-09), {@code rejudge.enabled}.</li>
 * </ul>
 *
 * <h2>⚠️ Bảy con số dưới đây đổi là PHẢI HỎI NGƯỜI</h2>
 * {@code CLAUDE.md} mục 5.4: số judge slot · lease reaper 120s · rate limit 10s ·
 * quota AI 5/ngày · ZIP 200MB · source 64KB. Chúng nằm trong schema DB, trong
 * {@code oj-contract}, hoặc trong cả hai — đổi một phía là lệch phía kia.
 *
 * <p>Để chuyện lệch đó không xảy ra âm thầm, các compact constructor dưới đây <b>đối chiếu
 * ngược với hằng số trong {@code oj-contract} và crash lúc boot nếu không khớp</b>. Cùng tinh
 * thần với {@code EnvVarStartupCheck}: thà không khởi động được còn hơn chạy sai.
 */
@ConfigurationProperties(prefix = "oj")
public record AppProperties(
        Submission submission,
        Judge judge,
        Page page,
        Internal internal,
        Sse sse,
        Ai ai) {

    /**
     * Luồng realtime chi tiết bài nộp — Bước 3.9, FR-SUB-05.
     *
     * @param timeout   trần thời gian sống của một kết nối SSE. <b>Phải nhỏ hơn trần của
     *                  Cloudflare Tunnel</b>: nếu tunnel cắt trước thì client thấy một kết
     *                  nối chết mà không có sự kiện kết thúc nào, còn nếu ta chủ động đóng
     *                  thì client biết đường mở lại. Chủ động luôn tốt hơn bị động
     * @param heartbeat nhịp gửi comment giữ kết nối. Proxy và tunnel đóng kết nối im lặng
     *                  quá lâu, và "im lặng" là trạng thái BÌNH THƯỜNG của trang này —
     *                  một bài chấm 30 giây thì không có gì để nói suốt 30 giây đó
     */
    public record Sse(Duration timeout, Duration heartbeat) {

        public Sse {
            if (timeout == null || heartbeat == null) {
                throw new IllegalStateException("oj.sse.timeout và oj.sse.heartbeat bắt buộc");
            }
            if (heartbeat.compareTo(timeout) >= 0) {
                throw new IllegalStateException(
                        "oj.sse.heartbeat (" + heartbeat + ") phải nhỏ hơn timeout (" + timeout
                                + ") — nếu không thì không nhịp giữ kết nối nào kịp gửi");
            }
        }
    }

    /** Nộp bài — FR-SUB-01, FR-SUB-08. */
    public record Submission(
            int maxSourceBytes,
            Duration rateLimit) {

        public Submission {
            // Cùng một con số sống ở ba nơi: CHECK trong DB, hằng trong oj-contract, và đây.
            // Lệch nhau nghĩa là API nhận một bài mà worker sẽ từ chối — hoặc ngược lại.
            if (maxSourceBytes != JudgeJobDto.MAX_SOURCE_BYTES) {
                throw new IllegalStateException(
                        "oj.submission.max-source-bytes = " + maxSourceBytes
                                + " nhưng JudgeJobDto.MAX_SOURCE_BYTES = " + JudgeJobDto.MAX_SOURCE_BYTES
                                + ". Hai con số này phải bằng nhau, và cả hai phải khớp "
                                + "CHECK (byte_size <= 65536) trên source_blobs. "
                                + "Đổi là phải hỏi người — CLAUDE.md mục 5.4");
            }
            if (rateLimit == null || rateLimit.isNegative()) {
                throw new IllegalStateException("oj.submission.rate-limit không hợp lệ");
            }
        }
    }

    /** Chấm bài — hàng đợi, reaper, IE retry. */
    public record Judge(
            Duration lease,
            Duration reaperInterval,
            int maxIeRetries,
            int resultBatchSize,
            String referenceHostName) {

        public Judge {
            if (lease == null || lease.isZero() || lease.isNegative()) {
                throw new IllegalStateException("oj.judge.lease không hợp lệ");
            }
            if (reaperInterval == null || reaperInterval.compareTo(lease) >= 0) {
                throw new IllegalStateException(
                        "oj.judge.reaper-interval (" + reaperInterval + ") phải NHỎ HƠN "
                                + "oj.judge.lease (" + lease + "). Reaper chạy thưa hơn lease "
                                + "nghĩa là một bài kẹt phải chờ tới hai chu kỳ mới được nhặt lại");
            }
            if (maxIeRetries < 0) {
                throw new IllegalStateException("oj.judge.max-ie-retries phải >= 0");
            }
            if (resultBatchSize != JudgeProgressDto.BATCH_SIZE) {
                throw new IllegalStateException(
                        "oj.judge.result-batch-size = " + resultBatchSize
                                + " nhưng JudgeProgressDto.BATCH_SIZE = " + JudgeProgressDto.BATCH_SIZE
                                + ". Đổi kích thước lô là đổi hành vi cả oj-api lẫn oj-worker");
            }
        }

        /** Giây, để ghép thẳng vào tham số {@code :leaseSeconds} của truy vấn claim. */
        public int leaseSeconds() {
            return (int) lease.toSeconds();
        }
    }

    /**
     * Phân trang — bất biến #8.
     *
     * <p>Client xin 1000 thì trả {@code maxSize}, <b>không trả lỗi</b>
     * ({@code oj-api/CLAUDE.md} mục 3). Từ chối một tham số quá lớn chỉ làm client phải
     * đoán, còn cắt xuống trần thì ai cũng hiểu.
     */
    public record Page(int defaultSize, int maxSize) {

        public Page {
            if (defaultSize < 1 || maxSize < defaultSize) {
                throw new IllegalStateException(
                        "oj.page: cần 1 <= default-size <= max-size, nhận được "
                                + defaultSize + " / " + maxSize);
            }
            if (maxSize > 50) {
                throw new IllegalStateException(
                        "oj.page.max-size > 50 — bất biến #8 và bảng SLO S3. "
                                + "submissions sẽ có hàng triệu dòng");
            }
        }
    }

    /**
     * Hai endpoint {@code /internal/judge/*}.
     *
     * <p>Secret đọc từ env và <b>crash lúc boot nếu thiếu</b> — không có giá trị mặc định,
     * cố ý. Một secret mặc định là một secret công khai.
     */
    public record Internal(String sharedSecret) {

        public Internal {
            if (sharedSecret == null || sharedSecret.isBlank()) {
                throw new IllegalStateException(
                        "Thiếu OJ_INTERNAL_SHARED_SECRET. Hai endpoint /internal/judge/* xác thực "
                                + "bằng secret này, không phải JWT người dùng — chạy mà thiếu nó "
                                + "nghĩa là bất kỳ ai gọi được /internal cũng ghi được verdict");
            }
            if (sharedSecret.length() < 32) {
                throw new IllegalStateException("OJ_INTERNAL_SHARED_SECRET quá ngắn (cần >= 32 ký tự)");
            }
        }
    }

    /**
     * AI Code Reviewer — tuần 14-15. Khai báo từ M1 vì quota 5/ngày là con số đã chốt
     * ({@code CLAUDE.md} mục 5.4) và nó nên nằm cạnh các con số còn lại ngay từ đầu.
     *
     * <p>Công tắc bật/tắt <b>không</b> ở đây — nó là {@code ai_review.enabled} trong
     * {@code system_settings}, vì ADMIN phải tắt được tức thì mà không deploy (FR-AI-09).
     */
    public record Ai(int dailyQuota, Duration timeout) {

        public Ai {
            if (dailyQuota < 0) {
                throw new IllegalStateException("oj.ai.daily-quota phải >= 0");
            }
            if (timeout == null || timeout.toSeconds() > 30) {
                throw new IllegalStateException(
                        "oj.ai.timeout cứng 30s, không có ngoại lệ (nfrplan.md 10.5)");
            }
        }
    }
}
