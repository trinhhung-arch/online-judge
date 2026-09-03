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
 *
 * <h2>Hai nhóm lớn nhất nằm ở file riêng</h2>
 * {@link AuthProperties} (Bước 4.5) và {@link ContestProperties} (M5) mỗi cái mang bốn con số
 * thuộc diện "đổi là phải hỏi người", nên phần javadoc giải thích <i>vì sao</i> chúng cố định
 * dài hơn phần khai báo. Để cả trong file này thì nó vượt trần 300 dòng của
 * {@code CLAUDE.md} mục 7, và quan trọng hơn: hai nhóm ấy được đọc riêng, bởi người đang sửa
 * đúng một trong hai.
 */
@ConfigurationProperties(prefix = "oj")
public record AppProperties(
        Submission submission,
        Judge judge,
        Page page,
        Internal internal,
        Sse sse,
        AuthProperties auth,
        Jobs jobs,
        ContestProperties contest,
        Ai ai) {

    /**
     * Job nền — Quy tắc 5 của {@code frplan.md}, khung ở {@code platform.jobs}.
     *
     * <h2>Vì sao {@code lease} phải LỚN HƠN {@code pollInterval} rất nhiều</h2>
     * Ngược với quan hệ giữa {@code oj.judge.lease} và {@code reaper-interval}, và lý do cũng
     * ngược: ở đó reaper phải chạy dày hơn lease để bài kẹt được nhặt nhanh. Ở đây, một job
     * <b>đang chạy thật</b> chỉ gia hạn lease mỗi lần handler gọi {@code tienDo()} — và giữa
     * hai lần ấy có thể là hàng chục giây làm việc nặng. Lease ngắn nghĩa là job bị tưởng là
     * chết rồi bị chạy song song với chính nó.
     *
     * @param lease        job im lặng quá lâu thì bị coi là bỏ rơi và đưa về {@code PAUSED}
     * @param pollInterval nhịp {@code JobRunner} hỏi database xem có việc không
     */
    public record Jobs(Duration lease, Duration pollInterval) {

        public Jobs {
            if (lease == null || pollInterval == null) {
                throw new IllegalStateException("oj.jobs.lease và oj.jobs.poll-interval bắt buộc");
            }
            if (lease.compareTo(pollInterval) <= 0) {
                throw new IllegalStateException(
                        "oj.jobs.lease (" + lease + ") phải LỚN HƠN oj.jobs.poll-interval ("
                                + pollInterval + "). Ngược lại thì mỗi nhịp thu hồi đều nhặt "
                                + "lại chính job đang chạy, và job đó chạy song song với "
                                + "chính nó trên cùng một dữ liệu");
            }
        }
    }

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
            String referenceHostName,
            Duration hostLiveness,
            double throughputEstimate,
            Duration metricsInterval,
            Rejudge rejudge) {

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
            if (hostLiveness == null || hostLiveness.isZero() || hostLiveness.isNegative()) {
                throw new IllegalStateException("oj.judge.host-liveness không hợp lệ");
            }
            if (metricsInterval == null || metricsInterval.isZero()
                    || metricsInterval.isNegative()) {
                throw new IllegalStateException("oj.judge.metrics-interval không hợp lệ");
            }
            if (throughputEstimate <= 0) {
                throw new IllegalStateException(
                        "oj.judge.throughput-estimate phải > 0 — nó là mẫu số của phép chia "
                                + "tính 'thời gian chờ ước tính' (FR-ADM-05)");
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
     * Điều tiết chấm lại hàng loạt — FR-ADM-01, Bước 6.3. Luật nằm ở
     * {@code judging.domain.RejudgeJob}; đây chỉ là ba con số.
     *
     * @param maxInFlight    trần số dòng rejudge được nằm chờ trong {@code judge_queue} cùng
     *                       lúc. <b>Đây là cách viết "30% năng lực chấm" thành một con số
     *                       kiểm được</b>: mỗi dòng chờ là nhiều nhất một judge slot có thể
     *                       bận vì rejudge, nên 2 trên 6 slot là 33%. Đổi số slot ở
     *                       {@code oj-worker} thì phải đổi số này — {@code HopDongVanHanhTest}
     *                       đọc cả hai file yml và đối chiếu, để hai con số không lệch nhau
     *                       trong im lặng
     * @param liveWaitBrake  bài nộp trực tiếp chờ lâu hơn ngần này thì rejudge về 0. Khớp
     *                       P6 (p95 queue_wait &lt; 5s) của {@code nfrplan.md} Phần 1
     * @param batchSize      bất biến #8 — mọi truy vấn đều có {@code LIMIT}
     */
    public record Rejudge(int maxInFlight, Duration liveWaitBrake, int batchSize) {

        public Rejudge {
            if (maxInFlight < 1) {
                throw new IllegalStateException(
                        "oj.judge.rejudge.max-in-flight phải >= 1, nhận " + maxInFlight
                                + ". Đặt 0 để tắt rejudge là sai chỗ: dùng công tắc "
                                + "system_settings['rejudge.enabled'], thứ ADMIN đổi được "
                                + "lúc đang chạy mà không deploy lại");
            }
            if (liveWaitBrake == null || liveWaitBrake.isNegative()) {
                throw new IllegalStateException("oj.judge.rejudge.live-wait-brake không hợp lệ");
            }
            if (batchSize < 1) {
                throw new IllegalStateException("oj.judge.rejudge.batch-size phải >= 1");
            }
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
