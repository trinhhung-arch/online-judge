package dev.oj.contract;

/**
 * Bảy kết quả chấm bài. Đây là toàn bộ danh sách — không có verdict thứ tám.
 *
 * <p>Tên hằng ở đây <b>trùng chính xác</b> với giá trị trong ràng buộc
 * {@code CHECK (verdict IN ('AC','WA','TLE','MLE','RE','CE','IE'))} của
 * {@code submissions} và {@code judge_runs}, nên {@link #name()} là giá trị ghi thẳng
 * xuống DB và không cần bảng ánh xạ.
 *
 * <p><b>Không thêm text tiếng người vào đây.</b> U3 ({@code nfrplan.md} 6.2) bắt mọi verdict
 * phải giải thích được, nhưng phần giải thích là việc của {@code VerdictExplainer} ở tầng
 * {@code api} (M3, Bước 3.11). Contract chỉ mang dữ liệu.
 */
public enum Verdict {

    /** Accepted — đúng toàn bộ. */
    AC,
    /** Wrong Answer — sai kết quả. Số thứ tự test lộ ra tới đâu do {@code feedback_level} quyết định. */
    WA,
    /** Time Limit Exceeded. */
    TLE,
    /** Memory Limit Exceeded. */
    MLE,
    /** Runtime Error — chương trình chết bằng signal hoặc mã thoát khác 0. */
    RE,
    /** Compile Error — log compiler được phép trả về cho tác giả bài nộp. */
    CE,
    /**
     * Internal Error — <b>lỗi của hệ thống, không phải của bài nộp</b>.
     *
     * <p>Worker trả {@code IE} khi nó không chắc chắn kết quả là gì. Đoán bừa một verdict
     * trong contest thì không ai phát hiện ra, và đó mới là điều tệ
     * ({@code oj-worker/CLAUDE.md} mục 6).
     */
    IE;

    /** Bài đúng hoàn toàn. */
    public boolean isAccepted() {
        return this == AC;
    }

    /**
     * Lỗi hệ thống, không phải lỗi bài nộp → API tự chấm lại tối đa 2 lần trước khi
     * báo cho người dùng (FR-SUB-12).
     */
    public boolean isSystemFailure() {
        return this == IE;
    }

    /**
     * Bài không biên dịch được → không có test nào chạy, không có subtask nào được chấm,
     * và <b>không gọi AI review</b> (vô nghĩa, {@code nfrplan.md} 10.3).
     */
    public boolean isCompileError() {
        return this == CE;
    }

    /** Verdict này đến từ việc chạy thật, nên các số đo thời gian/bộ nhớ có ý nghĩa. */
    public boolean hasRuntimeMeasurements() {
        return this != CE && this != IE;
    }

    /**
     * Ánh xạ ngược từ giá trị lưu trong DB.
     *
     * @throws IllegalArgumentException nếu giá trị không thuộc bảy verdict — cố ý ném lỗi
     *         thay vì trả {@code IE}, vì một giá trị lạ trong DB là dấu hiệu migration hỏng,
     *         không phải một bài chấm lỗi.
     */
    public static Verdict fromCode(String code) {
        for (Verdict v : values()) {
            if (v.name().equals(code)) {
                return v;
            }
        }
        throw new IllegalArgumentException("Verdict không hợp lệ: " + code);
    }
}
