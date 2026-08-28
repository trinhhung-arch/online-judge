package dev.oj.platform.web;

import java.util.List;
import java.util.function.Function;

/**
 * Một trang kết quả. <b>Cursor-based, và không có tổng số bản ghi.</b>
 *
 * <h2>Hai thứ vắng mặt, cả hai đều cố ý</h2>
 *
 * <p><b>Không có {@code totalCount}.</b> Muốn có nó thì phải chạy {@code COUNT(*)} trên
 * {@code submissions} — một lần quét toàn bộ bảng, trên bảng sẽ có hàng triệu dòng, cho mỗi
 * lần mở trang lịch sử. Nó phá P1 (API p95 < 200ms) và S3 ("1M+ dòng, p95 giữ nguyên") cùng
 * lúc, đổi lấy một con số mà gần như không ai đọc. Nếu một yêu cầu nào đó cần "trang 47/312",
 * hãy đọc {@code postgres-design.md} mục 15 rồi dừng lại và hỏi.
 *
 * <p><b>Không có {@code offset}.</b> {@code OFFSET 50000} bắt Postgres đọc rồi vứt bỏ 50.000
 * dòng, nên trang càng sâu càng chậm. Tệ hơn: nếu có bài nộp mới chen vào giữa hai lần gọi,
 * các dòng sẽ trượt và người dùng thấy trùng hoặc mất bản ghi. Cursor
 * {@code WHERE id < :cursor} thì luôn cùng một giá thành, và ổn định khi dữ liệu đang thay đổi.
 *
 * <h2>Vì sao cursor là {@code id} chứ không phải thời gian</h2>
 * {@code submissions.id} là {@code BIGINT IDENTITY} tăng đơn điệu, nên {@code ORDER BY id DESC}
 * <i>chính là</i> thứ tự thời gian. Đó cũng là lý do bảng không cần index trên {@code created_at}
 * và ngân sách index của bảng nóng còn chỗ trống ({@code postgres-design.md} mục 4).
 *
 * @param items      các bản ghi của trang, đã đúng thứ tự
 * @param nextCursor truyền vào lần gọi sau; {@code null} nghĩa là hết
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }

    public static <T> CursorPage<T> last(List<T> items) {
        return new CursorPage<>(items, null);
    }

    /**
     * Dựng một trang từ kết quả của một câu query đã lấy {@code pageSize + 1} dòng.
     *
     * <p>Đây là mẹo chuẩn để biết "còn nữa không" mà <b>không</b> phải chạy thêm một câu đếm:
     * xin dư đúng một dòng. Nếu về đủ {@code pageSize + 1} thì còn trang sau, và dòng dư bị
     * cắt đi chứ không trả về.
     *
     * <pre>
     *   var rows = jdbc.sql(SQL).param("pageSize", size + 1).query(...).list();
     *   return CursorPage.of(rows, size, r -&gt; String.valueOf(r.id()));
     * </pre>
     *
     * Để ở đây thay vì mỗi repository tự làm, vì đây đúng là loại chi tiết mà lần thứ ba viết
     * lại sẽ có người quên mất chữ {@code + 1}, và trang cuối sẽ mãi mãi báo "còn nữa".
     *
     * @param fetched  danh sách đã lấy, tối đa {@code pageSize + 1} phần tử
     * @param pageSize số phần tử thật sự muốn trả
     * @param cursorOf cách lấy cursor từ phần tử cuối, thường là {@code id}
     */
    public static <T> CursorPage<T> of(List<T> fetched, int pageSize, Function<T, String> cursorOf) {
        if (fetched.size() <= pageSize) {
            return last(fetched);
        }
        List<T> page = List.copyOf(fetched.subList(0, pageSize));
        return new CursorPage<>(page, cursorOf.apply(page.get(pageSize - 1)));
    }

    /**
     * Ép kích thước trang về khoảng hợp lệ.
     *
     * <p>Client xin 1000 thì <b>trả về {@code max}, không trả lỗi</b>
     * ({@code oj-api/CLAUDE.md} mục 3). Từ chối một tham số quá lớn chỉ khiến client phải đoán
     * xem trần là bao nhiêu; cắt xuống trần thì ai cũng hiểu ngay từ response đầu tiên.
     *
     * @param requested giá trị client gửi, có thể {@code null}
     */
    public static int clampSize(Integer requested, int defaultSize, int maxSize) {
        if (requested == null || requested < 1) {
            return defaultSize;
        }
        return Math.min(requested, maxSize);
    }
}
