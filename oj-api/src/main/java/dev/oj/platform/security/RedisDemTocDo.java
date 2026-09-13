package dev.oj.platform.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Đếm bằng Redis — cửa sổ CỐ ĐỊNH, một khoá cho mỗi ô thời gian.
 *
 * <h2>★ Vì sao số hiệu ô nằm trong TÊN khoá chứ không nằm ở TTL</h2>
 * Cách quen thuộc là {@code INCR khoa} rồi {@code EXPIRE khoa} khi giá trị bằng 1. Nhưng đó là
 * hai lệnh: tiến trình chết, mạng đứt, hay Redis bị đẩy ra giữa hai lệnh là khoá ấy <b>không có
 * hạn</b> — và người dùng đó bị chặn vĩnh viễn cho tới khi ai đó xoá tay, mà không ai biết để
 * xoá. Gắn số ô vào tên khoá thì ô sau luôn là một khoá mới, nên hạn dùng chỉ còn là việc dọn
 * rác chứ không phải điều kiện đúng đắn.
 *
 * <h2>Đánh đổi đã biết của cửa sổ cố định</h2>
 * Người dùng bắn hết hạn mức ở cuối ô này rồi bắn tiếp ở đầu ô sau thì trong một khoảng ngắn họ
 * đi được gấp đôi trần. Chấp nhận: đây là lớp chống lạm dụng, không phải chốt đúng đắn, và một
 * cửa sổ trượt thật tốn một sorted-set cho MỖI người dùng đang online.
 */
@Component
public class RedisDemTocDo implements DemTocDo {

    private static final String TIEN_TO = "oj:ratelimit:api:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisDemTocDo(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public long tang(String khoa, Duration cuaSo) {
        long giay = Math.max(1, cuaSo.toSeconds());
        String o = TIEN_TO + khoa + ':' + (clock.instant().getEpochSecond() / giay);
        Long luot = redis.opsForValue().increment(o);
        // Gấp đôi cửa sổ: đủ để ô cũ tự biến mất, và không đụng tới tính đúng của phép đếm.
        redis.expire(o, Duration.ofSeconds(giay * 2));
        return luot == null ? 0 : luot;
    }
}
