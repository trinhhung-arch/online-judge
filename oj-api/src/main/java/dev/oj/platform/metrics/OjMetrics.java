package dev.oj.platform.metrics;

/**
 * Tên các chỉ số Micrometer — Bước 6.10, một nguồn sự thật cho cả nơi ghi lẫn nơi đọc.
 *
 * <h2>Vì sao một file hằng số cho việc này</h2>
 * Một chỉ số được <b>đăng ký</b> ở module sở hữu dữ liệu và được <b>đọc</b> ở
 * {@code platform.ops}. Hai chỗ tự gõ chuỗi là hai chỗ có thể lệch, và triệu chứng của việc
 * lệch là <i>một ô trống trên dashboard</i> — không có ngoại lệ nào, không có log nào, và
 * không ai nhận ra cho tới lúc cần nhìn nó nhất.
 *
 * <p>Cùng lập luận đã tạo ra {@code JudgeEndpoints}: thứ gì hai bên phải đồng ý thì nằm ở một
 * chỗ.
 *
 * <h2>Chiều phụ thuộc vẫn đúng</h2>
 * File này ở {@code platform} và <b>không import module nào</b> — nó là từ vựng, không phải
 * phụ thuộc. Cùng quan hệ như {@code JobType} kể tên việc của nghiệp vụ mà không import ai.
 *
 * <h2>Ánh xạ sang bảng SLO ({@code nfrplan.md} Phần 1)</h2>
 * <pre>
 *   P1, P2   http.server.requests           Boot tự đo, có histogram (xem application.yml)
 *   P4       oj.judge.finished              đếm verdict -> throughput là đạo hàm của nó
 *   P6       oj.queue.wait.ms               bài chờ lâu nhất đang trong hàng đợi
 *   P8       oj.standings.drift             số ô lệch giữa Redis và Postgres
 *   R3       oj.judge.finished{verdict=IE}  tỉ lệ IE tính từ cùng một counter với P4
 *   —        oj.queue.waiting / .judging    độ sâu hàng đợi, FR-ADM-04 và FR-ADM-05
 *   —        oj.workers.live                số máy chấm còn báo danh
 *   AI2      oj.ai.tokens                   tuần 14–15
 * </pre>
 */
public final class OjMetrics {

    /** Độ sâu hàng đợi — {@code claimed_at IS NULL}. */
    public static final String QUEUE_WAITING = "oj.queue.waiting";

    /** Đang được chấm. */
    public static final String QUEUE_JUDGING = "oj.queue.judging";

    /**
     * P6 — bài chờ lâu nhất đang trong hàng đợi, mili giây.
     *
     * <p>Là {@code Gauge}, không phải {@code Timer}: nó đo <b>tình trạng hiện tại</b> chứ
     * không phải phân bố của những lần đã xong. Một {@code Timer} chỉ ghi được sau khi bài đã
     * được chấm, nên đúng lúc hàng đợi tắc — lúc duy nhất chỉ số này quan trọng — nó không có
     * dữ liệu mới nào để báo.
     */
    public static final String QUEUE_WAIT_MS = "oj.queue.wait.ms";

    /** Số dòng {@code priority = 10} đang chờ. Cho thấy rejudge có đang bị phanh không. */
    public static final String QUEUE_REJUDGE_WAITING = "oj.queue.rejudge.waiting";

    /** Máy chấm còn báo danh trong cửa sổ liveness. Xem {@code JudgeHostHealthIndicator}. */
    public static final String WORKERS_LIVE = "oj.workers.live";

    /**
     * P4 và R3 từ <b>một</b> counter, tách bằng tag {@code verdict}.
     *
     * <p>Tỉ lệ IE là {@code oj.judge.finished{verdict=IE} / oj.judge.finished}. Hai counter
     * rời nhau thì mẫu số và tử số có thể lấy ở hai thời điểm khác nhau, và tỉ lệ vọt lên vô
     * lý đúng lúc tải cao.
     */
    public static final String JUDGE_FINISHED = "oj.judge.finished";

    /** Tag của {@link #JUDGE_FINISHED}. */
    public static final String TAG_VERDICT = "verdict";

    /** P8 — số ô lệch mà {@code StandingsDriftCheckJob} tìm thấy ở lần chạy gần nhất. */
    public static final String STANDINGS_DRIFT = "oj.standings.drift";

    private OjMetrics() {
    }
}
