package dev.oj.platform.config;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeProgressDto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "oj")
public record AppProperties(
        Submission submission,
        Judge judge,
        Page page,
        Internal internal,
        Sse sse,
        SecurityHeadersProperties securityHeaders,
        AuthProperties auth,
        Jobs jobs,
        ContestProperties contest,
        ApiRateLimit apiRateLimit,
        Ai ai) {

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

    public record Submission(
            int maxSourceBytes,
            Duration rateLimit) {

        public Submission {
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

    public record Judge(
            Duration lease,
            Duration reaperInterval,
            int maxIeRetries,
            int maxReaps,
            int resultBatchSize,
            String referenceHostName,
            Duration hostLiveness,
            double throughputEstimate,
            Duration metricsInterval,
            RejudgeProperties rejudge) {

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
            if (maxReaps < 1) {
                throw new IllegalStateException("oj.judge.max-reaps phải >= 1, nhận " + maxReaps
                        + " — thiếu dòng ấy trong yml cũng ra 0, và 0 là IE ngay lần hết lease đầu");
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

        public int leaseSeconds() {
            return (int) lease.toSeconds();
        }
    }

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

    public record ApiRateLimit(int perUser, int perIp, Duration window) {

        public ApiRateLimit {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalStateException("oj.api-rate-limit.window = " + window + " không hợp lệ");
            }
            if (perUser < 0 || perIp < 0) {
                throw new IllegalStateException(
                        "oj.api-rate-limit: số âm không có nghĩa. 0 là TẮT, số dương là trần. "
                                + "Nhận perUser=" + perUser + ", perIp=" + perIp);
            }
            if (perIp > 0 && perUser > 0 && perIp < perUser) {
                throw new IllegalStateException(
                        "oj.api-rate-limit.per-ip (" + perIp + ") nhỏ hơn per-user (" + perUser
                                + "): một IP là NHIỀU người (phòng thi sau NAT), nên xô IP phải rộng hơn xô người");
            }
        }
    }

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
