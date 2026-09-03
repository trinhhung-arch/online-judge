package dev.oj.platform.audit;

import dev.oj.platform.web.CursorPage;

import java.time.Instant;
import java.util.Map;

/**
 * FR-ADM-02 — ADMIN đọc {@code audit_log}, lọc và phân trang. Bước 6.5.
 *
 * <h2>Tách khỏi {@link AuditLog}, và lý do không phải là sự gọn gàng</h2>
 * {@code AuditLog} được tiêm vào <b>bảy</b> use-case đang ghi nhật ký. Nếu đọc và ghi ở chung
 * một interface thì cả bảy chỗ ấy đều cầm sẵn một hàm đọc toàn bộ nhật ký kiểm toán — và
 * ngày có người cần "hiện lịch sử thao tác" ở một trang nào đó, hàm ấy sẽ ở ngay trong tầm
 * tay, không có gì cản.
 *
 * <p>Bảng này là ô cuối cùng của ma trận hiển thị: <b>chỉ ADMIN</b>
 * ({@code oj-api/CLAUDE.md} mục 2). Một interface riêng, tiêm vào đúng một use-case có
 * {@code @RequiresRole(ADMIN)}, biến điều đó thành hình dạng của code chứ không phải một quy ước.
 */
public interface AuditLogReader {

    /**
     * @param cursor {@code null} cho trang đầu. Định dạng {@code "<epochMilli>:<id>"} — khoá
     *               chính của bảng là {@code (occurred_at, id)}, nên con trỏ phải mang cả hai;
     *               chỉ mang {@code id} là bỏ sót dòng khi hai bản ghi trùng mốc thời gian
     * @param size   đã clamp về [1..50] ở use-case
     */
    CursorPage<Entry> tim(Filter loc, String cursor, int size);

    /**
     * Ba bộ lọc của FR-ADM-02: theo người thực hiện, theo hành động, theo thời gian.
     *
     * <p>{@code null} là không lọc. Hiện thực dùng {@code (CAST(:x AS type) IS NULL OR ...)}
     * trong <b>một câu SQL hằng</b> — bất biến #5, và cũng để planner dùng lại được prepared
     * statement. Cái {@code CAST} không phải trang trí: thiếu nó, Postgres không suy được kiểu
     * của tham số và trả {@code could not determine data type of parameter}, đúng lỗi 500 đã
     * gặp ở danh sách đề tại M4.
     */
    record Filter(Long actorId, String action, Instant tu, Instant den) {

        private static final Filter NONE = new Filter(null, null, null, null);

        public static Filter none() {
            return NONE;
        }
    }

    /**
     * Một dòng nhật ký. Hình chiếu, không phải entity.
     *
     * @param actorHandle tên hiển thị của người thực hiện; {@code null} nghĩa là hệ thống
     *                    (reaper, job nền) — đúng như comment của V5 mô tả
     * @param detail      JSONB đã giải mã. <b>Người ghi chịu trách nhiệm không đưa dữ liệu
     *                    nhạy cảm vào đây</b> (bất biến #9); tầng đọc không lọc lại được, vì
     *                    nó không biết khoá nào là gì
     */
    record Entry(long id, Instant occurredAt, Long actorId, String actorHandle, String actorRole,
                 String action, String entityType, Long entityId, Map<String, Object> detail,
                 String traceId, String clientIp) {
    }
}
