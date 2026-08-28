package dev.oj.problems.domain;

/**
 * Vòng đời của một đề. Khớp {@code CHECK (status IN ('DRAFT','PUBLISHED','RETIRED'))} ở V2.
 *
 * <p>Không có {@code DELETED}: đề không bị xoá. Một đề đã có bài nộp mà biến mất thì mọi bảng
 * xếp hạng lịch sử có nó đều sai vĩnh viễn — cùng lý do với FR-AUTH-07 (ẩn danh hoá tài khoản
 * thay vì xoá) và FR-SUB-09 (không ai xoá được bài nộp).
 */
public enum ProblemStatus {

    /**
     * Đang soạn. <b>Chỉ tác giả và ADMIN thấy</b> (FR-PROB-08).
     *
     * <p>Đây là ô quan trọng nhất của enum này: một đề {@code DRAFT} rất có thể là đề của
     * contest tuần sau. Lộ nó ra là lộ đề trước giờ thi.
     */
    DRAFT,

    /** Đã xuất bản, ai cũng xem được. */
    PUBLISHED,

    /**
     * Gỡ khỏi danh sách nhưng giữ nguyên mọi bài nộp cũ và mọi bảng xếp hạng đã có.
     *
     * <p>Không nhận bài nộp mới. Đường dẫn cũ vẫn mở được để bài nộp trong lịch sử còn tham
     * chiếu tới một trang có thật.
     */
    RETIRED;

    /** Người lạ và người dùng thường xem được đề này? */
    public boolean isPubliclyVisible() {
        return this == PUBLISHED || this == RETIRED;
    }

    /** Còn nhận bài nộp mới? */
    public boolean acceptsSubmissions() {
        return this == PUBLISHED;
    }

    public static ProblemStatus fromCode(String code) {
        for (ProblemStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("problem status không hợp lệ: " + code);
    }
}
