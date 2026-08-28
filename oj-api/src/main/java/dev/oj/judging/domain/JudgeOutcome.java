package dev.oj.judging.domain;

import dev.oj.contract.Verdict;

/**
 * Kết quả của <b>một</b> lần chấm: verdict, điểm, và ba con số đo được.
 *
 * <p>Cùng một nhóm dữ liệu này xuất hiện ở hai chỗ trong V3, nên nó là một kiểu chứ không
 * phải sáu tham số rời:
 * <ul>
 *   <li>{@link Submission} — <b>ảnh chụp</b> của attempt gần nhất, để trang chi tiết không
 *       phải join;</li>
 *   <li>{@link JudgeRun} — <b>bản ghi bất biến</b> của từng attempt, không bao giờ bị ghi đè.</li>
 * </ul>
 *
 * <p>Sáu tham số rời thì {@code (score, maxScore)} và {@code (timeMs, memoryKb)} hoán vị cho
 * nhau mà vẫn biên dịch được, và một bài 100/0 điểm trông y như một bài 0/100 điểm cho tới
 * lúc có người nhìn bảng xếp hạng.
 *
 * <h2>Vì sao dùng thẳng {@code contract.Verdict}</h2>
 * Cùng lý do đã ghi ở {@code problems.domain.Problem}: {@code oj-contract} chỉ phụ thuộc JDK
 * nên domain import nó vẫn qua cả bốn luật ArchUnit, {@link Verdict#name()} là đúng giá trị
 * trong {@code CHECK (verdict IN (...))} của cả {@code submissions} lẫn {@code judge_runs},
 * và bảy verdict là <b>từ vựng chung</b> của hai tiến trình. Định nghĩa lại một enum y hệt
 * rồi viết mapper là nghi lễ — và nghi lễ nào cũng có ngày lệch nhau, ở đây là ngày ai đó
 * thêm verdict thứ tám vào một phía.
 *
 * @param verdict           một trong bảy — FR-SUB-04
 * @param score             điểm đạt được, luôn {@code <= maxScore}
 * @param maxScore          điểm tối đa của đề tại phiên bản testdata đã dùng
 * @param failedTestOrdinal <b>chỉ số thứ tự</b> test sai, hoặc {@code null}. Đây là toàn bộ
 *                          những gì hệ thống lưu về "bài sai ở đâu" — không có nội dung test
 *                          ở bất kỳ đâu trong {@code oj-api} (bất biến #1). Con số này còn bị
 *                          lọc thêm một lần theo {@code problems.feedback_level} trước khi
 *                          tới người dùng (FR-PROB-07)
 * @param timeMs            CPU time lớn nhất qua các test, đã quy về máy chấm chuẩn.
 *                          Hiển thị thì <b>làm tròn 10ms</b> — chữ số hàng mili giây là nhiễu
 *                          (P7, FR-SUB-11), nhưng làm tròn là việc của tầng {@code api}:
 *                          domain giữ số đo thật để còn đối chiếu khi hệ số máy trôi
 * @param memoryKb          bộ nhớ lớn nhất qua các test
 */
public record JudgeOutcome(
        Verdict verdict,
        int score,
        int maxScore,
        Integer failedTestOrdinal,
        Integer timeMs,
        Integer memoryKb) {

    public JudgeOutcome {
        if (verdict == null) {
            throw new NullPointerException("verdict — markDone từ chối một kết quả không có verdict");
        }
        if (score < 0 || maxScore < 0) {
            throw new IllegalArgumentException("điểm không âm: " + score + "/" + maxScore);
        }
        if (score > maxScore) {
            throw new IllegalArgumentException("score (" + score + ") > maxScore (" + maxScore + ")");
        }
        if (failedTestOrdinal != null
                && (failedTestOrdinal < 1 || failedTestOrdinal > DomainRules.MAX_TEST_ORDINAL)) {
            throw new IllegalArgumentException(
                    "failedTestOrdinal ngoài [1.." + DomainRules.MAX_TEST_ORDINAL + "]: "
                            + failedTestOrdinal);
        }
        // AC mà vẫn chỉ ra một test sai là mâu thuẫn nội tại. JudgeResultDto đã chặn nó ở
        // biên HTTP, trước mọi lần ghi DB; ở đây là hàng rào thứ hai cho đường rejudge và
        // đường đọc lại từ DB, nơi không có constructor nào của oj-contract chạy qua.
        if (verdict.isAccepted() && failedTestOrdinal != null) {
            throw new IllegalArgumentException("verdict=AC nhưng failedTestOrdinal=" + failedTestOrdinal);
        }

        // CHUẨN HOÁ, KHÔNG NÉM: CE thì chưa test nào chạy, IE thì worker không chắc chắn điều
        // gì cả — hai con số đo trong hai trường hợp đó vô nghĩa. Ném lỗi ở đây sẽ chặn đúng
        // đường ghi verdict, và một verdict không ghi được là một bài quay lại hàng đợi mãi
        // mãi. "0.00s" hiện trên trang một bài CE thì chỉ khó hiểu; vòng lặp reaper thì chết.
        if (!verdict.hasRuntimeMeasurements()) {
            timeMs = null;
            memoryKb = null;
        }
        if (timeMs != null && timeMs < 0) {
            timeMs = null;
        }
        if (memoryKb != null && memoryKb < 0) {
            memoryKb = null;
        }
    }

    /** Bài đúng toàn bộ. */
    public boolean isAccepted() {
        return verdict.isAccepted();
    }

    /**
     * Lỗi hệ thống chứ không phải lỗi bài nộp → API cho chấm lại tối đa 2 lần trước khi hiện
     * {@code IE} cho người dùng (FR-SUB-12). Nhánh đó rẽ <b>trước</b> khoá lạc quan trong
     * {@code RecordJudgeResult}, xem {@link JudgeQueueEntry#canRetryIe(int)}.
     */
    public boolean isSystemFailure() {
        return verdict.isSystemFailure();
    }
}
