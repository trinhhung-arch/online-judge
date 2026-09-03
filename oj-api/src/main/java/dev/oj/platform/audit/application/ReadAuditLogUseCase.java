package dev.oj.platform.audit.application;

import dev.oj.platform.audit.AuditLogReader;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * FR-ADM-02 — <b>ô cuối cùng của ma trận hiển thị</b>: {@code audit_log} chỉ ADMIN thấy.
 *
 * <p>Ngắn, và cố ý ngắn. Giá trị của nó nằm ở đúng hai dòng: {@code @RequiresRole(ADMIN)} và
 * {@code clampSize}. Cả hai đều là thứ mà nếu để ở controller thì một endpoint thứ hai đọc
 * cùng dữ liệu sẽ quên mất ({@code CLAUDE.md} bất biến #11 và #8).
 */
@RequiresRole(Role.ADMIN)
@Service
public class ReadAuditLogUseCase {

    private final AuditLogReader nhatKy;
    private final AppProperties properties;

    public ReadAuditLogUseCase(AuditLogReader nhatKy, AppProperties properties) {
        this.nhatKy = nhatKy;
        this.properties = properties;
    }

    /**
     * @param size {@code null} là mặc định 20. Xin 1000 thì nhận 50, không nhận lỗi
     *             ({@code oj-api/CLAUDE.md} mục 3)
     */
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
