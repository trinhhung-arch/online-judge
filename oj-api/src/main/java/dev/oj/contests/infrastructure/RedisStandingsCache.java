package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ★ Cache bảng xếp hạng trên Redis — FR-CON-04, P1, P8. Bước 5.7.
 *
 * <h2>Bất biến của mốc này: Redis là cache, Postgres là sự thật</h2>
 * Lớp này bọc {@link JdbcStandingsReader}. Redis chết, chậm, hay trả rác — mọi trường hợp đều
 * rơi về Postgres và trả <b>đúng</b> kết quả, chỉ chậm hơn ({@code oj-api/CLAUDE.md} mục 6,
 * degraded mode của {@code nfrplan.md} 7.2). Không có đường nào ở đây trả lỗi cho người dùng
 * vì Redis.
 *
 * <h2>★ Chỉ TOP N nằm trong cache, và đó là cách giải một mâu thuẫn trong chính đặc tả</h2>
 * {@code oj-api/CLAUDE.md} mục 6 nói hai câu ngay cạnh nhau:
 * <i>"{@code ZREVRANK} cho vị trí của chính mình"</i> và <i>"không bao giờ tải toàn bộ
 * bảng"</i>. Nhưng {@code ZREVRANK} chỉ đúng nếu ZSET chứa <b>mọi</b> thí sinh — tức là phải
 * tải toàn bộ bảng để dựng nó.
 *
 * <p>Cách giải: cache chứa đúng top N. Đó là thứ hàng trăm người cùng mở và cùng nhìn, nên nó
 * là chỗ cache mua được nhiều nhất. Còn <i>"hạng của tôi"</i> là <b>một dòng cho mỗi người</b>
 * — Postgres trả lời bằng một truy vấn có index ({@code ix_contest_standings_rank}), và đó là
 * việc nó làm tốt.
 *
 * <p>Nói cách khác: cache thứ nhiều người cùng đọc, không cache thứ mỗi người đọc một khác.
 *
 * <h2>Thứ hạng có ĐỒNG HẠNG, nên không tính từ chỉ số mảng</h2>
 * Postgres dùng {@code rank()}: hai người bằng nhau cùng hạng 1, người sau là hạng 3. Lấy
 * {@code index + 1} sẽ ra 1, 2, 3 — khác, và khác đúng ở chỗ người ta để ý nhất. Hạng luôn
 * đến từ Postgres.
 *
 * <h2>Cache bị XOÁ khi bảng đổi, không chờ hết hạn</h2>
 * {@code RedisStandingsPublisher} xoá khoá ngay sau khi {@code StandingsUpdater} commit. TTL
 * chỉ là lưới an toàn cho trường hợp lệnh xoá thất bại — nếu dựa vào TTL để làm mới thì độ
 * trễ thành TTL, và FR-CON-04 hứa 2 giây.
 */
@Primary
@Component
public class RedisStandingsCache implements StandingsReader {

    private static final Logger log = LoggerFactory.getLogger(RedisStandingsCache.class);

    private static final String REDIS_CHET =
            "Cache bảng xếp hạng không dùng được: {}. Đọc thẳng Postgres — chậm hơn, vẫn đúng.";

    /**
     * Lưới an toàn, <b>không phải</b> cơ chế làm mới. Xem javadoc của class.
     *
     * <h2>Nó cứu đúng một tình huống, và tình huống ấy có thật</h2>
     * Cache chỉ bị xoá bởi {@code RedisStandingsEventBus}, tức là bởi <b>đường ghi của ứng
     * dụng</b>. Một thay đổi đi vòng qua đường đó — người vận hành sửa tay bằng SQL để cứu một
     * sự cố, một migration đụng vào {@code contest_standings} — sẽ không xoá cache, và bảng
     * hiện ra vẫn là bảng cũ.
     *
     * <p>Đã gặp đúng tình huống này khi chạy tay lúc viết M5: một câu {@code UPDATE} thẳng vào
     * database, và trang bảng xếp hạng giữ nguyên số cũ. Không phải lỗi — nhưng là thứ người
     * sửa tay cần biết trước, nên nó được viết ra đây thay vì để họ tự khám phá lúc 2 giờ sáng.
     *
     * <p>Sáu mươi giây là khoảng người ta chịu được sau một thao tác thủ công, và đủ ngắn để
     * không ai đi tìm nguyên nhân. Muốn thấy ngay thì {@code DEL oj:standings:*}.
     */
    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcStandingsReader postgres;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisStandingsCache(JdbcStandingsReader postgres, StringRedisTemplate redis,
                               ObjectMapper json) {
        this.postgres = postgres;
        this.redis = redis;
        this.json = json;
    }

    @Override
    public List<Dong> top(long contestId, int n, boolean dongBang) {
        String khoa = StandingsKeys.zset(contestId, dongBang);
        try {
            List<Dong> tuCache = docCache(khoa, n);
            if (!tuCache.isEmpty()) {
                return tuCache;
            }
            List<Dong> tuDb = postgres.top(contestId, n, dongBang);
            napCache(khoa, tuDb);
            return tuDb;
        } catch (RuntimeException e) {
            log.warn(REDIS_CHET, e.toString());
            return postgres.top(contestId, n, dongBang);
        }
    }

    /**
     * Luôn đi thẳng Postgres — một dòng, có index, và mỗi người một khác. Xem javadoc của class.
     */
    @Override
    public Optional<Dong> cuaNguoi(long contestId, long userId, boolean dongBang) {
        return postgres.cuaNguoi(contestId, userId, dongBang);
    }

    /** Luôn đi thẳng Postgres — {@code rank()} có đồng hạng, chỉ số mảng thì không. */
    @Override
    public Optional<Integer> hang(long contestId, long userId, boolean dongBang) {
        return postgres.hang(contestId, userId, dongBang);
    }

    private List<Dong> docCache(String khoa, int n) {
        Set<String> cac = redis.opsForZSet().reverseRange(khoa, 0, n - 1L);
        if (cac == null || cac.isEmpty()) {
            return List.of();
        }
        List<Dong> ketQua = new ArrayList<>(cac.size());
        for (String s : cac) {
            ketQua.add(json.readValue(s, Dong.class));
        }
        return ketQua;
    }

    /**
     * Nạp cache. Hỏng thì bỏ qua — kết quả đã có trong tay và đúng.
     *
     * <p>Điểm ZSET là vị trí trong danh sách đã sắp, không phải một phép gói điểm và penalty.
     * Xem javadoc của {@link StandingsKeys}.
     */
    private void napCache(String khoa, List<Dong> cac) {
        if (cac.isEmpty()) {
            return;
        }
        try {
            redis.opsForZSet().add(khoa, StandingsKeys.dongThanhTuple(cac, json));
            redis.expire(khoa, TTL);
        } catch (RuntimeException e) {
            log.warn(REDIS_CHET, e.toString());
        }
    }
}
