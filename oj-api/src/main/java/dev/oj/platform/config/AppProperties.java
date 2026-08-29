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
        Auth auth,
        Jobs jobs,
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
     * Danh tính — Bước 4.5, FR-AUTH-02 và FR-AUTH-08.
     *
     * <h2>Bốn con số ở đây KHÔNG được đổi mà không hỏi người</h2>
     * {@code access-ttl} 15 phút và {@code refresh-ttl} 7 ngày là chữ của FR-AUTH-02;
     * {@code max-login-failures} 5 và {@code lockout} 15 phút là hai dòng trong bảng giới hạn
     * của {@code oj-api/CLAUDE.md} mục 8, mà mục đó kết thúc bằng đúng câu <i>"đổi bất kỳ con
     * số nào ở bảng này là phải hỏi người"</i>. Các compact constructor dưới đây <b>crash lúc
     * boot</b> nếu ai đó lặng lẽ nới chúng ra.
     *
     * <h2>Vì sao {@code access-ttl} ngắn là điều kiện để thiết kế này đúng</h2>
     * Access token <b>không tra cứu database</b> — vai trò nằm ngay trong token
     * ({@code CurrentUserProvider.CurrentUser}). Đó là thứ làm nó rẻ, và cũng là thứ làm nó
     * <i>cũ</i>: hạ vai trò một người từ ADMIN xuống USER thì token cũ vẫn còn ADMIN cho tới
     * khi hết hạn. Mười lăm phút là trần của khoảng cũ đó. Kéo dài ra để "đỡ phải refresh"
     * là kéo dài đúng khoảng thời gian ấy.
     *
     * @param jwtSecret        khoá HMAC-SHA256. Đọc từ env, không có mặc định, tối thiểu 32 ký tự
     * @param accessTtl        FR-AUTH-02 — 15 phút
     * @param refreshTtl       FR-AUTH-02 — 7 ngày
     * @param bcryptCost       FR-AUTH-01 — 12. Khớp comment trên {@code users.password_hash}
     * @param maxLoginFailures FR-AUTH-08 — 5 lần sai
     * @param loginWindow      FR-AUTH-08 — trong 1 phút
     * @param lockout          FR-AUTH-08 — khoá 15 phút
     */
    public record Auth(
            String jwtSecret,
            Duration accessTtl,
            Duration refreshTtl,
            int bcryptCost,
            int maxLoginFailures,
            Duration loginWindow,
            Duration lockout) {

        public Auth {
            if (jwtSecret == null || jwtSecret.isBlank()) {
                throw new IllegalStateException(
                        "Thiếu OJ_JWT_SECRET. Đây là khoá ký access token — chạy mà thiếu nó "
                                + "nghĩa là không phát được token nào, hoặc tệ hơn: phát bằng "
                                + "một khoá mặc định mà ai đọc mã nguồn cũng biết");
            }
            // HMAC-SHA256 sinh khoá 32 byte. Ngắn hơn thế thì phần entropy thiếu được bù bằng
            // padding của HMAC, tức là khoá yếu hơn thuật toán — hạ giá cả chữ ký lẫn token.
            if (jwtSecret.length() < 32) {
                throw new IllegalStateException("OJ_JWT_SECRET quá ngắn (cần >= 32 ký tự)");
            }
            if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()
                    || accessTtl.toMinutes() > 15) {
                throw new IllegalStateException(
                        "oj.auth.access-ttl = " + accessTtl + ". FR-AUTH-02 chốt 15 phút, và đó "
                                + "là trần của khoảng thời gian một vai trò đã bị hạ vẫn còn "
                                + "hiệu lực. Kéo dài là phải hỏi người — CLAUDE.md mục 5.4");
            }
            if (refreshTtl == null || refreshTtl.compareTo(accessTtl) <= 0) {
                throw new IllegalStateException(
                        "oj.auth.refresh-ttl (" + refreshTtl + ") phải LỚN HƠN access-ttl ("
                                + accessTtl + ") — nếu không thì refresh token hết hạn trước "
                                + "thứ nó dùng để làm mới, và người dùng bị đăng xuất mỗi 15 phút");
            }
            if (bcryptCost != 12) {
                throw new IllegalStateException(
                        "oj.auth.bcrypt-cost = " + bcryptCost + ", nhưng FR-AUTH-01 và comment "
                                + "trên users.password_hash đều ghi 12. Hạ cost là làm yếu "
                                + "TOÀN BỘ mật khẩu đã băm trước đó vẫn còn trong database");
            }
            if (maxLoginFailures != 5 || lockout == null || lockout.toMinutes() != 15
                    || loginWindow == null || loginWindow.toSeconds() != 60) {
                throw new IllegalStateException(
                        "oj.auth: FR-AUTH-08 chốt 5 lần sai / 1 phút / IP, khoá 15 phút. "
                                + "Nhận được " + maxLoginFailures + " lần / " + loginWindow
                                + " / khoá " + lockout + ". Đây là một dòng trong bảng giới hạn "
                                + "của oj-api/CLAUDE.md mục 8 — đổi là phải hỏi người");
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
