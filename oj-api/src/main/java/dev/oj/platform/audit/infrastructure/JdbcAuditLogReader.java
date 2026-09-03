package dev.oj.platform.audit.infrastructure;

import dev.oj.platform.audit.AuditLogReader;
import dev.oj.platform.web.CursorPage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Hiện thực {@link AuditLogReader} — FR-ADM-02, Bước 6.5. Pool {@code app}.
 *
 * <h2>Con trỏ mang hai giá trị vì khoá chính có hai cột</h2>
 * {@code audit_log} phân mảnh theo {@code occurred_at}, nên khoá chính là
 * {@code (occurred_at, id)} chứ không phải {@code id} — một bảng phân mảnh không có khoá chính
 * chỉ gồm {@code id} được. Con trỏ chỉ mang {@code id} sẽ bỏ sót dòng ngay khi hai bản ghi
 * trùng mốc thời gian, và trong nhật ký kiểm toán thì trùng mốc là chuyện thường: một thao tác
 * ghi ba dòng trong cùng một transaction đều mang {@code now()} giống hệt nhau.
 *
 * <p>Vì thế mệnh đề phân trang là so sánh <b>bộ đôi</b>:
 * {@code (occurred_at, id) < (:cursorAt, :cursorId)} — Postgres so sánh tuple theo từ điển,
 * và nó dùng được đúng index {@code (occurred_at DESC, id DESC)} ngầm của khoá chính.
 *
 * <h2>Không có {@code COUNT(*)}, và ở bảng này thì đó không phải là sự cầu toàn</h2>
 * {@code audit_log} là bảng chỉ lớn lên. Một câu đếm để hiện "trang 3/482" sẽ quét mọi
 * partition, và nó sẽ chậm dần đúng theo tốc độ hệ thống được dùng.
 */
@Repository
public class JdbcAuditLogReader implements AuditLogReader {

    private static final TypeReference<Map<String, Object>> KIEU_DETAIL = new TypeReference<>() {
    };

    private static final String TIM = """
            SELECT a.id, a.occurred_at, a.actor_id, u.handle AS actor_handle, a.actor_role,
                   a.action, a.entity_type, a.entity_id, a.detail::text AS detail,
                   a.trace_id, host(a.client_ip) AS client_ip
              FROM audit_log a
              LEFT JOIN users u ON u.id = a.actor_id
             WHERE (CAST(:actorId AS bigint)      IS NULL OR a.actor_id   = :actorId)
               AND (CAST(:action  AS text)        IS NULL OR a.action     = :action)
               AND (CAST(:tu      AS timestamptz) IS NULL OR a.occurred_at >= :tu)
               AND (CAST(:den     AS timestamptz) IS NULL OR a.occurred_at <  :den)
               AND (CAST(:cursorAt AS timestamptz) IS NULL
                    OR (a.occurred_at, a.id) < (:cursorAt, :cursorId))
             ORDER BY a.occurred_at DESC, a.id DESC
             LIMIT :gioiHan
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcAuditLogReader(@Qualifier("appJdbcClient") JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public CursorPage<Entry> tim(Filter loc, String cursor, int size) {
        Con con = Con.doc(cursor);
        List<Entry> rows = jdbc.sql(TIM)
                .param("actorId", loc.actorId())
                .param("action", loc.action())
                .param("tu", odt(loc.tu()))
                .param("den", odt(loc.den()))
                .param("cursorAt", odt(con.at()))
                .param("cursorId", con.id())
                .param("gioiHan", size + 1)     // xin dư 1 để biết còn trang sau — CursorPage.of
                .query(this::doc)
                .list();
        return CursorPage.of(rows, size, e -> new Con(e.occurredAt(), e.id()).ghi());
    }

    private Entry doc(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String detail = rs.getString("detail");
        Long actorId = rs.getObject("actor_id", Long.class);
        Long entityId = rs.getObject("entity_id", Long.class);
        return new Entry(
                rs.getLong("id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                actorId,
                rs.getString("actor_handle"),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("entity_type"),
                entityId,
                detail == null ? Map.of() : json.readValue(detail, KIEU_DETAIL),
                rs.getString("trace_id"),
                rs.getString("client_ip"));
    }

    private static OffsetDateTime odt(Instant luc) {
        return luc == null ? null : OffsetDateTime.ofInstant(luc, ZoneOffset.UTC);
    }

    /**
     * Con trỏ {@code "<microGiayTuEpoch>:<id>"}.
     *
     * <h2>★ MICRO giây, không phải mili giây — và đây là một lỗi đã thật sự xảy ra</h2>
     * Bản đầu mã hoá bằng {@code Instant.toEpochMilli()}, và nó <b>bỏ sót dòng</b>:
     * {@code timestamptz} của Postgres có độ chính xác micro giây, nên cắt xuống mili là làm
     * con trỏ <i>lùi lại</i> tới 999 micro giây. Mệnh đề {@code (occurred_at, id) < (:cursorAt,
     * :cursorId)} khi đó loại luôn mọi dòng nằm trong cùng mili giây nhưng ở micro giây thấp
     * hơn dòng cuối trang — tức là những dòng lẽ ra phải nằm ở trang sau.
     *
     * <p>Triệu chứng: trang hai của nhật ký kiểm toán <b>thiếu vài dòng</b>, và chỉ thiếu khi
     * các bản ghi được tạo đủ nhanh để rơi vào cùng một mili giây — đúng thứ xảy ra khi một
     * thao tác quản trị ghi vài dòng liên tiếp. Nó đỏ ngẫu nhiên trong CI trước khi kịp gây
     * hại thật, và đó là toàn bộ giá trị của việc {@code VanHanhIT} chạy cùng bộ với các lớp
     * khác thay vì chạy một mình.
     *
     * <p>{@code long} micro giây đủ chỗ tới năm 294247 — không cần lo tràn.
     *
     * <h2>Con trỏ hỏng thì coi như trang đầu, KHÔNG ném</h2>
     * Ngược với {@code SubmissionRepository} nơi cursor hỏng bị từ chối. Khác biệt là chủ ý:
     * ở đó client lặp vô hạn trang đầu mà không ai biết, còn ở đây người dùng là một ADMIN
     * đang nhìn màn hình và sẽ thấy ngay mình quay về đầu danh sách.
     */
    private record Con(Instant at, Long id) {

        private static final long MICRO_MOI_GIAY = 1_000_000L;

        static Con doc(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return new Con(null, null);
            }
            int cham = cursor.indexOf(':');
            if (cham <= 0) {
                return new Con(null, null);
            }
            try {
                long micro = Long.parseLong(cursor.substring(0, cham));
                return new Con(
                        Instant.ofEpochSecond(Math.floorDiv(micro, MICRO_MOI_GIAY),
                                Math.floorMod(micro, MICRO_MOI_GIAY) * 1_000L),
                        Long.parseLong(cursor.substring(cham + 1)));
            } catch (NumberFormatException e) {
                return new Con(null, null);
            }
        }

        String ghi() {
            long micro = at.getEpochSecond() * MICRO_MOI_GIAY + at.getNano() / 1_000L;
            return micro + ":" + id;
        }
    }
}
