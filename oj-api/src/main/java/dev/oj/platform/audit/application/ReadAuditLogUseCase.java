package dev.oj.platform.audit.application;

import dev.oj.platform.audit.AuditLogReader;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

import java.time.Instant;

@RequiresRole(Role.ADMIN)
@Service
public class ReadAuditLogUseCase {

    private final AuditLogReader nhatKy;
    private final AppProperties properties;

    public ReadAuditLogUseCase(AuditLogReader nhatKy, AppProperties properties) {
        this.nhatKy = nhatKy;
        this.properties = properties;
    }

    public CursorPage<AuditLogReader.Entry> tim(Long actorId, String action, Instant tu,
                                                Instant den, String cursor, Integer size) {
        AppProperties.Page page = properties.page();
        int pageSize = CursorPage.clampSize(size, page.defaultSize(), page.maxSize());
        return nhatKy.tim(new AuditLogReader.Filter(actorId, rong(action), tu, den),
                cursor, pageSize);
    }

    private static String rong(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
