package dev.oj.platform.audit.infrastructure;

import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.trace.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Repository
public class JdbcAuditLog implements AuditLog {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditLog.class);

    private static final String KHONG_GHI_DUOC =
            "Không ghi được audit_log cho hành động {}: {}. Thao tác chính ĐÃ thành công.";

    private static final String CHEN = """
            INSERT INTO audit_log (actor_id, actor_role, action, entity_type, entity_id,
                                   detail, trace_id)
            VALUES (:actorId, :actorRole, :action, :entityType, :entityId,
                    CAST(:detail AS jsonb), :traceId)
            """;

    private final JdbcClient jdbc;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper json;

    public JdbcAuditLog(@Qualifier("appJdbcClient") JdbcClient jdbc,
                        CurrentUserProvider currentUser,
                        ObjectMapper json) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.json = json;
    }

    @Override
    public void ghi(String hanhDong, String loaiThucThe, Long idThucThe,
                    Map<String, Object> chiTiet) {
        Long actorId = null;
        String actorRole = null;
        try {
            var nguoiGoi = currentUser.current();
            actorId = nguoiGoi.id();
            actorRole = nguoiGoi.role().name();
        } catch (AuthorizationException e) {
            actorId = null;
        }
        try {
            jdbc.sql(CHEN)
                    .param("actorId", actorId)
                    .param("actorRole", actorRole)
                    .param("action", hanhDong)
                    .param("entityType", loaiThucThe)
                    .param("entityId", idThucThe)
                    .param("detail", json.writeValueAsString(chiTiet))
                    .param("traceId", TraceIdFilter.current())
                    .update();
        } catch (RuntimeException e) {
            log.warn(KHONG_GHI_DUOC, hanhDong, e.toString());
        }
    }
}
