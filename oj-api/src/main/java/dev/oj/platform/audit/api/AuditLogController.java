package dev.oj.platform.audit.api;

import dev.oj.platform.audit.AuditLogReader;
import dev.oj.platform.audit.application.ReadAuditLogUseCase;
import dev.oj.platform.web.CursorPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FR-ADM-02 — {@code GET /api/v1/admin/audit-log}. Bước 6.5.
 *
 * <p>Không có {@code @RequiresRole} ở đây: nó nằm trên {@link ReadAuditLogUseCase}
 * (bất biến #11). Một bản sao ở controller chỉ tạo ra hai chỗ có thể lệch nhau.
 *
 * <h2>{@code Instant} qua query param</h2>
 * Nhận ISO-8601 ({@code 2026-08-30T00:00:00Z}). Spring tự chuyển; sai định dạng thì
 * {@code GlobalExceptionHandler} trả 400 với câu chữ của nó, không phải 500.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-log")
public class AuditLogController {

    private final ReadAuditLogUseCase doc;

    public AuditLogController(ReadAuditLogUseCase doc) {
        this.doc = doc;
    }

    @GetMapping
    public Map<String, Object> tim(@RequestParam(required = false) Long actorId,
                                   @RequestParam(required = false) String action,
                                   @RequestParam(required = false) Instant tu,
                                   @RequestParam(required = false) Instant den,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer size) {
        CursorPage<AuditLogReader.Entry> trang = doc.tim(actorId, action, tu, den, cursor, size);
        List<Dong> items = trang.items().stream().map(Dong::tu).toList();
        return Map.of("items", items,
                "nextCursor", trang.nextCursor() == null ? "" : trang.nextCursor());
    }

    /**
     * DTO ở tầng {@code api}, không trả thẳng {@code AuditLogReader.Entry}
     * ({@code CLAUDE.md} mục 7).
     *
     * <p>Khác biệt thật giữa hai kiểu: bản này <b>không mang {@code traceId}</b>. Mã ấy dùng
     * để nối các dòng log với nhau khi gỡ lỗi, và nó là một chi tiết nội bộ — trả nó ra một
     * bề mặt HTTP là mời người ta xây công cụ dựa vào hình dạng log của ta.
     */
    public record Dong(long id, Instant luc, Long nguoiThucHienId, String nguoiThucHien,
                       String vaiTro, String hanhDong, String loaiThucThe, Long idThucThe,
                       Map<String, Object> chiTiet, String ip) {

        static Dong tu(AuditLogReader.Entry e) {
            return new Dong(e.id(), e.occurredAt(), e.actorId(),
                    e.actorHandle() == null ? "hệ thống" : e.actorHandle(),
                    e.actorRole(), e.action(), e.entityType(), e.entityId(),
                    e.detail(), e.clientIp());
        }
    }
}
