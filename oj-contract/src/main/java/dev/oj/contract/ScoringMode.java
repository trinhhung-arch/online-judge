package dev.oj.contract;

/**
 * Cách tính điểm của một đề. Khớp với
 * {@code CHECK (scoring_mode IN ('ALL_OR_NOTHING','SUBTASK'))} trong bảng {@code problems}.
 */
public enum ScoringMode {

    /**
     * Sai một test là trượt cả bài.
     *
     * <p>Đây là chế độ duy nhất cho phép <b>early exit</b>: test đầu tiên không phải
     * {@code AC} thì worker dừng ngay, cắt ~50% thời gian trung bình
     * ({@code nfrplan.md} 2.3 mục 1).
     */
    ALL_OR_NOTHING,

    /**
     * Điểm theo nhóm test.
     *
     * <p><b>Không được early exit</b> ở chế độ này — mỗi subtask phải được chấm đủ để
     * biết điểm nhóm, trừ những nhóm bị bỏ qua vì phụ thuộc chưa đạt (FR-PROB-06).
     */
    SUBTASK;

    /** Worker được phép dừng ngay khi gặp test sai đầu tiên? */
    public boolean allowsEarlyExit() {
        return this == ALL_OR_NOTHING;
    }
}
