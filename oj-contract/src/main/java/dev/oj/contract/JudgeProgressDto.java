package dev.oj.contract;

import java.util.List;

/**
 * Tiến độ chấm giữa chừng, gửi bằng {@code POST /internal/judge/progress}.
 * <b>Khai báo ở M1, dùng từ M3</b> ({@code docs/build-order.md} Bước 3.7).
 *
 * <h2>Gửi theo lô 20 test, không gửi từng test một</h2>
 * Một bài 50 test gửi từng test là 50 round-trip cho một thứ người dùng nhìn bằng mắt.
 * DMOJ đã mắc đúng lỗi này rồi phải thêm rate limit để tự cứu. Lô 20 giảm 20 lần số
 * round-trip mà thanh tiến độ vẫn mượt ({@code nfrplan.md} 2.3 mục 7).
 *
 * <h2>⚠️ Record này là DỮ LIỆU NỘI BỘ, không phải thứ đẩy thẳng ra SSE</h2>
 * Nó mang verdict của <i>từng</i> test. Với một đề đặt {@code feedback_level = NONE}
 * (thể thức ICPC), người dùng <b>không được biết</b> mình sai ở test nào — và với
 * {@code TEST_INDEX} thì chỉ được biết số thứ tự.
 *
 * <p>Nghĩa là API <b>phải lọc theo {@code problems.feedback_level} trước khi publish lên
 * Redis pub/sub</b>. Nối thẳng payload này vào luồng SSE là mở lại đúng đường rò rỉ mà
 * FR-PROB-07 sinh ra để đóng: nộp một chương trình cố tình sai ở test k, đọc tiến độ, lặp N
 * lần là dựng lại được toàn bộ hình dạng bộ test.
 *
 * <p>Cái không có ở đây, và sẽ không bao giờ có: nội dung input, output kỳ vọng, output thực
 * tế của chương trình. Bất biến #1.
 *
 * @param submissionId id bài nộp
 * @param attempt      lần chấm; tiến độ của một attempt đã bị reaper thu hồi phải bị API bỏ qua
 * @param fromOrdinal  test đầu của lô, tính từ 1
 * @param toOrdinal    test cuối của lô
 * @param totalTests   tổng số test, để UI hiện "12/50" mà không phải hỏi thêm
 * @param outcomes     kết quả từng test trong lô
 */
public record JudgeProgressDto(
        long submissionId,
        int attempt,
        int fromOrdinal,
        int toOrdinal,
        int totalTests,
        List<TestOutcome> outcomes) {

    /** Kích thước lô chuẩn. Đổi con số này là đổi hành vi cả hai vùng — hỏi người trước. */
    public static final int BATCH_SIZE = 20;

    public JudgeProgressDto {
        ContractChecks.requirePositive(submissionId, "submissionId");
        ContractChecks.requireAtLeast(attempt, 1, "attempt");
        ContractChecks.requireRange(fromOrdinal, 1, TestcaseMetaDto.MAX_ORDINAL, "fromOrdinal");
        ContractChecks.requireRange(toOrdinal, fromOrdinal, TestcaseMetaDto.MAX_ORDINAL, "toOrdinal");
        ContractChecks.requireRange(totalTests, toOrdinal, TestcaseMetaDto.MAX_ORDINAL, "totalTests");
        outcomes = ContractChecks.frozen(outcomes);
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("outcomes rỗng — đừng gửi một lô không có gì");
        }
    }

    /** Đã chạy tới đâu, tính theo phần trăm — cho thanh tiến độ. */
    public int percentDone() {
        return (int) Math.round(100.0 * toOrdinal / totalTests);
    }

    /**
     * Kết quả một test.
     *
     * <p>Chỉ có verdict và hai số đo. <b>Không có nội dung nào</b> — không input, không output
     * kỳ vọng, không output thực tế. Nếu bạn thấy mình sắp thêm một trường {@code String} vào
     * record này, hãy đọc {@code frplan.md} mục 3.1 rồi dừng lại và hỏi.
     *
     * @param ordinal  số thứ tự test
     * @param verdict  kết quả test này
     * @param timeMs   CPU time của test, quy về máy chấm chuẩn
     * @param memoryKb bộ nhớ của test
     */
    public record TestOutcome(
            int ordinal,
            Verdict verdict,
            Integer timeMs,
            Integer memoryKb) {

        public TestOutcome {
            ContractChecks.requireRange(ordinal, 1, TestcaseMetaDto.MAX_ORDINAL, "ordinal");
            if (verdict == null) {
                throw new NullPointerException("verdict");
            }
            ContractChecks.requireNullOrRange(timeMs, 0, Integer.MAX_VALUE, "timeMs");
            ContractChecks.requireNullOrRange(memoryKb, 0, Integer.MAX_VALUE, "memoryKb");
        }
    }
}
