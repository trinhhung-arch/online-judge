package dev.oj.contract;

/**
 * Bộ so sánh output. Thêm checker = thêm một hằng ở đây và một class hiện thực trong
 * {@code oj-worker} ({@code nfrplan.md} 8.4: "thêm checker = 1 class").
 *
 * <p>{@link #code()} khớp với {@code CHECK (checker_type IN ('exact','token','float'))}
 * trong bảng {@code problems}.
 */
public enum CheckerType {

    /** So sánh từng byte, kể cả khoảng trắng cuối dòng. */
    EXACT("exact"),
    /** So sánh theo token, bỏ qua khác biệt khoảng trắng. Mặc định của hệ thống. */
    TOKEN("token"),
    /** So sánh số thực với sai số {@code epsilon}. Bắt buộc phải có epsilon. */
    FLOAT("float");

    private final String code;

    CheckerType(String code) {
        this.code = code;
    }

    /** Giá trị lưu trong cột {@code problems.checker_type}. */
    public String code() {
        return code;
    }

    public static CheckerType fromCode(String code) {
        for (CheckerType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("checker_type không hợp lệ: " + code);
    }
}
