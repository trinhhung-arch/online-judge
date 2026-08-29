package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SubmissionRateLimiter;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * FR-SUB-08 — Redis là đường chính, Postgres là đường dự phòng. Bước 4.7.
 *
 * <h2>★ Vì sao {@code SET NX PX} chứ không phải đọc rồi ghi</h2>
 * Hai request nộp bài song song của cùng một người sẽ cùng đọc thấy <i>"chưa nộp gì"</i> nếu
 * kiểm và chiếm chỗ là hai bước — và giới hạn thôi là giới hạn đúng lúc nó cần nhất, khi có
 * người bấm nút hai lần hoặc viết một vòng lặp. {@code SET NX} nguyên tử: đúng một trong hai
 * request chiếm được khoá.
 *
 * <h2>★ Redis chết thì KHÔNG mở toang, cũng KHÔNG khoá cứng</h2>
 * Hai phản ứng bản năng đều sai:
 * <ul>
 *   <li><b>Bỏ qua giới hạn</b> — biến một sự cố hạ tầng thành một cửa để nộp bài không giới
 *       hạn, đúng lúc hệ thống đang yếu nhất.</li>
 *   <li><b>Từ chối mọi bài nộp</b> — vi phạm điều không thể thoả hiệp thứ hai của dự án:
 *       <i>không mất bài nộp</i>. Redis chỉ là cache; Postgres mới là nguồn sự thật, và nó
 *       vẫn sống.</li>
 * </ul>
 * Nên đường dự phòng đọc thẳng {@code submissions} — truy vấn 7 của {@code duong_nong.sql},
 * index-only scan trên {@code ix_submissions_user_recent}, không chạm heap. Cùng một quy tắc,
 * chậm hơn một chút, và vẫn đúng.
 *
 * <p><b>Khác biệt phải biết giữa hai đường:</b> đường Redis <i>giữ chỗ trước</i> khi bài được
 * ghi; đường Postgres chỉ nhìn thấy bài đã commit. Nên khi Redis chết, hai request đến trong
 * cùng một mili giây có thể lọt cả hai. Đó là cái giá của chế độ suy giảm, và nó nhỏ hơn hẳn
 * cái giá của hai lựa chọn ở trên.
 *
 * <h2>Đường nào cũng KHÔNG được ném lỗi hạ tầng ra người dùng</h2>
 * Người nộp bài không có lỗi gì khi Redis chết. Ngoại lệ duy nhất được phép thoát ra khỏi
 * class này là {@code RATE_LIMITED} — thứ nói về hành vi của họ.
 */
@Component
public class RedisSubmissionRateLimiter implements SubmissionRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSubmissionRateLimiter.class);

    private static final String REDIS_CHET =
            "Redis không dùng được cho rate limit nộp bài: {}. Chuyển sang đường dự phòng Postgres.";

    private static final String KHOA_PREFIX = "oj:ratelimit:submit:";

    /** Giá trị không mang thông tin gì — chỉ sự tồn tại của khoá mới có nghĩa. */
    private static final String CHIEM_CHO = "1";

    private final StringRedisTemplate redis;
    private final SubmissionRepository submissions;
    private final Duration cuaSo;
    private final Clock clock;

    public RedisSubmissionRateLimiter(StringRedisTemplate redis,
                                      SubmissionRepository submissions,
                                      AppProperties properties,
                                      Clock clock) {
        this.redis = redis;
        this.submissions = submissions;
        this.cuaSo = properties.submission().rateLimit();
        this.clock = clock;
    }

    @Override
    public void kiemTraVaGhiNhan(long userId) {
        if (cuaSo.isZero() || cuaSo.isNegative()) {
            return;
        }
        try {
            quaRedis(userId);
        } catch (JudgingException e) {
            throw e;                        // quyết định về hành vi người dùng: để nó đi tiếp
        } catch (RuntimeException e) {
            log.warn(REDIS_CHET, e.toString());
            quaPostgres(userId);
        }
    }

    private void quaRedis(long userId) {
        String khoa = khoa(userId);
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(khoa, CHIEM_CHO, cuaSo))) {
            return;
        }
        Long conLaiMs = redis.getExpire(khoa, TimeUnit.MILLISECONDS);
        // Khoá vừa hết hạn giữa hai lệnh: getExpire trả -2 (không tồn tại) hoặc -1 (không TTL).
        // Cả hai đều nghĩa là người dùng đã chờ đủ, nên cho qua thay vì báo một con số âm.
        if (conLaiMs == null || conLaiMs <= 0) {
            return;
        }
        throw JudgingException.nopQuaNhanh(Duration.ofMillis(conLaiMs));
    }

    private void quaPostgres(long userId) {
        Instant ganNhat = submissions.lastSubmittedAt(userId).orElse(null);
        if (ganNhat == null) {
            return;
        }
        Duration daTroi = Duration.between(ganNhat, clock.instant());
        if (daTroi.isNegative() || daTroi.compareTo(cuaSo) >= 0) {
            return;
        }
        throw JudgingException.nopQuaNhanh(cuaSo.minus(daTroi));
    }

    private static String khoa(long userId) {
        return KHOA_PREFIX + userId;
    }
}
