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
                .param("gioiHan", size + 1)
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
