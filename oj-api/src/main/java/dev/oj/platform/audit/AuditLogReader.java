package dev.oj.platform.audit;

import dev.oj.platform.web.CursorPage;

import java.time.Instant;
import java.util.Map;

public interface AuditLogReader {

    CursorPage<Entry> tim(Filter loc, String cursor, int size);

    record Filter(Long actorId, String action, Instant tu, Instant den) {

        private static final Filter NONE = new Filter(null, null, null, null);

        public static Filter none() {
            return NONE;
        }
    }

    record Entry(long id, Instant occurredAt, Long actorId, String actorHandle, String actorRole,
                 String action, String entityType, Long entityId, Map<String, Object> detail,
                 String traceId, String clientIp) {
    }
}
