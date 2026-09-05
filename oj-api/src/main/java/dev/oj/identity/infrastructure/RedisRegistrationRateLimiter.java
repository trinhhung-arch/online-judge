package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.RegistrationRateLimiter;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Đếm lượt đăng ký theo IP bằng Redis — FR-AUTH-01.
 *
 * <h2>★ Redis chết thì TỪ CHỐI, ngược với rate limit nộp bài</h2>
 * {@code RedisSubmissionRateLimiter} khi mất Redis sẽ chuyển sang đường Postgres và tiếp tục
 * cho nộp, vì điều không thể thoả hiệp thứ hai của dự án là <i>không mất bài nộp</i>. Ở đây
 * lập luận ngược lại, và cần nói rõ vì sao:
 *
 * <ul>
 *   <li><b>Không có đường dự phòng thật.</b> Postgres không lưu IP của lượt đăng ký, nên
 *       không có gì để đếm. Đường dự phòng duy nhất có thể viết là "cho qua".</li>
 *   <li><b>Cho qua là mở đúng cái cửa này.</b> Redis chết là lúc hệ thống yếu nhất; mở cửa
 *       tạo tài khoản không giới hạn vào đúng lúc ấy là hỏng theo hướng tệ nhất.</li>
 *   <li><b>Từ chối ở đây không mất gì của ai.</b> Người đã có tài khoản vẫn đăng nhập, vẫn
 *       nộp bài, vẫn xem verdict — vì cả ba đường đó không đi qua class này. Thứ duy nhất
 *       dừng lại là việc tạo tài khoản mới, và hoãn nó vài phút không phá hỏng gì.</li>
 * </ul>
 *
 * <p>Nói ngắn: mất một bài nộp là lỗi không sửa được; hoãn một lượt đăng ký thì người ta bấm
 * lại. Hai vế đó không cân nhau, nên hai class không được hỏng giống nhau.
 *
 * <h2>{@code INCR} rồi {@code EXPIRE}, và thứ tự ấy quan trọng</h2>
 * {@code INCR} trên khoá chưa tồn tại trả về 1 — đó là dấu hiệu duy nhất và đáng tin để biết
 * mình vừa mở một cửa sổ mới, nên chỉ khi ấy mới đặt hạn. Đặt hạn ở mọi lượt sẽ làm cửa sổ
 * trượt theo request cuối, và một bot gọi đều đặn sẽ giữ cửa sổ mở vĩnh viễn mà không bao giờ
 * chạm ngưỡng.
 */
@Component
public class RedisRegistrationRateLimiter implements RegistrationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRegistrationRateLimiter.class);

    private static final String KHOA_PREFIX = "oj:ratelimit:register:";

    private final StringRedisTemplate redis;
    private final int toiDa;
    private final Duration cuaSo;

    public RedisRegistrationRateLimiter(StringRedisTemplate redis, AppProperties properties) {
        this.redis = redis;
        this.toiDa = properties.auth().maxRegistrationsPerIp();
        this.cuaSo = properties.auth().registrationWindow();
    }

    @Override
    public void kiemVaGhiNhan(String clientIp) {
        String khoa = KHOA_PREFIX + clientIp;
        Long dem;
        try {
            dem = redis.opsForValue().increment(khoa);
            if (dem != null && dem == 1L) {
                redis.expire(khoa, cuaSo);
            }
        } catch (RuntimeException e) {
            // Không ghi clientIp vào log ở mức WARN cùng với lỗi hạ tầng: dòng này nói về
            // Redis, không về người gọi. IP đã có trong audit_log của lượt đăng ký thành công.
            log.warn("Redis không dùng được cho rate limit đăng ký: {}. Từ chối lượt này — "
                    + "xem javadoc để biết vì sao không cho qua.", e.toString());
            throw IdentityException.quaNhieuDangKy(cuaSo);
        }
        if (dem != null && dem > toiDa) {
            throw IdentityException.quaNhieuDangKy(conLai(khoa));
        }
    }

    /**
     * Thời gian còn lại của cửa sổ, để nói với người dùng khi nào thử lại được.
     *
     * <p>{@code getExpire} trả về số âm khi khoá không có hạn hoặc vừa biến mất giữa hai lệnh.
     * Cả hai đều hiếm và đều không đáng làm hỏng một response — rơi về đúng độ dài cửa sổ,
     * tức là ước lượng bi quan, và bi quan ở đây là an toàn.
     */
    private Duration conLai(String khoa) {
        try {
            Long giay = redis.getExpire(khoa);
            return giay != null && giay > 0 ? Duration.ofSeconds(giay) : cuaSo;
        } catch (RuntimeException e) {
            return cuaSo;
        }
    }
}
