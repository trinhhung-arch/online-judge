package dev.oj.platform.settings.infrastructure;

import dev.oj.platform.settings.SystemSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hiện thực {@link SystemSettings} trên Postgres, có <b>cache ngắn hạn</b>.
 *
 * <h2>★ Cache 2 giây, và cả hai đầu của con số đó đều có lý do</h2>
 * {@code SubmitSolutionUseCase} đọc công tắc này ở <b>mỗi lần nộp bài</b>, trong ngân sách
 * 300ms của P2. Không cache thì mỗi bài nộp tốn thêm một lượt round-trip trên pool {@code app}
 * — đúng cái pool đang chịu tải lúc 500 người nộp cùng lúc, và đúng lúc ADMIN có thể đang
 * muốn bật chế độ bảo trì.
 *
 * <p>Nhưng cache <b>dài</b> thì tệ hơn: bật bảo trì rồi phải chờ mới có hiệu lực là một công
 * tắc không dùng được trong sự cố. Hai giây là khoảng mà cả hai vấn đề đều nhỏ — người vận
 * hành không phân biệt được "ngay lập tức" với "hai giây", còn đường nộp bài mất đúng một
 * truy vấn mỗi hai giây thay vì một truy vấn mỗi bài.
 *
 * <p>{@link #dat} <b>xoá cache ngay</b>, nên chính instance nhận lệnh có hiệu lực tức thì;
 * hai giây chỉ là trần cho các instance khác. Một API chạy nhiều instance thì đây là đường
 * lan truyền duy nhất, và nó đủ — cùng lập luận với TTL 60s của bảng xếp hạng ở M5.
 *
 * <h2>Đọc hỏng thì trả mặc định, không ném</h2>
 * Database chết mà đường nộp bài cũng chết theo là hai sự cố thay vì một. Nhưng chú ý: ở đây
 * điều đó gần như lý thuyết — nếu Postgres chết thì câu {@code INSERT submissions} ngay sau
 * cũng hỏng. Giá trị thật của nhánh này là chống <i>lỗi tạm thời</i>: một lần timeout pool
 * không được biến thành "hệ thống ngừng nhận bài".
 */
@Repository
public class JdbcSystemSettings implements SystemSettings {

    private static final Logger log = LoggerFactory.getLogger(JdbcSystemSettings.class);

    /** Xem javadoc lớp. Không đưa vào {@code application.yml}: đây là chi tiết hiện thực của
     *  cache, không phải một ngưỡng nghiệp vụ ai cần chỉnh. */
    private static final Duration TTL = Duration.ofSeconds(2);

    private static final String DOC = """
            SELECT value #>> '{}' FROM system_settings WHERE key = :khoa
            """;

    private static final String GHI = """
            INSERT INTO system_settings (key, value, updated_by, updated_at)
            VALUES (:khoa, CAST(:giaTri AS jsonb), :nguoiDoi, now())
            ON CONFLICT (key) DO UPDATE
               SET value = EXCLUDED.value,
                   updated_by = EXCLUDED.updated_by,
                   updated_at = EXCLUDED.updated_at
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public JdbcSystemSettings(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * ★ Cache lưu <b>thứ database nói</b>, không lưu thứ người gọi đầu tiên nhận được.
     *
     * <h2>Một lỗi thật, bắt bởi {@code VanHanhIT.khoa_la_thi_tra_mac_dinh}</h2>
     * Bản đầu của lớp này cache thẳng giá trị đã áp mặc định. Với một khoá <b>không tồn
     * tại</b>, người gọi đầu tiên truyền {@code macDinh = true} sẽ nhét {@code true} vào
     * cache, và người gọi thứ hai truyền {@code macDinh = false} nhận lại {@code true} — mặc
     * định của người khác.
     *
     * <p>Nghe như một ca biên, nhưng nó nằm đúng trên đường nguy hiểm nhất: {@code ai_review
     * .enabled} có mặc định {@code false} (tính năng chưa mở), còn {@code submissions
     * .accepting} có mặc định {@code true}. Một khoá bị xoá nhầm khỏi {@code system_settings}
     * là đủ để hai bên mượn mặc định của nhau, và cái sai theo chiều "bật một tính năng chưa
     * sẵn sàng" thì không có thông báo nào.
     *
     * <p>Nên cache giữ ba trạng thái — {@code true}, {@code false}, và <i>không có</i> —
     * còn mặc định được áp <b>ở mỗi lần đọc</b>, bởi chính người biết mình muốn gì.
     */
    @Override
    public boolean bat(String khoa, boolean macDinh) {
        Cached cu = cache.get(khoa);
        Instant bayGio = clock.instant();
        if (cu != null && cu.hetHan().isAfter(bayGio)) {
            return cu.giaTri() == null ? macDinh : cu.giaTri();
        }
        Boolean giaTri = docTuDb(khoa);
        cache.put(khoa, new Cached(giaTri, bayGio.plus(TTL)));
        return giaTri == null ? macDinh : giaTri;
    }

    /** @return {@code null} nghĩa là "database không nói gì" — khoá không có, hoặc đọc hỏng */
    private Boolean docTuDb(String khoa) {
        try {
            return jdbc.sql(DOC).param("khoa", khoa)
                    .query(String.class).optional()
                    .map(Boolean::parseBoolean)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Không đọc được công tắc '{}': {}. Người gọi dùng mặc định của mình.",
                    khoa, e.toString());
            return null;
        }
    }

    @Override
    public void dat(String khoa, boolean giaTri, Long nguoiDoi) {
        jdbc.sql(GHI)
                .param("khoa", khoa)
                .param("giaTri", Boolean.toString(giaTri))
                .param("nguoiDoi", nguoiDoi)
                .update();
        // Xoá thay vì ghi đè: lần đọc kế tiếp lấy lại từ database, nên nếu câu UPDATE ở trên
        // không làm điều ta tưởng thì ta thấy ngay, chứ không thấy một cache nói dối.
        cache.remove(khoa);
    }

    /** @param giaTri {@code null} = khoá không tồn tại trong {@code system_settings} */
    private record Cached(Boolean giaTri, Instant hetHan) {
    }
}
