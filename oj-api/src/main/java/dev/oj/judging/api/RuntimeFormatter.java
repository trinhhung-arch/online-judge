package dev.oj.judging.api;

import java.util.Locale;

/**
 * ★ Bước 3.12 · FR-SUB-11 · P7 — <b>làm tròn thời gian chạy đến 10ms</b>.
 *
 * <h2>Vì sao chữ số hàng mili giây bị vứt đi, chứ không phải "để đó cho ai cần"</h2>
 * Sai số đo là ±5%. Với một bài chạy 23ms thì khoảng tin cậy là 22–24ms, nên chữ số cuối
 * <b>không mang thông tin</b> — nó là nhiễu được in ra bằng phông chữ của sự thật.
 *
 * <p>Hiển thị nó tạo ra một trò chơi giả: người dùng thấy 23ms, nộp lại, thấy 21ms, tưởng
 * mình vừa tối ưu được. Họ không tối ưu gì cả — họ vừa lấy hai mẫu từ cùng một phân phối.
 * Rồi họ nộp thêm tám lần nữa. Mười lượt chấm của cả hệ thống, giữa giờ cao điểm, cho <b>0</b>
 * giá trị — và người trả giá là những người đang xếp hàng sau họ.
 *
 * <p>Làm tròn 10ms thì 23ms và 21ms đều là <b>20ms</b>, và trò chơi đó biến mất. Ai thật sự
 * tối ưu được thì con số vẫn nhảy: 20ms → 10ms là một khác biệt có thật.
 *
 * <h2>Domain vẫn giữ số đo thô</h2>
 * {@code judge_runs.time_ms} lưu nguyên 23. Chỉ tầng hiển thị làm tròn — vì khi
 * {@code host_factor} trôi (throttle nhiệt, đổi máy), thứ cần đối chiếu là số thật, không
 * phải số đã làm đẹp.
 */
public final class RuntimeFormatter {

    /** Bước làm tròn. Nhỏ hơn thì nhiễu lọt qua; lớn hơn thì mất khác biệt có thật. */
    public static final int ROUNDING_MS = 10;

    /**
     * Chú thích bắt buộc đi kèm mọi con số thời gian trên giao diện.
     *
     * <p>Không có nó thì "20ms" đọc như một phép đo tuyệt đối, và câu hỏi "sao máy tôi chạy
     * 15ms mà đây báo 20ms" không có câu trả lời nào ngoài tranh cãi. Có nó thì câu trả lời
     * nằm ngay cạnh con số ({@code nfrplan.md} 9.1).
     */
    public static final String MEASUREMENT_NOTE = "đo trên máy chấm chuẩn, sai số ±5%";

    private RuntimeFormatter() {
    }

    /** @return {@code null} giữ nguyên {@code null} — CE và IE không có số đo nào */
    public static Integer roundMs(Integer timeMs) {
        return timeMs == null ? null : (int) (Math.round(timeMs / (double) ROUNDING_MS) * ROUNDING_MS);
    }

    /**
     * {@code "2.03s"} — dạng dùng trong câu giải thích TLE, nơi <b>không</b> làm tròn.
     *
     * <p>Đây là ngoại lệ có chủ ý của luật trên: "2.03s / 2.00s" nói với thí sinh rằng họ
     * vượt hạn <i>một chút</i>, tức là tối ưu hằng số có thể cứu được bài. Làm tròn thành
     * "2.00s / 2.00s" thì câu đó thành vô nghĩa — và tệ hơn, nó trông như máy chấm sai.
     *
     * <p>{@code Locale.ROOT} vì {@code vi-VN} in dấu phẩy thập phân, mà {@code "2,03s"} thì
     * một nửa thế giới đọc thành hai nghìn linh ba giây.
     */
    public static String seconds(Integer timeMs) {
        return timeMs == null ? "—" : String.format(Locale.ROOT, "%.2fs", timeMs / 1000.0);
    }

    /** {@code "12.4 MB"} — người ta nghĩ bằng MB, cơ sở dữ liệu lưu bằng KB. */
    public static String memory(Integer memoryKb) {
        return memoryKb == null ? "—" : String.format(Locale.ROOT, "%.1f MB", memoryKb / 1024.0);
    }
}
