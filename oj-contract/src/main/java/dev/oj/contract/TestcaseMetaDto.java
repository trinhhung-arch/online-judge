package dev.oj.contract;

/**
 * Mô tả một testcase — <b>metadata, và chỉ metadata</b>.
 *
 * <h2>Cái không có trong record này, và vì sao</h2>
 * Không có {@code inputText}, không có {@code expectedOutput}, không có gì mang nội dung.
 * Chỉ có {@code sha256}, và worker dùng nó để tải nội dung từ MinIO về cache cục bộ.
 *
 * <p>Đây là bất biến #1 (SEC3) được diễn đạt ở tầng kiểu dữ liệu: nếu nội dung testcase
 * không tồn tại trong contract thì nó không thể vô tình đi ngược từ worker về API, không
 * thể lọt vào một response, một log, hay một prompt LLM. Bảng {@code testcases} trong
 * Postgres cũng cố ý không có cột nội dung, vì cùng một lý do.
 *
 * <p>Vì sao điều đó đáng đến thế: cho người dùng xem nội dung test họ vừa sai nghe như
 * lòng tốt, nhưng nó là một <b>thuật toán rút trích</b>. Nộp một chương trình cố tình sai ở
 * test 1 → nhận nội dung test 1. Sai ở test 2 → nhận test 2. Lặp N lần là có trọn bộ test,
 * rồi nộp một bảng tra cứu đáp án và AC mọi bài ({@code frplan.md} mục 3.1).
 *
 * @param ordinal        số thứ tự trong bộ test, 1..1000 (khớp {@code testcases.ordinal})
 * @param isSample       test công khai? Chỉ test {@code sample} mới có nội dung được phép
 *                       hiển thị, và nội dung đó nằm ở bảng
 *                       {@code sample_testcase_contents} phía API — không đi qua đây
 * @param inputSha256    khoá tải input từ MinIO
 * @param outputSha256   khoá tải output kỳ vọng từ MinIO
 * @param subtaskOrdinal nhóm chứa test này, hoặc {@code null} nếu đề không chia subtask
 */
public record TestcaseMetaDto(
        int ordinal,
        boolean isSample,
        String inputSha256,
        String outputSha256,
        Integer subtaskOrdinal) {

    /** Trần số test của một đề — khớp {@code CHECK (test_count BETWEEN 1 AND 1000)}. */
    public static final int MAX_ORDINAL = 1000;

    public TestcaseMetaDto {
        ContractChecks.requireRange(ordinal, 1, MAX_ORDINAL, "ordinal");
        ContractChecks.requireSha256(inputSha256, "inputSha256");
        ContractChecks.requireSha256(outputSha256, "outputSha256");
        ContractChecks.requireNullOrRange(subtaskOrdinal, 1, 100, "subtaskOrdinal");
    }

    public boolean belongsToSubtask() {
        return subtaskOrdinal != null;
    }
}
