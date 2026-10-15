package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.RegistrationRateLimiter;
import dev.oj.identity.domain.IdentityException;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

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
 * <h2>★ Đếm và đặt hạn là MỘT lệnh, không phải hai</h2>
 * Bản trước gọi {@code INCR}, rồi {@code EXPIRE} riêng khi giá trị bằng 1. Hai lệnh là có một
 * khe giữa chúng: timeout 200ms của Redis, mạng chớp, hay tiến trình chết đúng lúc ấy thì khoá
 * đã có số đếm mà <b>không có hạn</b> — và vì {@code EXPIRE} chỉ chạy khi đếm bằng 1, không
 * lượt nào sau đó đặt lại hạn nữa. Mười lượt sau, IP ấy (với IPv6 là cả dải /64 — một phòng
 * thi, một mạng 4G) bị chặn đăng ký <i>vĩnh viễn</i>, im lặng, cho tới khi ai đó xoá tay.
 * {@code RedisDemTocDo} đã ghi đúng cái bẫy này; lớp này từng rơi vào nó.
 *
 * <p>Giờ cả hai bước nằm trong một script Lua, Redis chạy nguyên khối. Và điều kiện đặt hạn là
 * "khoá <b>chưa có hạn</b>" chứ không phải "đếm bằng 1", nên khoá mồ côi do bản cũ để lại
 * cũng tự lành ở lượt kế tiếp.
 *
 * <p>Vẫn <b>chỉ</b> đặt hạn khi chưa có: đặt ở mọi lượt sẽ làm cửa sổ trượt theo request cuối,
 * và một bot gọi đều đặn sẽ giữ cửa sổ mở vĩnh viễn mà không bao giờ chạm ngưỡng.
 */
@Component
public class RedisRegistrationRateLimiter implements RegistrationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRegistrationRateLimiter.class);

    private static final String KHOA_PREFIX = "oj:ratelimit:register:";

    /** {@code PTTL} âm: khoá vừa sinh, hoặc mồ côi từ bản cũ. Cả hai đều cần hạn. */
    private static final RedisScript<Long> DEM_VA_DAT_HAN = RedisScript.of("""
            local dem = redis.call('INCR', KEYS[1])
            if redis.call('PTTL', KEYS[1]) < 0 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return dem
            """, Long.class);

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
            dem = redis.execute(DEM_VA_DAT_HAN, List.of(khoa), String.valueOf(cuaSo.toMillis()));
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
     * <p>{@code getExpire} trả về số âm khi khoá vừa biến mất giữa hai lệnh (hết hạn đúng lúc
     * ấy). Hiếm, và không đáng làm hỏng một response — rơi về đúng độ dài cửa sổ,
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
